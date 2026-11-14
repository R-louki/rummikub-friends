package com.rummikub.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.theme.*
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor

/**
 * Renders a single tactile Rummikub tile.
 *
 * @param tile The tile model to render.
 * @param isDragging True if currently lifted by a drag gesture.
 * @param isSelected True if the tile is selected (tap mode): golden border, slightly raised.
 * @param isNewThisTurn True if the tile was placed on the table during the current turn.
 * @param isGhost True if acting as a target drop slot placeholder.
 * @param width Physical width (default 42.dp for landscape).
 * @param height Physical height (default 58.dp).
 */
@Composable
fun TileView(
    tile: Tile,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    isSelected: Boolean = false,
    isNewThisTurn: Boolean = false,
    isGhost: Boolean = false,
    width: Dp = 42.dp,
    height: Dp = 58.dp
) {
    val lifted = isDragging || isSelected

    val scale by animateFloatAsState(
        targetValue = if (lifted) 1.10f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "tile_scale"
    )

    val elevation by animateFloatAsState(
        targetValue = if (lifted) 12f else 3f,
        animationSpec = tween(durationMillis = 120),
        label = "tile_elevation"
    )

    val tileColor = when {
        tile.isJoker -> TileJokerStar
        tile.color == TileColor.BLACK -> TileColorBlack
        tile.color == TileColor.BLUE -> TileColorBlue
        tile.color == TileColor.RED -> TileColorRed
        tile.color == TileColor.YELLOW -> TileColorYellow
        else -> TileColorBlack
    }

    val alpha = if (isGhost) 0.40f else 1.0f

    val borderColor = when {
        isSelected -> Color(0xFFFFD54F)
        isNewThisTurn -> Color(0xFF4FC3F7)
        else -> Color(0xFFC0B8A2)
    }
    val borderWidth = if (isSelected || isNewThisTurn) 2.dp else 1.dp

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .scale(scale)
            .shadow(elevation.dp, shape = RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(TileIvoryBevel, TileIvorySurface, TileIvoryShadow)
                )
            )
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (tile.isJoker) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "★",
                    color = tileColor.copy(alpha = alpha),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "☺",
                    color = tileColor.copy(alpha = alpha),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tile.number.toString(),
                    color = tileColor.copy(alpha = alpha),
                    fontSize = if (tile.number >= 10) 18.sp else 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                // Small underline bar for 6 and 9 distinction
                if (tile.number == 6 || tile.number == 9) {
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.dp)
                            .background(tileColor.copy(alpha = alpha), RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
}
