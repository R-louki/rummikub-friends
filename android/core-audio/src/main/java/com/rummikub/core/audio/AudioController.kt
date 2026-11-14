package com.rummikub.core.audio

import com.rummikub.core.model.NetworkPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Central orchestrator for the entire Rummikub audio engine (platform independent).
 *
 * Coordinates:
 * - Live Voice Chat state (VAD, push-to-talk, mutes) — the actual WebRTC transport is
 *   provided by the Android layer through [sendSignalPacket] and the voice signal flow.
 * - Focus background music volume/fades — actual playback through [onMusicVolumeChanged].
 * - Tile sound effects — actual playback through [onPlaySound].
 * - Intelligent Auto-Ducking (-40% music volume during speech)
 */
class AudioController(
    myPlayerId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    sendSignalPacket: suspend (NetworkPacket.VoiceSignalPacket) -> Unit = {},
    onPlaySound: (GameSoundEffect, Float) -> Unit = { _, _ -> },
    onMusicVolumeChanged: (Float) -> Unit = {}
) {
    val musicPlayer: FocusMusicPlayer = FocusMusicPlayer(scope = scope, onVolumeChanged = onMusicVolumeChanged)
    val voiceChat: WebRtcVoiceChat = WebRtcVoiceChat(
        myPlayerId = myPlayerId,
        scope = scope,
        sendSignalPacket = sendSignalPacket
    )
    val sfxPlayer: SoundEffectPlayer = SoundEffectPlayer(onPlaySoundCallback = onPlaySound)

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

    /** The server assigns the player id after joining a room: keep the voice layer in sync. */
    fun setMyPlayerId(playerId: String) {
        voiceChat.myPlayerId = playerId
    }

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

    fun stopMusic() {
        musicPlayer.pause()
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

    fun onCountdownTick() {
        sfxPlayer.playSound(GameSoundEffect.COUNTDOWN_TICK)
    }

    fun release() {
        duckingManager.stop()
        musicPlayer.release()
    }
}
