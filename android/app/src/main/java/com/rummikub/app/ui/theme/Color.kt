package com.rummikub.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Table & Felt Colors
class FeltPalette(val name: String, val light: Color, val medium: Color, val dark: Color, val deepest: Color)

/** Table colour themes, chosen by the player (🎨 Thème). The table, lobby and menus follow it. */
object ThemeState {
    val palettes = listOf(
        FeltPalette("Bleu nuit", Color(0xFF2F6FD6), Color(0xFF1A4A9E), Color(0xFF0E2A66), Color(0xFF071638)),
        FeltPalette("Émeraude", Color(0xFF1E7A4C), Color(0xFF14573A), Color(0xFF08301D), Color(0xFF041A10)),
        FeltPalette("Bordeaux", Color(0xFFA33A4C), Color(0xFF6E1F33), Color(0xFF3E0F1D), Color(0xFF22070F)),
        FeltPalette("Violet", Color(0xFF6A4FB8), Color(0xFF45308A), Color(0xFF261A55), Color(0xFF130C30)),
        FeltPalette("Ardoise", Color(0xFF4C6272), Color(0xFF33444F), Color(0xFF1E2A33), Color(0xFF10171D))
    )
    var index by mutableIntStateOf(0)
        private set
    val current: FeltPalette get() = palettes[index.coerceIn(0, palettes.lastIndex)]
    fun select(i: Int) { index = i.coerceIn(0, palettes.lastIndex) }
}

val FeltDarkGreen: Color get() = ThemeState.current.dark
val FeltMediumGreen: Color get() = ThemeState.current.medium
val FeltLight: Color get() = ThemeState.current.light
val GoldAccent = Color(0xFFFFD54F)
val WoodDarkBrown = Color(0xFF26140B)
val WoodBorder = Color(0xFF3E2316)

// Tile Surface & Physicality
val TileIvorySurface = Color(0xFFFAF7EE)
val TileIvoryBevel = Color(0xFFFFFFFF)
val TileIvoryShadow = Color(0xFFD6CDB8)
val TileGhostBorder = Color(0x66FFFFFF)

// Official Rummikub Face Colors (Couleurs pures, franches, sans aucun contour)
val TileColorBlack = Color(0xFF18181B)
val TileColorBlue = Color(0xFF1E57F0)
val TileColorRed = Color(0xFFE02828)          // Rouge Vif Écarlate
val TileColorYellow = Color(0xFFE08A00)       // Jaune d'Or / Safran franc (sans contour)
val TileJokerStar = Color(0xFFFF8F00)

// Voice VAD & Network Status Indicators
val VadSpeakingHalo = Color(0xFF00E5FF)
val StatusConnected = Color(0xFF00E676)
val StatusReconnecting = Color(0xFFFFAB00)
val StatusDisconnected = Color(0xFFFF1744)
val TimerUrgentRed = Color(0xFFFF1744)
