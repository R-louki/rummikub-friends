package com.rummikub.app.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.rummikub.core.model.NetworkPacket
import com.rummikub.core.model.NetworkPacket.VoiceSignalPacket.SignalType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Peer-to-peer live voice between the players of a room (full mesh, 2 to 4 peers).
 *
 * Signalling (SDP offers/answers, ICE candidates) travels through the game server as
 * `voice_signal` packets. The peer with the lexicographically smaller player id always
 * makes the offer, which avoids offer collisions.
 *
 * Needs the RECORD_AUDIO permission before [start] is called.
 */
class WebRtcVoiceEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val sendSignal: suspend (NetworkPacket.VoiceSignalPacket) -> Unit,
    private val onLocalAudioLevelDb: (Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localTrack: org.webrtc.AudioTrack? = null

    private val peers = ConcurrentHashMap<String, Peer>()
    @Volatile private var myId: String = ""
    @Volatile private var micEnabled: Boolean = true
    @Volatile private var remoteVolume: Float = 1f
    private val remoteMuted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var lastLevelReportMs = 0L

    @Volatile private var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    val isRunning: Boolean get() = factory != null

    /** Human-readable state of the live voice, shown in the game menu (helps to find why it is silent). */
    private val _status = MutableStateFlow("démarrage…")
    val status: StateFlow<String> = _status.asStateFlow()
    private var retryJob: Job? = null

    private fun refreshStatus() {
        _status.value = when {
            factory == null -> "inactive"
            peers.isEmpty() -> "en attente d'un autre joueur"
            else -> {
                val ok = peers.values.count {
                    it.ice == PeerConnection.IceConnectionState.CONNECTED ||
                        it.ice == PeerConnection.IceConnectionState.COMPLETED
                }
                when {
                    ok == peers.size -> "connectée (${peers.size} joueur${if (peers.size > 1) "s" else ""})"
                    ok > 0 -> "$ok/${peers.size} connectés"
                    peers.values.any { it.ice == PeerConnection.IceConnectionState.FAILED } -> "échec réseau (relais nécessaire)"
                    else -> "connexion en cours…"
                }
            }
        }
    }

    /**
     * An offer sent while the other phone had not started its voice yet is simply lost: the player
     * with the lower id offers again every few seconds until the link is up.
     */
    private fun startRetryLoop() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (true) {
                delay(4_000)
                val now = System.currentTimeMillis()
                for ((id, peer) in peers.entries.toList()) {
                    val up = peer.ice == PeerConnection.IceConnectionState.CONNECTED ||
                        peer.ice == PeerConnection.IceConnectionState.COMPLETED
                    if (!up && myId < id && now - peer.createdAt > 8_000) {
                        peers.remove(id)?.let { try { it.pc.close() } catch (_: Exception) {} }
                        createPeer(id)
                        makeOffer(id)
                    }
                }
                refreshStatus()
            }
        }
    }

    private class Peer(val pc: PeerConnection) {
        val createdAt = System.currentTimeMillis()
        @Volatile var ice: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        var remoteTrack: org.webrtc.AudioTrack? = null
        var remoteDescriptionSet = false
        val pendingCandidates = ArrayList<IceCandidate>()
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================

    fun start(myPlayerId: String, servers: List<PeerConnection.IceServer>? = null) {
        if (!servers.isNullOrEmpty()) iceServers = servers
        myId = myPlayerId
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // With a headset / Bluetooth speaker the voices must play there as normal "media" (stereo, good
        // quality), not through the phone's call routing; without one, the phone sits on the table.
        val headset = headsetConnected()
        val adm = JavaAudioDeviceModule.builder(appContext)
            // Software echo cancellation / noise suppression (same behaviour on every phone). The hardware
            // ones, stacked on top, are a known cause of muffled or "underwater" voices.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(
                        if (headset) android.media.AudioAttributes.USAGE_MEDIA
                        else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                    )
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setSamplesReadyCallback { samples -> reportLevel(samples) }
            .createAudioDeviceModule()

        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        adm.release()
        factory = f

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
        }
        audioSource = f.createAudioSource(constraints)
        localTrack = f.createAudioTrack("RUMMI_MIC", audioSource).also { it.setEnabled(micEnabled) }

        applyRoute()
        try { audioManager.registerAudioDeviceCallback(deviceCallback, main) } catch (_: Exception) {}
        startRetryLoop()
        refreshStatus()
    }

    /** True when a Bluetooth or wired headset / speaker is the current audio output. */
    private fun headsetConnected(): Boolean = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> false
            }
        }
    } catch (_: Exception) { false }

    /**
     * Headset connected: leave the routing alone (the system keeps sending sound to it).
     * Otherwise the phone is on the table: communication mode + loudspeaker.
     */
    private fun applyRoute() {
        try {
            if (headsetConnected()) {
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        } catch (_: Exception) {}
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) { applyRoute() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) { applyRoute() }
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        for ((_, peer) in peers) {
            try { peer.pc.close() } catch (_: Exception) {}
        }
        peers.clear()
        try { localTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        localTrack = null
        audioSource = null
        factory = null
        refreshStatus()
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}
    }

    // ==========================================
    // PEER MANAGEMENT
    // ==========================================

    /** Keeps one connection per other player in the room. */
    fun syncPeers(otherPlayerIds: Collection<String>) {
        if (factory == null || myId.isEmpty()) return
        val wanted = otherPlayerIds.filter { it != myId }.toSet()

        for (id in peers.keys.toList()) {
            if (id !in wanted) {
                peers.remove(id)?.let { try { it.pc.close() } catch (_: Exception) {} }
            }
        }
        for (id in wanted) {
            if (!peers.containsKey(id)) {
                createPeer(id)
                if (myId < id) makeOffer(id)
            }
        }
    }

    private fun createPeer(remoteId: String): Peer? {
        val f = factory ?: return null
        val track = localTrack ?: return null
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                send(remoteId, SignalType.ICE_CANDIDATE, candidate = encodeCandidate(candidate))
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val t = transceiver.receiver.track()
                if (t is org.webrtc.AudioTrack) {
                    peers[remoteId]?.remoteTrack = t
                    t.setEnabled(remoteId !in remoteMuted)
                    try { t.setVolume((remoteVolume * 10f).toDouble()) } catch (_: Exception) {}
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                peers[remoteId]?.ice = state
                refreshStatus()
                if (state == PeerConnection.IceConnectionState.FAILED && myId < remoteId) {
                    // Try again from scratch
                    peers.remove(remoteId)?.let { try { it.pc.close() } catch (_: Exception) {} }
                    createPeer(remoteId)
                    makeOffer(remoteId)
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }
        val pc = f.createPeerConnection(config, observer) ?: return null
        pc.addTrack(track, listOf("RUMMI_STREAM"))
        val peer = Peer(pc)
        peers[remoteId] = peer
        refreshStatus()
        return peer
    }

    private fun makeOffer(remoteId: String) {
        val peer = peers[remoteId] ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peer.pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = SessionDescription(desc.type, com.rummikub.core.audio.OpusSdpTuner.tune(desc.description))
                peer.pc.setLocalDescription(SimpleSdpObserver(), tuned)
                send(remoteId, SignalType.OFFER, sdp = tuned.description)
            }
        }, constraints)
    }

    // ==========================================
    // SIGNALLING
    // ==========================================

    fun handleSignal(packet: NetworkPacket.VoiceSignalPacket) {
        if (factory == null) return
        val from = packet.fromPlayerId
        if (from == myId) return
        if (packet.toPlayerId != null && packet.toPlayerId != myId) return

        when (packet.signalType) {
            SignalType.OFFER -> {
                val sdp = packet.sdp ?: return
                val existing = peers[from]
                if (existing != null && existing.remoteDescriptionSet) {
                    peers.remove(from)
                    try { existing.pc.close() } catch (_: Exception) {}
                }
                val peer = peers[from] ?: createPeer(from) ?: return
                peer.pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        peer.remoteDescriptionSet = true
                        drainCandidates(peer)
                        peer.pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                val tuned = SessionDescription(desc.type, com.rummikub.core.audio.OpusSdpTuner.tune(desc.description))
                                peer.pc.setLocalDescription(SimpleSdpObserver(), tuned)
                                send(from, SignalType.ANSWER, sdp = tuned.description)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }

            SignalType.ANSWER -> {
                val sdp = packet.sdp ?: return
                val peer = peers[from] ?: return
                peer.pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        peer.remoteDescriptionSet = true
                        drainCandidates(peer)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            SignalType.ICE_CANDIDATE -> {
                val candidate = decodeCandidate(packet.candidate ?: return) ?: return
                val peer = peers[from] ?: return
                if (peer.remoteDescriptionSet) {
                    peer.pc.addIceCandidate(candidate)
                } else {
                    synchronized(peer.pendingCandidates) { peer.pendingCandidates.add(candidate) }
                }
            }

            SignalType.VAD_SPEAKING_STATUS, SignalType.MUTE_STATUS -> {
                // Handled by the platform-independent voice state (WebRtcVoiceChat)
            }
        }
    }

    private fun drainCandidates(peer: Peer) {
        val pending = synchronized(peer.pendingCandidates) {
            val copy = ArrayList(peer.pendingCandidates)
            peer.pendingCandidates.clear()
            copy
        }
        for (c in pending) peer.pc.addIceCandidate(c)
    }

    private fun send(to: String, type: SignalType, sdp: String? = null, candidate: String? = null) {
        val packet = NetworkPacket.VoiceSignalPacket(
            fromPlayerId = myId,
            toPlayerId = to,
            signalType = type,
            sdp = sdp,
            candidate = candidate
        )
        scope.launch { sendSignal(packet) }
    }

    private fun encodeCandidate(c: IceCandidate): String = "${c.sdpMLineIndex}|${c.sdpMid ?: ""}|${c.sdp}"

    private fun decodeCandidate(s: String): IceCandidate? {
        val parts = s.split("|", limit = 3)
        if (parts.size != 3) return null
        val index = parts[0].toIntOrNull() ?: return null
        return IceCandidate(parts[1], index, parts[2])
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        localTrack?.setEnabled(enabled)
    }

    fun setRemoteMuted(playerId: String, muted: Boolean) {
        if (muted) remoteMuted.add(playerId) else remoteMuted.remove(playerId)
        peers[playerId]?.remoteTrack?.setEnabled(!muted)
    }

    fun setRemoteVolume(volume: Float) {
        remoteVolume = volume.coerceIn(0f, 1f)
        for ((_, peer) in peers) {
            try { peer.remoteTrack?.setVolume((remoteVolume * 10f).toDouble()) } catch (_: Exception) {}
        }
    }

    // ==========================================
    // LOCAL LEVEL (for the VAD halo)
    // ==========================================

    private fun reportLevel(samples: JavaAudioDeviceModule.AudioSamples) {
        val now = System.currentTimeMillis()
        if (now - lastLevelReportMs < 60L) return
        lastLevelReportMs = now
        if (!micEnabled) return

        val data = samples.data
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = data.size / 2
        if (count == 0) return
        var sum = 0.0
        for (i in 0 until count) {
            val s = buffer.getShort(i * 2).toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / count)
        val db = if (rms <= 1.0) -96f else (20.0 * log10(rms / 32768.0)).toFloat()
        main.post { onLocalAudioLevelDb(db) }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            System.err.println("[WebRtcVoiceEngine] createSdp failed: $error")
        }
        override fun onSetFailure(error: String?) {
            System.err.println("[WebRtcVoiceEngine] setSdp failed: $error")
        }
    }
}
