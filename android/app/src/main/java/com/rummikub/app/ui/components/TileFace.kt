package com.rummikub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.rummikub.app.ui.theme.TileColorBlack
import com.rummikub.app.ui.theme.TileColorBlue
import com.rummikub.app.ui.theme.TileColorRed
import com.rummikub.app.ui.theme.TileColorYellow
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Mise en valeur d'une tuile. */
enum class TileMark {
    NONE,

    /** Posée pendant ce tour : halo vert. */
    PLACED,

    /** Sélectionnée : halo doré. */
    SELECTED,

    /** Posée par un adversaire : halo orange. */
    OPPONENT_PLACED,

    /** Déplacée par un adversaire : halo violet. */
    OPPONENT_MOVED,
}

private val Ivory0 = Color(0xFFFFFEFA)
private val Ivory1 = Color(0xFFF3ECD8)
private val EdgeTop = Color(0xFFCFC4A6)
private val EdgeBottom = Color(0xFF9F9372)
private val Stroke0 = Color(0xFFDDD3B8)

private fun tileInk(color: TileColor?): Color = when (color) {
    TileColor.BLUE -> TileColorBlue
    TileColor.RED -> TileColorRed
    TileColor.YELLOW -> TileColorYellow
    else -> TileColorBlack
}

private fun markColor(mark: TileMark): Color? = when (mark) {
    TileMark.PLACED -> Color(0xFF69F0AE)
    TileMark.SELECTED -> Color(0xFFFFD54F)
    TileMark.OPPONENT_PLACED -> Color(0xFFFF9100)
    TileMark.OPPONENT_MOVED -> Color(0xFFD500F9)
    TileMark.NONE -> null
}

/**
 * Tuile dessinée en vectoriel : nette à toute densité d'écran. Tous les tracés sont proportionnels
 * à la taille donnée par le modificateur.
 */
@Composable
fun TileFace(
    tile: Tile,
    modifier: Modifier = Modifier,
    mark: TileMark = TileMark.NONE,
    colorMarks: Boolean = true,
    dimmed: Boolean = false,
    measurer: TextMeasurer = rememberTextMeasurer(cacheSize = 48),
) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val label = if (tile.isJoker) "" else tile.number.toString()
            val fontPx = w * if (label.length <= 1) 0.64f else 0.52f
            val layout: TextLayoutResult? = if (label.isEmpty()) null else measurer.measure(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = TextUnit(fontPx / density, TextUnitType.Sp),
                    color = tileInk(tile.color),
                ),
                constraints = Constraints(maxWidth = (w * 1.3f).toInt().coerceAtLeast(1)),
            )
            onDrawBehind { drawTile(tile, layout, mark, colorMarks, dimmed) }
        },
    )
}

private fun DrawScope.drawTile(tile: Tile, label: TextLayoutResult?, mark: TileMark, colorMarks: Boolean, dimmed: Boolean) {
    val w = size.width
    val h = size.height
    val r = w * 0.15f
    val thick = h * 0.055f
    val faceH = h - thick
    val halo = markColor(mark)

    if (halo != null) {
        val spread = w * if (mark == TileMark.SELECTED) 0.11f else 0.07f
        drawRoundRect(
            color = halo.copy(alpha = 0.50f),
            topLeft = Offset(-spread, -spread),
            size = Size(w + 2 * spread, h + 2 * spread),
            cornerRadius = CornerRadius(r + spread),
        )
    }
    // bord inférieur : épaisseur de la tuile
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(EdgeTop, EdgeBottom)),
        topLeft = Offset(0f, thick),
        size = Size(w, faceH),
        cornerRadius = CornerRadius(r),
    )
    // face
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Ivory0, Ivory1), startY = 0f, endY = faceH),
        size = Size(w, faceH),
        cornerRadius = CornerRadius(r),
    )
    drawRoundRect(
        color = Stroke0,
        size = Size(w, faceH),
        cornerRadius = CornerRadius(r),
        style = Stroke(width = (w * 0.018f).coerceAtLeast(1f)),
    )
    // reflet
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent), endY = faceH * 0.42f),
        topLeft = Offset(w * 0.06f, w * 0.05f),
        size = Size(w * 0.88f, faceH * 0.38f),
        cornerRadius = CornerRadius(r * 0.8f),
    )

    if (tile.isJoker) {
        drawJoker(w, faceH)
    } else {
        val ink = tileInk(tile.color)
        if (label != null) {
            val markSpace = if (colorMarks) faceH * 0.15f else 0f
            drawText(label, topLeft = Offset((w - label.size.width) / 2f, (faceH - markSpace - label.size.height) / 2f + faceH * 0.02f))
        }
        if (colorMarks) drawColorMark(tile.color, ink, w, faceH)
        // 6 et 9 : un trait les distingue
        if (tile.number == 6 || tile.number == 9) {
            val y = faceH * 0.66f
            drawLine(ink, Offset(w * 0.36f, y), Offset(w * 0.64f, y), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        }
    }

    if (halo != null) {
        drawRoundRect(
            color = halo.copy(alpha = 0.95f),
            size = Size(w, faceH),
            cornerRadius = CornerRadius(r),
            style = Stroke(width = (w * 0.045f).coerceAtLeast(1.5f)),
        )
    }
    if (dimmed) drawRoundRect(Color.Black.copy(alpha = 0.35f), size = Size(w, h), cornerRadius = CornerRadius(r))
}

