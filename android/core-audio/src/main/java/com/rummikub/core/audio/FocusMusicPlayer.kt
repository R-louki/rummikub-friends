package com.rummikub.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Interface for the background focus music player.
 */
interface IFocusMusicPlayer {
    val currentTrack: StateFlow<FocusMusicTrack?>
    val isPlaying: StateFlow<Boolean>
    val userVolume: StateFlow<Float>
    val effectiveVolume: StateFlow<Float>

    fun playTrack(track: FocusMusicTrack)
    fun pause()
    fun resume()
    fun setUserVolume(volume: Float)
    fun fadeVolumeTo(targetVolume: Float, durationMs: Long)
    fun release()
}

/**
 * Controller for Focus Music with seamless loop and volume fade interpolation.
 * Supports both standalone testing and Media3 ExoPlayer delegation.
 */
class FocusMusicPlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val onVolumeChanged: (Float) -> Unit = {}
) : IFocusMusicPlayer {

    private val _currentTrack = MutableStateFlow<FocusMusicTrack?>(null)
    override val currentTrack: StateFlow<FocusMusicTrack?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _userVolume = MutableStateFlow(0.70f) // Default 70%
    override val userVolume: StateFlow<Float> = _userVolume.asStateFlow()

    private val _effectiveVolume = MutableStateFlow(0.70f)
    override val effectiveVolume: StateFlow<Float> = _effectiveVolume.asStateFlow()

    private var fadeJob: Job? = null

    override fun playTrack(track: FocusMusicTrack) {
        _currentTrack.value = track
        _isPlaying.value = true
        _effectiveVolume.value = _userVolume.value
        onVolumeChanged(_effectiveVolume.value)
    }

    override fun pause() {
        _isPlaying.value = false
    }

    override fun resume() {
        if (_currentTrack.value != null) {
            _isPlaying.value = true
        }
    }

    override fun setUserVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _userVolume.value = clamped
        // If not currently ducked, effective volume follows user volume directly
        if (fadeJob?.isActive != true) {
            _effectiveVolume.value = clamped
            onVolumeChanged(clamped)
        }
    }

    /**
     * Smoothly interpolates the effective volume to [targetVolume] over [durationMs].
     * Uses 20ms steps to eliminate any audio artifact or zipper noise.
     */
    override fun fadeVolumeTo(targetVolume: Float, durationMs: Long) {
        fadeJob?.cancel()

        val clampedTarget = targetVolume.coerceIn(0f, 1f)
        val startVolume = _effectiveVolume.value

        if (durationMs <= 0) {
            _effectiveVolume.value = clampedTarget
            onVolumeChanged(clampedTarget)
            return
        }

        val stepIntervalMs = 20L
        val totalSteps = (durationMs / stepIntervalMs).coerceAtLeast(1)
        val volumeDeltaPerStep = (clampedTarget - startVolume) / totalSteps

        fadeJob = scope.launch {
            var current = startVolume
            for (step in 1..totalSteps) {
                delay(stepIntervalMs)
                current += volumeDeltaPerStep
                _effectiveVolume.value = current.coerceIn(0f, 1f)
                onVolumeChanged(_effectiveVolume.value)
            }
            _effectiveVolume.value = clampedTarget
            onVolumeChanged(clampedTarget)
        }
    }

    override fun release() {
        fadeJob?.cancel()
        _isPlaying.value = false
    }
}
