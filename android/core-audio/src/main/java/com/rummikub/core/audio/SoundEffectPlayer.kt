package com.rummikub.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GameSoundEffect {
    TILE_GRAB,
    TILE_SNAP_GRID,
    TILE_SORT,
    TURN_COMMITTED_SUCCESS,
    TURN_ROLLBACK_PENALTY,
    COUNTDOWN_TICK
}

interface ISoundEffectPlayer {
    val sfxVolume: StateFlow<Float>
    fun setSfxVolume(volume: Float)
    fun playSound(effect: GameSoundEffect)
}

/**
 * Controller for Game Audio Effects (tile clicks, grid snap, alerts).
 */
class SoundEffectPlayer(
    private val onPlaySoundCallback: (GameSoundEffect, Float) -> Unit = { _, _ -> }
) : ISoundEffectPlayer {

    private val _sfxVolume = MutableStateFlow(0.80f) // 80% default
    override val sfxVolume: StateFlow<Float> = _sfxVolume.asStateFlow()

    override fun setSfxVolume(volume: Float) {
        _sfxVolume.value = volume.coerceIn(0f, 1f)
    }

    override fun playSound(effect: GameSoundEffect) {
        val volume = _sfxVolume.value
        if (volume > 0f) {
            onPlaySoundCallback(effect, volume)
        }
    }
}
