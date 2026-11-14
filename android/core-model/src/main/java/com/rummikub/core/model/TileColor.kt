package com.rummikub.core.model

import kotlinx.serialization.Serializable

/**
 * The four official colors in Rummikub.
 */
@Serializable
enum class TileColor(val displayName: String, val hexColor: Long) {
    BLACK("Noir", 0xFF18181B),
    BLUE("Bleu", 0xFF1D4ED8),
    RED("Rouge", 0xFFDC2626),
    YELLOW("Jaune", 0xFFCA8A04); // Jaune d'Or pur, sans contour, franc et contrasté

    companion object {
        fun fromString(value: String): TileColor? =
            values().find { it.name.equals(value, ignoreCase = true) }
    }
}
