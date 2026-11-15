package com.rummikub.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
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
    isPlacedByOpponent: Boolean = false,
    isMovedByOpponent: Boolean = false,
    isGhost: Boolean = false,
    width: Dp = 42.dp,
    height: Dp = 58.dp
) {
    val lifted = isDragging || isSelected

    val scale by animateFloatAsState(
        targetValue = if (lifted) 1.12f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "tile_scale"
    )
    // A selected tile also rises a little, like a tile picked up from the table
    val lift by animateFloatAsState(
        targetValue = if (lifted) -7f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "tile_lift"
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
        isPlacedByOpponent -> Color(0xFFFF6D00)   // orange: just laid down from the opponent's rack
        isMovedByOpponent -> Color(0xFFD500F9)    // violet: moved by the opponent
        else -> Color(0xFFC0B8A2)
    }
    val borderWidth = if (isSelected || isNewThisTurn || isPlacedByOpponent || isMovedByOpponent) 3.dp else 1.dp

    // The tile has a visible thickness: a darker edge under the face, and a shadow on the table
    val thickness = (height.value * 0.075f).dp.coerceAtLeast(3.dp)
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = lift * density
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(elevation.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFFD9CFB6), Color(0xFF8E8369))))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height - thickness)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(TileIvoryBevel, TileIvorySurface, TileIvoryShadow)
                    )
                )
                .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
                .padding(1.dp),
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
                    fontSize = (width.value * 0.48f).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "☺",
                    color = tileColor.copy(alpha = alpha),
                    fontSize = (width.value * 0.29f).sp,
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
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x55000000), androidx.compose.ui.geometry.Offset(1.5f, 2.5f), 3f)),
                    color = tileColor.copy(alpha = alpha),
                    fontSize = (width.value * (if (tile.number >= 10) 0.47f else 0.56f)).sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                // Small underline bar for 6 and 9 distinction
                if (tile.number == 6 || tile.number == 9) {
                    Box(
                        modifier = Modifier
                            .width(width * 0.32f)
                            .height(2.dp)
                            .background(tileColor.copy(alpha = alpha), RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
    }
}
