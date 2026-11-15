package com.rummikub.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
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
    val lift by animateFloatAsState(
        targetValue = if (lifted) -5f else 0f,
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

    // Selected: a golden halo that breathes gently
    val breathe = if (isSelected) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "sel").animateFloat(
            initialValue = 0.55f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse
            ), label = "selAlpha"
        ).value
    } else 1f
    val glow = if (isSelected) Color(0xFFFFC107) else Color.Black

    val borderColor = when {
        isSelected -> Color(0xFFFFD54F).copy(alpha = breathe)
        isNewThisTurn -> Color(0xFF4FC3F7)
        isPlacedByOpponent -> Color(0xFFFF6D00)   // orange: just laid down from the opponent's rack
        isMovedByOpponent -> Color(0xFFD500F9)    // violet: moved by the opponent
        else -> Color(0xFFC0B8A2)
    }
    val borderWidth = if (isSelected || isNewThisTurn || isPlacedByOpponent || isMovedByOpponent) 3.dp else 1.dp

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = lift * density
            }
            .shadow(
                if (isSelected) 16.dp else elevation.dp, RoundedCornerShape(7.dp),
                ambientColor = glow, spotColor = glow
            )
            .clip(RoundedCornerShape(7.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFFFFF), Color(0xFFF7F3E8), Color(0xFFE0D9C4))
                )
            )
            .border(borderWidth, borderColor, RoundedCornerShape(7.dp))
            // Soft bevel: light on the top edge, shade on the bottom edge
            .drawWithContent {
                drawContent()
                if (isSelected) {
                    // A golden tint over the whole tile: the selection is visible at a glance
                    drawRoundRect(Color(0x38FFC107), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                }
                val inset = 1.6.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0x00FFFFFF), Color(0x40000000))),
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
                )
            }
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width * 0.80f)
                .background(
                    Brush.linearGradient(listOf(Color(0xFFDDD6C0), Color(0xFFF4F0E3), Color(0xFFFFFFFF))),
                    androidx.compose.foundation.shape.CircleShape
                )
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color(0x33000000), Color(0x00000000), Color(0x66FFFFFF))),
                    androidx.compose.foundation.shape.CircleShape
                )
        )
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
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x40000000), androidx.compose.ui.geometry.Offset(0f, 2.5f), 4f)),
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
