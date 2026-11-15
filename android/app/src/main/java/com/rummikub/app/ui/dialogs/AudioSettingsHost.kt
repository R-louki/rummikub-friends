package com.rummikub.app.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rummikub.app.audio.AndroidAudioBridge
import com.rummikub.core.model.GameConfig

/**
 * The audio settings dialog wired to the audio bridge (volumes, microphone, ambience and the
 * player's own music library). Usable from the lobby as well as from the game screen.
 */
@Composable
fun AudioSettingsHost(
    audioBridge: AndroidAudioBridge,
    gameConfig: GameConfig,
    onAddMusic: () -> Unit,
    onDismiss: () -> Unit
) {
    val audio = audioBridge.controller
    val musicVolume by audio.musicVolume.collectAsState()
    val voiceVolume by audio.voiceVolume.collectAsState()
    val sfxVolume by audio.sfxVolume.collectAsState()
    val voiceMode by audio.voiceChat.mode.collectAsState()
    val isMicMuted by audio.voiceChat.isMicMuted.collectAsState()
    val currentTrack by audio.musicPlayer.currentTrack.collectAsState()
    val isMusicPlaying by audio.musicPlayer.isPlaying.collectAsState()
    val userTracks by audioBridge.library.tracks.collectAsState()

    GameSettingsDialog(
        musicVolume = musicVolume,
        voiceVolume = voiceVolume,
        sfxVolume = sfxVolume,
        voiceMode = voiceMode,
        isMicMuted = isMicMuted,
        isMusicPlaying = isMusicPlaying,
        currentTrack = currentTrack,
        userTracks = userTracks,
        gameConfig = gameConfig,
        onMusicVolumeChanged = { audio.setMusicVolume(it) },
        onVoiceVolumeChanged = { audio.setVoiceVolume(it) },
        onSfxVolumeChanged = { audio.setSfxVolume(it) },
        onVoiceModeChanged = { audio.voiceChat.setMode(it) },
        onToggleMic = { audio.toggleMicrophoneMute() },
        onTrackSelected = { audio.playTrack(it) },
        onStopMusic = { audio.stopMusic() },
        onAddMusic = onAddMusic,
        onRemoveTrack = { audioBridge.removeTrack(it.id) },
        onNextTrack = { audioBridge.nextTrack() },
        onDismiss = onDismiss
    )
}
