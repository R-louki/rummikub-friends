package com.rummikub.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.rummikub.core.audio.GameSoundEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny procedural sound-effect synthesizer: no audio assets needed.
 * Each effect is a short PCM buffer generated once and played through a static AudioTrack.
 */
class SfxSynth(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val sampleRate = 44_100
    private val buffers: Map<GameSoundEffect, ShortArray> by lazy { GameSoundEffect.values().associateWith { synthesize(it) } }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    fun play(effect: GameSoundEffect, volume: Float) {
        val pcm = buffers[effect] ?: return
        val gain = volume.coerceIn(0f, 1f)
        if (gain <= 0f) return
        scope.launch {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setVolume(gain)
                track.play()
                delay((pcm.size * 1000L / sampleRate) + 60L)
                track.stop()
                track.release()
            } catch (_: Exception) {
                // Audio hardware busy or unavailable: ignore, sound is cosmetic.
            }
        }
    }

    // ==========================================
    // SYNTHESIS
    // ==========================================

    private fun synthesize(effect: GameSoundEffect): ShortArray = when (effect) {
        GameSoundEffect.TILE_GRAB -> noiseClick(durationMs = 18, decay = 220f, amplitude = 0.35f)
        GameSoundEffect.TILE_SNAP_GRID -> mix(
            tone(freq = 1900f, durationMs = 28, decay = 160f, amplitude = 0.45f),
            noiseClick(durationMs = 12, decay = 300f, amplitude = 0.25f)
        )
        GameSoundEffect.TILE_SORT -> noiseSweep(durationMs = 90, amplitude = 0.22f)
        GameSoundEffect.TURN_COMMITTED_SUCCESS -> concat(
            tone(freq = 659f, durationMs = 90, decay = 18f, amplitude = 0.35f),
            tone(freq = 880f, durationMs = 160, decay = 12f, amplitude = 0.35f)
        )
        GameSoundEffect.TURN_ROLLBACK_PENALTY -> sweep(fromHz = 420f, toHz = 180f, durationMs = 260, amplitude = 0.4f)
        GameSoundEffect.COUNTDOWN_TICK -> tone(freq = 1050f, durationMs = 35, decay = 120f, amplitude = 0.3f)
    }

    private fun samples(ms: Int): Int = sampleRate * ms / 1000

    /** Decaying sine. [decay] is the exponential decay rate per second. */
    private fun tone(freq: Float, durationMs: Int, decay: Float, amplitude: Float): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            val env = exp(-decay * t) * fadeOut(i, n)
            out[i] = toPcm(sin(2.0 * PI * freq * t).toFloat() * env * amplitude)
        }
        return out
    }

    private fun noiseClick(durationMs: Int, decay: Float, amplitude: Float): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        var last = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            // light low-pass for a woody, not hissy, click
            last = last * 0.6f + (Random.nextFloat() * 2f - 1f) * 0.4f
            out[i] = toPcm(last * exp(-decay * t) * amplitude)
        }
        return out
    }

    private fun noiseSweep(durationMs: Int, amplitude: Float): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        var last = 0f
        for (i in 0 until n) {
            val p = i.toFloat() / n
            val lp = 0.2f + 0.7f * p // filter opens up over time
            last = last * (1f - lp) + (Random.nextFloat() * 2f - 1f) * lp
            val env = sin(PI * p).toFloat()
            out[i] = toPcm(last * env * amplitude)
        }
        return out
    }

    private fun sweep(fromHz: Float, toHz: Float, durationMs: Int, amplitude: Float): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toFloat() / n
            val f = fromHz + (toHz - fromHz) * p
            phase += 2.0 * PI * f / sampleRate
            val tri = (2.0 / PI) * kotlin.math.asin(sin(phase)) // softer than a square
            out[i] = toPcm(tri.toFloat() * fadeOut(i, n) * amplitude)
        }
        return out
    }

    private fun fadeOut(i: Int, n: Int): Float {
        val tail = n / 6
        return if (i > n - tail) (n - i).toFloat() / tail else 1f
    }

    private fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val n = maxOf(a.size, b.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val s = (if (i < a.size) a[i].toInt() else 0) + (if (i < b.size) b[i].toInt() else 0)
            out[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun concat(a: ShortArray, b: ShortArray): ShortArray = a + b

    private fun toPcm(v: Float): Short = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
}
