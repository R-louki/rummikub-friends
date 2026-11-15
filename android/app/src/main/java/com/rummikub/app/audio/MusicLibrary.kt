package com.rummikub.app.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.rummikub.core.audio.FocusGenre
import com.rummikub.core.audio.FocusMusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The player's own background music: audio files picked on the phone (any format Android plays).
 * Nothing is uploaded or hosted by the app, only the file references are remembered.
 */
class MusicLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("music_library", Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<FocusMusicTrack>>(load())
    val tracks: StateFlow<List<FocusMusicTrack>> = _tracks.asStateFlow()

    /** Adds the picked files (the system picker has already granted read access). */
    fun add(uris: List<Uri>) {
        val current = _tracks.value.toMutableList()
        for (uri in uris) {
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Some providers do not support persistable grants; the file still plays this session.
            }
            val text = uri.toString()
            if (current.any { it.uri == text }) continue
            current += FocusMusicTrack(
                id = "local_" + text.hashCode().toUInt().toString(16),
                title = displayName(uri),
                genre = FocusGenre.CUSTOM,
                uri = text
            )
        }
        _tracks.value = current
        save(current)
    }

    fun remove(trackId: String) {
        val current = _tracks.value.filterNot { it.id == trackId }
        _tracks.value = current
        save(current)
    }

    private fun displayName(uri: Uri): String {
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Morceau"
    }

    private fun save(list: List<FocusMusicTrack>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("id", t.id).put("title", t.title).put("uri", t.uri))
        prefs.edit().putString("tracks", arr.toString()).apply()
    }

    private fun load(): List<FocusMusicTrack> {
        val raw = prefs.getString("tracks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FocusMusicTrack(o.getString("id"), o.getString("title"), FocusGenre.CUSTOM, o.getString("uri"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
