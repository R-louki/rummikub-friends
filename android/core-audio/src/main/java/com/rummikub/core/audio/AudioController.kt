package com.rummikub.core.audio

import com.rummikub.core.model.NetworkPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Central orchestrator for the entire Rummikub Android audio engine.
 *
 * Coordinates:
 * - WebRTC Live Voice Chat (UDP)
 * - Media3 ExoPlayer Focus Background Music (Gapless Loop)
 * - Tile Sound Effects (Snapping, Clicks, Buzzers)
 * - Intelligent Auto-Ducking (-40% music volume during speech)
 */
class AudioController(
    myPlayerId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    sendSignalPacket: suspend (NetworkPacket.VoiceSignalPacket) -> Unit = {}
) {
    val musicPlayer: FocusMusicPlayer = FocusMusicPlayer(scope = scope)
    val voiceChat: WebRtcVoiceChat = WebRtcVoiceChat(
        myPlayerId = myPlayerId,
        scope = scope,
        sendSignalPacket = sendSignalPacket
    )
    val sfxPlayer: SoundEffectPlayer = SoundEffectPlayer()

    val duckingManager: AudioDuckingManager = AudioDuckingManager(
        musicPlayer = musicPlayer,
        voiceChat = voiceChat,
        scope = scope
    )

    // Exposed volume state flows for the UI sliders
    val musicVolume: StateFlow<Float> = musicPlayer.userVolume
    val voiceVolume: StateFlow<Float> = voiceChat.voiceVolume
    val sfxVolume: StateFlow<Float> = sfxPlayer.sfxVolume
    val isDucked: StateFlow<Boolean> = duckingManager.isDucked
    val speakingPlayers: StateFlow<Set<String>> = voiceChat.speakingPlayers

    fun setMusicVolume(volume: Float) {
        musicPlayer.setUserVolume(volume)
    }

    fun setVoiceVolume(volume: Float) {
        voiceChat.setVoiceVolume(volume)
    }

    fun setSfxVolume(volume: Float) {
        sfxPlayer.setSfxVolume(volume)
    }

    fun playTrack(track: FocusMusicTrack) {
        musicPlayer.playTrack(track)
    }

    fun playNextDefaultTrack() {
        val current = musicPlayer.currentTrack.value
        val list = FocusMusicTrack.DEFAULT_PLAYLIST
        val nextIndex = if (current == null) 0 else (list.indexOf(current) + 1) % list.size
        musicPlayer.playTrack(list[nextIndex])
    }

    fun toggleMicrophoneMute() {
        voiceChat.setMicrophoneMuted(!voiceChat.isMicMuted.value)
    }

    fun togglePlayerMute(playerId: String) {
        voiceChat.togglePlayerMute(playerId)
    }

    fun onTileGrabbed() {
        sfxPlayer.playSound(GameSoundEffect.TILE_GRAB)
    }

    fun onTileSnapped() {
        sfxPlayer.playSound(GameSoundEffect.TILE_SNAP_GRID)
    }

    fun onTurnSuccess() {
        sfxPlayer.playSound(GameSoundEffect.TURN_COMMITTED_SUCCESS)
    }

    fun onTurnRollback() {
        sfxPlayer.playSound(GameSoundEffect.TURN_ROLLBACK_PENALTY)
    }

    fun release() {
        duckingManager.stop()
        musicPlayer.release()
    }
}
