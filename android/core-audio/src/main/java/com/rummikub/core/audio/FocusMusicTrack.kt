package com.rummikub.core.audio

/**
 * Musical genre for focus / concentration background audio.
 */
enum class FocusGenre(val displayName: String) {
    LO_FI("Café"),
    AMBIENT("Vagues"),
    WHITE_NOISE("Pluie"),
    SOFT_JAZZ("Jazz"),
    CUSTOM("Ma musique")
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
        /**
         * Free ambience loops from the Google sound library (checked reachable on 2026-11-15).
         */
        val DEFAULT_PLAYLIST = listOf(
            FocusMusicTrack(
                id = "cafe_01",
                title = "Ambiance café",
                genre = FocusGenre.LO_FI,
                uri = "https://actions.google.com/sounds/v1/ambiences/coffee_shop.ogg"
            ),
            FocusMusicTrack(
                id = "rain_01",
                title = "Pluie sur le toit",
                genre = FocusGenre.WHITE_NOISE,
                uri = "https://actions.google.com/sounds/v1/weather/rain_on_roof.ogg"
            ),
            FocusMusicTrack(
                id = "waves_01",
                title = "Vagues sur les rochers",
                genre = FocusGenre.AMBIENT,
                uri = "https://actions.google.com/sounds/v1/water/waves_crashing_on_rock_beach.ogg"
            )
        )
    }
}
