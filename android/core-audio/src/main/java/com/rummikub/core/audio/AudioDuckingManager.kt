package com.rummikub.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Intelligent Audio Ducking Manager.
 *
 * Automatically reduces the Focus Music gain by 40% (to 60% of base volume)
 * with a smooth 200ms fade-out whenever any player starts speaking.
 * When speaking ceases, waits for a 300ms hangover to prevent audio pumping,
 * then smoothly restores full volume over 500ms.
 */
class AudioDuckingManager(
    private val musicPlayer: IFocusMusicPlayer,
    private val voiceChat: IWebRtcVoiceChat,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val duckingAttenuationPercent: Float = 0.40f, // -40%
    private val fadeOutDurationMs: Long = 200L,
    private val fadeInDurationMs: Long = 500L,
    private val releaseHangoverMs: Long = 300L
) {
    private val _isDucked = MutableStateFlow(false)
    val isDucked: StateFlow<Boolean> = _isDucked.asStateFlow()

    private var releaseJob: Job? = null
    private var observerJob: Job? = null

    init {
        start()
    }

    fun start() {
        observerJob?.cancel()
        observerJob = scope.launch {
            voiceChat.speakingPlayers.collect { speakers ->
                handleSpeakersChanged(speakers)
            }
        }
    }

    private fun handleSpeakersChanged(speakers: Set<String>) {
        if (speakers.isNotEmpty()) {
            // Someone is speaking -> Cancel any pending volume recovery
            releaseJob?.cancel()
            releaseJob = null

            if (!_isDucked.value) {
                _isDucked.value = true
                val baseVolume = musicPlayer.userVolume.value
                val duckedTarget = baseVolume * (1.0f - duckingAttenuationPercent)
                musicPlayer.fadeVolumeTo(duckedTarget, fadeOutDurationMs)
            }
        } else {
            // No one is speaking -> Wait for hangover, then fade back up
            if (_isDucked.value && releaseJob?.isActive != true) {
                releaseJob = scope.launch {
                    delay(releaseHangoverMs)
                    _isDucked.value = false
                    val baseVolume = musicPlayer.userVolume.value
                    musicPlayer.fadeVolumeTo(baseVolume, fadeInDurationMs)
                }
            }
        }
    }

    fun stop() {
        observerJob?.cancel()
        releaseJob?.cancel()
        observerJob = null
        releaseJob = null
        if (_isDucked.value) {
            _isDucked.value = false
            musicPlayer.fadeVolumeTo(musicPlayer.userVolume.value, fadeInDurationMs)
        }
    }
}