/** Petit repère sous le chiffre : la couleur se lit aussi par la forme (daltonisme). */
private fun DrawScope.drawColorMark(color: TileColor?, ink: Color, w: Float, faceH: Float) {
    val s = w * 0.085f
    val cx = w / 2f
    val cy = faceH * 0.82f
    when (color) {
        TileColor.BLACK, null -> drawCircle(ink, radius = s, center = Offset(cx, cy))
        TileColor.BLUE -> drawRoundRect(ink, Offset(cx - s, cy - s), Size(2 * s, 2 * s), CornerRadius(s * 0.35f))
        TileColor.RED -> drawPath(
            Path().apply {
                moveTo(cx, cy - s * 1.1f)
                lineTo(cx + s * 1.15f, cy + s * 0.85f)
                lineTo(cx - s * 1.15f, cy + s * 0.85f)
                close()
            },
            ink, style = Fill,
        )
        TileColor.YELLOW -> drawPath(
            Path().apply {
                moveTo(cx, cy - s * 1.15f)
                lineTo(cx + s * 1.05f, cy)
                lineTo(cx, cy + s * 1.15f)
                lineTo(cx - s * 1.05f, cy)
                close()
            },
            ink, style = Fill,
        )
    }
}

/** Joker : visage souriant dans une étoile aux quatre couleurs. */
private fun DrawScope.drawJoker(w: Float, faceH: Float) {
    val cx = w / 2f
    val cy = faceH * 0.5f
    val outer = w * 0.41f
    val inner = w * 0.27f
    val points = 8
    val star = Path()
    for (i in 0 until points * 2) {
        val a = -PI / 2 + i * PI / points
        val rad = if (i % 2 == 0) outer else inner
        val x = cx + (cos(a) * rad).toFloat()
        val y = cy + (sin(a) * rad).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    drawPath(
        star,
        brush = Brush.sweepGradient(
            listOf(TileColorRed, TileColorYellow, Color(0xFF2EBD6B), TileColorBlue, TileColorBlack, TileColorRed),
            center = Offset(cx, cy),
        ),
    )
    drawCircle(Ivory0, radius = inner * 0.93f, center = Offset(cx, cy))
    val eye = w * 0.045f
    drawCircle(TileColorBlack, eye, Offset(cx - w * 0.085f, cy - w * 0.06f))
    drawCircle(TileColorBlack, eye, Offset(cx + w * 0.085f, cy - w * 0.06f))
    val smile = Path().apply {
        moveTo(cx - w * 0.11f, cy + w * 0.03f)
        quadraticTo(cx, cy + w * 0.17f, cx + w * 0.11f, cy + w * 0.03f)
    }
    drawPath(smile, TileColorRed, style = Stroke(width = w * 0.035f, cap = StrokeCap.Round))
}
