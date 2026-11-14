package com.rummikub.app.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rummikub.core.audio.FocusMusicTrack

/**
 * Background focus music through Media3 ExoPlayer (streams the track URI, loops forever).
 * All calls are marshalled to the main thread, as ExoPlayer requires.
 */
class MusicEngine(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var currentTrackId: String? = null
    private var volume = 0.5f

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(appContext).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_GAME)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false
        )
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                System.err.println("[MusicEngine] Lecture impossible (${currentTrackId}): ${error.errorCodeName}")
            }
        })
        player = p
        return p
    }

    fun play(track: FocusMusicTrack) {
        main.post {
            val p = ensurePlayer()
            if (currentTrackId != track.id) {
                currentTrackId = track.id
                p.setMediaItem(MediaItem.fromUri(track.uri))
                p.prepare()
            }
            p.playWhenReady = true
        }
    }

    fun pause() {
        main.post { player?.playWhenReady = false }
    }

    fun resume() {
        main.post { player?.playWhenReady = true }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        main.post { player?.volume = volume }
    }

    fun release() {
        main.post {
            player?.release()
            player = null
            currentTrackId = null
        }
    }
}
