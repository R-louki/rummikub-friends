package com.rummikub.app.audio

import android.content.Context
import com.rummikub.core.audio.AudioController
import com.rummikub.core.audio.FocusGenre
import com.rummikub.core.audio.VoiceChatMode
import com.rummikub.core.network.GameNetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection

/**
 * Connects the platform-independent [AudioController] to real Android audio:
 * procedural sound effects, ExoPlayer background music and WebRTC live voice.
 */
class AndroidAudioBridge(
    context: Context,
    private val repository: GameNetworkRepository,
    private val scope: CoroutineScope
) {
    private val sfx = SfxSynth()
    val library = MusicLibrary(context)
    private val music = MusicEngine(context) { id ->
        // The player moved on to the next file of the playlist by itself: reflect it in the UI state.
        library.tracks.value.firstOrNull { it.id == id }?.let { controller.musicPlayer.playTrack(it) }
    }

    val controller: AudioController = AudioController(
        myPlayerId = "",
        sendSignalPacket = { signal -> repository.sendVoiceSignal(signal) },
        onPlaySound = { effect, volume -> sfx.play(effect, volume) },
        onMusicVolumeChanged = { volume -> music.setVolume(volume) }
    )

    private val voice = WebRtcVoiceEngine(
        context = context,
        scope = scope,
        sendSignal = { signal -> repository.sendVoiceSignal(signal) },
        onLocalAudioLevelDb = { db -> controller.voiceChat.updateLocalVadAudioEnergy(db) }
    )

    @Volatile private var micPermissionGranted = false
    @Volatile private var voiceStarting = false
    private var lastMuted: Set<String> = emptySet()

    val isVoiceRunning: Boolean get() = voice.isRunning

    init {
        val voiceChat = controller.voiceChat
        val musicPlayer = controller.musicPlayer

        // Music: follow the controller's track / play state / effective (ducked) volume
        scope.launch {
            combine(musicPlayer.currentTrack, musicPlayer.isPlaying) { track, playing -> track to playing }
                .collect { (track, playing) ->
                    when {
                        track == null || !playing -> music.pause()
                        track.genre == FocusGenre.CUSTOM -> {
                            val list = library.tracks.value
                            if (list.any { it.id == track.id }) music.playPlaylist(list, track.id)
                        }
                        else -> music.play(track)
                    }
                }
        }
        scope.launch { musicPlayer.effectiveVolume.collect { music.setVolume(it) } }

        // Voice: start when we have a server-assigned id and the mic permission
        scope.launch {
            repository.myPlayerId.collect { id ->
                if (id != null) {
                    controller.setMyPlayerId(id)
                    maybeStartVoice()
                } else {
                    voice.stop()
                }
            }
        }
        scope.launch {
            combine(repository.players, repository.myPlayerId) { players, me ->
                players.filter { !it.isBot }.map { it.id }.filter { it != me }
            }.collect { others ->
                if (voice.isRunning) voice.syncPeers(others)
            }
        }
        scope.launch {
            repository.voiceSignals.collect { signal ->
                voice.handleSignal(signal)
                voiceChat.handleRemoteVoiceSignal(signal)
            }
        }
        scope.launch {
            combine(voiceChat.mode, voiceChat.isMicMuted, voiceChat.isPushToTalkPressed) { mode, muted, pressed ->
                !muted && (mode == VoiceChatMode.OPEN_MIC || pressed)
            }.collect { enabled -> voice.setMicEnabled(enabled) }
        }
        scope.launch {
            voiceChat.mutedPlayers.collect { muted ->
                for (id in muted - lastMuted) voice.setRemoteMuted(id, true)
                for (id in lastMuted - muted) voice.setRemoteMuted(id, false)
                lastMuted = muted
            }
        }
        scope.launch { voiceChat.voiceVolume.collect { voice.setRemoteVolume(it) } }
    }

    /** Skips to the next file of the player's own playlist (no effect on built-in ambience). */
    fun nextTrack() {
        music.next()
    }

    /** Removes a track from the library; stops the music if it was the one playing. */
    fun removeTrack(trackId: String) {
        if (controller.musicPlayer.currentTrack.value?.id == trackId) controller.stopMusic()
        library.remove(trackId)
    }

    fun onMicPermissionResult(granted: Boolean) {
        micPermissionGranted = granted
        if (granted) maybeStartVoice()
    }

    private fun maybeStartVoice() {
        if (!micPermissionGranted) return
        if (repository.gameConfig.value.botCount > 0) return
        val id = repository.myPlayerId.value ?: return
        if (voice.isRunning || voiceStarting) return
        voiceStarting = true
        scope.launch {
            try {
                // STUN + optional TURN relay configured on the game server
                val servers = repository.fetchIceServers().map { cfg ->
                    val builder = PeerConnection.IceServer.builder(cfg.urls)
                    if (cfg.username != null && cfg.credential != null) {
                        builder.setUsername(cfg.username).setPassword(cfg.credential)
                    }
                    builder.createIceServer()
                }
                voice.start(id, servers)
                voice.syncPeers(repository.players.value.filter { !it.isBot }.map { it.id })
            } catch (e: Exception) {
                System.err.println("[AndroidAudioBridge] Voice start failed: ${e.message}")
            } finally {
                voiceStarting = false
            }
        }
    }

    fun release() {
        voice.stop()
        music.release()
        controller.release()
    }
}
