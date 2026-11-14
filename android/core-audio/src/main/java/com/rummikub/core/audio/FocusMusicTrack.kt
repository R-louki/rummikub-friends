package com.rummikub.core.audio

/**
 * Musical genre for focus / concentration background audio.
 */
enum class FocusGenre(val displayName: String) {
    LO_FI("Lo-Fi Chill Beats"),
    AMBIENT("Atmosphère Zen"),
    WHITE_NOISE("Bruit Blanc Apaisant"),
    SOFT_JAZZ("Jazz Doux & Café")
}

/**
 * Represents a playable concentration music track.
 *
 * @param id Unique identifier.
 * @param title Track display title.
 * @param genre Music category.
 * @param uri Resource URI (local asset or remote HTTPS stream).
 * @param durationMs Approximate duration or 0 for live infinite stream.
 */
data class FocusMusicTrack(
    val id: String,
    val title: String,
    val genre: FocusGenre,
    val uri: String,
    val durationMs: Long = 0L
) {
    companion object {
        val DEFAULT_PLAYLIST = listOf(
            FocusMusicTrack(
                id = "lofi_01",
                title = "Midnight Coffee Study",
                genre = FocusGenre.LO_FI,
                uri = "https://actions.google.com/sounds/v1/ambiences/coffee_shop.ogg"
            ),
            FocusMusicTrack(
                id = "ambient_01",
                title = "Deep Space Zen",
                genre = FocusGenre.AMBIENT,
                uri = "https://actions.google.com/sounds/v1/ambiences/rain_heavy.ogg"
            ),
            FocusMusicTrack(
                id = "white_noise_01",
                title = "Gentle Rainfall & Focus",
                genre = FocusGenre.WHITE_NOISE,
                uri = "https://actions.google.com/sounds/v1/weather/rain_on_roof.ogg"
            ),
            FocusMusicTrack(
                id = "jazz_01",
                title = "Acoustic Fireplace Lounge",
                genre = FocusGenre.SOFT_JAZZ,
                uri = "https://actions.google.com/sounds/v1/household/fireplace_blazing.ogg"
            )
        )
    }
}
