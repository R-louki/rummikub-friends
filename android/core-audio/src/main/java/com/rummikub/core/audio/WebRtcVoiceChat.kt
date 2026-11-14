package com.rummikub.core.audio

import com.rummikub.core.model.NetworkPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Microphone transmission mode.
 */
enum class VoiceChatMode {
    OPEN_MIC,      // Voice Activity Detection (VAD) automatically opens mic
    PUSH_TO_TALK   // Mic transmits only while the talk button is held
}

/**
 * Interface for WebRTC Live Voice Chat.
 */
interface IWebRtcVoiceChat {
    val mode: StateFlow<VoiceChatMode>
    val isMicMuted: StateFlow<Boolean>
    val isPushToTalkPressed: StateFlow<Boolean>
    val voiceVolume: StateFlow<Float>
    val mutedPlayers: StateFlow<Set<String>>
    val speakingPlayers: StateFlow<Set<String>>
    val isLocalSpeaking: StateFlow<Boolean>

    fun setMode(mode: VoiceChatMode)
    fun setMicrophoneMuted(muted: Boolean)
    fun setPushToTalkPressed(pressed: Boolean)
    fun togglePlayerMute(playerId: String)
    fun setVoiceVolume(volume: Float)
    fun handleRemoteVoiceSignal(signal: NetworkPacket.VoiceSignalPacket)
    fun updateLocalVadAudioEnergy(rmsDb: Float)
}

/**
 * Production implementation of the Live Voice Chat controller.
 * Handles VAD (Voice Activity Detection), PTT, individual friend mutes,
 * and speaking state synchronization.
 */
class WebRtcVoiceChat(
    var myPlayerId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val sendSignalPacket: suspend (NetworkPacket.VoiceSignalPacket) -> Unit = {}
) : IWebRtcVoiceChat {

    private val _mode = MutableStateFlow(VoiceChatMode.OPEN_MIC)
    override val mode: StateFlow<VoiceChatMode> = _mode.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    override val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isPushToTalkPressed = MutableStateFlow(false)
    override val isPushToTalkPressed: StateFlow<Boolean> = _isPushToTalkPressed.asStateFlow()

    private val _voiceVolume = MutableStateFlow(1.0f) // 100% volume default
    override val voiceVolume: StateFlow<Float> = _voiceVolume.asStateFlow()

    private val _mutedPlayers = MutableStateFlow<Set<String>>(emptySet())
    override val mutedPlayers: StateFlow<Set<String>> = _mutedPlayers.asStateFlow()

    private val _speakingPlayers = MutableStateFlow<Set<String>>(emptySet())
    override val speakingPlayers: StateFlow<Set<String>> = _speakingPlayers.asStateFlow()

    private val _isLocalSpeaking = MutableStateFlow(false)
    override val isLocalSpeaking: StateFlow<Boolean> = _isLocalSpeaking.asStateFlow()

    // VAD Configuration: Threshold in dB (-45 dB typical for speech), Hangover time (400ms)
    private val vadThresholdDb = -45f
    private val vadHangoverMs = 400L
    private var lastSpeakingTimeMs = 0L
    private var vadHangoverJob: Job? = null

    override fun setMode(mode: VoiceChatMode) {
        _mode.value = mode
        if (mode == VoiceChatMode.PUSH_TO_TALK && !_isPushToTalkPressed.value) {
            updateSpeakingState(false)
        }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        _isMicMuted.value = muted
        if (muted) {
            updateSpeakingState(false)
        }
    }

    override fun setPushToTalkPressed(pressed: Boolean) {
        _isPushToTalkPressed.value = pressed
        if (_mode.value == VoiceChatMode.PUSH_TO_TALK) {
            val shouldTransmit = pressed && !_isMicMuted.value
            updateSpeakingState(shouldTransmit)
        }
    }

    override fun togglePlayerMute(playerId: String) {
        val current = _mutedPlayers.value.toMutableSet()
        if (current.contains(playerId)) {
            current.remove(playerId)
        } else {
            current.add(playerId)
            // If we muted this player, remove them from active speaking list immediately
            _speakingPlayers.value = _speakingPlayers.value - playerId
        }
        _mutedPlayers.value = current
    }

    override fun setVoiceVolume(volume: Float) {
        _voiceVolume.value = volume.coerceIn(0f, 1f)
    }

    /**
     * Updates local speaking state based on microphone energy (dB) during OPEN_MIC mode.
     */
    override fun updateLocalVadAudioEnergy(rmsDb: Float) {
        if (_isMicMuted.value) return
        if (_mode.value != VoiceChatMode.OPEN_MIC) return

        val now = System.currentTimeMillis()
        if (rmsDb >= vadThresholdDb) {
            lastSpeakingTimeMs = now
            vadHangoverJob?.cancel()
            updateSpeakingState(true)
        } else {
            if (_isLocalSpeaking.value && vadHangoverJob?.isActive != true) {
                vadHangoverJob = scope.launch {
                    delay(vadHangoverMs)
                    updateSpeakingState(false)
                }
            }
        }
    }

    /**
     * Handles incoming WebRTC signaling packets (SDP, ICE, VAD status).
     */
    override fun handleRemoteVoiceSignal(signal: NetworkPacket.VoiceSignalPacket) {
        val senderId = signal.fromPlayerId
        // Ignore signals from muted players
        if (_mutedPlayers.value.contains(senderId)) return

        when (signal.signalType) {
            NetworkPacket.VoiceSignalPacket.SignalType.VAD_SPEAKING_STATUS -> {
                val current = _speakingPlayers.value.toMutableSet()
                if (signal.isSpeaking) {
                    current.add(senderId)
                } else {
                    current.remove(senderId)
                }
                _speakingPlayers.value = current
            }
            NetworkPacket.VoiceSignalPacket.SignalType.MUTE_STATUS -> {
                if (signal.isMuted) {
                    _speakingPlayers.value = _speakingPlayers.value - senderId
                }
            }
            else -> {
                // SDP Offer / Answer / ICE Candidate handled by WebRTC native peer connection
            }
        }
    }

    private fun updateSpeakingState(isSpeaking: Boolean) {
        if (_isLocalSpeaking.value == isSpeaking) return
        _isLocalSpeaking.value = isSpeaking

        val current = _speakingPlayers.value.toMutableSet()
        if (isSpeaking) {
            current.add(myPlayerId)
        } else {
            current.remove(myPlayerId)
        }
        _speakingPlayers.value = current

        // Broadcast VAD speaking status to all players in the room
        scope.launch {
            try {
                sendSignalPacket(
                    NetworkPacket.VoiceSignalPacket(
                        fromPlayerId = myPlayerId,
                        signalType = NetworkPacket.VoiceSignalPacket.SignalType.VAD_SPEAKING_STATUS,
                        isSpeaking = isSpeaking
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
