package com.rummikub.app.audio

import android.content.Context
import android.net.Uri
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
 * Background music through Media3 ExoPlayer.
 * - [play]: one streamed ambience track, looped.
 * - [playPlaylist]: the player's own files, played one after the other and looping over the list.
 * All calls are marshalled to the main thread, as ExoPlayer requires.
 */
class MusicEngine(
    context: Context,
    /** Called on the main thread when the player moves on to another track by itself. */
    private val onTrackChanged: (trackId: String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null

    @Volatile var currentTrackId: String? = null
        private set
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
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                if (id.isNotEmpty() && id != currentTrackId) {
                    currentTrackId = id
                    onTrackChanged(id)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                System.err.println("[MusicEngine] Lecture impossible ($currentTrackId): ${error.errorCodeName}")
                // A broken file must not stop a whole playlist: skip to the next one if there is one.
                val p2 = player ?: return
                if (p2.mediaItemCount > 1) {
                    p2.seekToNextMediaItem()
                    p2.prepare()
                }
            }
        })
        player = p
        return p
    }

    private fun item(track: FocusMusicTrack): MediaItem =
        MediaItem.Builder().setMediaId(track.id).setUri(Uri.parse(track.uri)).build()

    fun play(track: FocusMusicTrack) {
        main.post {
            val p = ensurePlayer()
            if (currentTrackId != track.id || p.mediaItemCount != 1) {
                currentTrackId = track.id
                p.repeatMode = Player.REPEAT_MODE_ONE
                p.setMediaItem(item(track))
                p.prepare()
            }
            p.playWhenReady = true
        }
    }

    /** Plays [tracks] in order starting at [startId], looping over the whole list. */
    fun playPlaylist(tracks: List<FocusMusicTrack>, startId: String) {
        if (tracks.isEmpty()) return
        main.post {
            val p = ensurePlayer()
            val start = tracks.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            val sameList = p.mediaItemCount == tracks.size &&
                (0 until p.mediaItemCount).all { p.getMediaItemAt(it).mediaId == tracks[it].id }
            if (!sameList) {
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.setMediaItems(tracks.map { item(it) }, start, 0L)
                p.prepare()
            } else if (p.currentMediaItemIndex != start) {
                p.seekToDefaultPosition(start)
            }
            currentTrackId = tracks[start].id
            p.playWhenReady = true
        }
    }

    fun next() {
        main.post { player?.let { if (it.mediaItemCount > 1) it.seekToNextMediaItem() } }
    }

    fun pause() {
        main.post { player?.playWhenReady = false }
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
