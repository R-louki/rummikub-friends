package com.rummikub.app.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lets the player frame a picked photo: drag to move, pinch to zoom, the circle shows what other
 * players will see. [onConfirm] receives the visible square, in bitmap pixels.
 */
@Composable
fun AvatarCropDialog(
    bitmap: Bitmap,
    onConfirm: (left: Int, top: Int, side: Int) -> Unit,
    onCancel: () -> Unit
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val viewport = 200.dp
    val viewportPx = with(LocalDensity.current) { viewport.toPx() }
    val baseScale = viewportPx / min(bitmap.width, bitmap.height)

    var zoom by remember { mutableStateOf(1f) }
    // Top-left corner of the image in viewport pixels
    var offset by remember {
        mutableStateOf(
            Offset((viewportPx - bitmap.width * baseScale) / 2f, (viewportPx - bitmap.height * baseScale) / 2f)
        )
    }

    fun clamp(o: Offset, z: Float): Offset {
        val s = baseScale * z
        val minX = viewportPx - bitmap.width * s
        val minY = viewportPx - bitmap.height * s
        return Offset(o.x.coerceIn(min(minX, 0f), 0f), o.y.coerceIn(min(minY, 0f), 0f))
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF2A150C)) {
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(
                    modifier = Modifier
                        .size(viewport)
                        .clipToBounds()
                        .pointerInput(bitmap) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                // Keep the point under the fingers fixed while zooming
                                val ratio = newZoom / zoom
                                val moved = Offset(
                                    centroid.x - (centroid.x - offset.x) * ratio + pan.x,
                                    centroid.y - (centroid.y - offset.y) * ratio + pan.y
                                )
                                zoom = newZoom
                                offset = clamp(moved, newZoom)
                            }
                        }
                ) {
                    val s = baseScale * zoom
                    drawImage(
                        image,
                        dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                        dstSize = IntSize((bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt())
                    )
                    // Darken everything outside the circle
                    val circle = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                    }
                    drawPath(circle, Color(0xAA000000))
                    drawCircle(Color.White, radius = size.minDimension / 2f, style = Stroke(2f))
                }
                Column(
                    modifier = Modifier.width(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                Text("Recadrer la photo", color = Color(0xFFFFD54F), fontSize = 18.sp)
                Text("Glissez pour déplacer, pincez ou utilisez le curseur pour zoomer", color = Color(0xB3FFFFFF), fontSize = 12.sp)
                Slider(
                    value = zoom,
                    onValueChange = { z ->
                        // zoom around the centre of the frame
                        val c = Offset(viewportPx / 2f, viewportPx / 2f)
                        val ratio = z / zoom
                        offset = clamp(Offset(c.x - (c.x - offset.x) * ratio, c.y - (c.y - offset.y) * ratio), z)
                        zoom = z
                    },
                    valueRange = 1f..6f,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Annuler") }
                    Button(onClick = {
                        val s = baseScale * zoom
                        val side = max(1, (viewportPx / s).roundToInt())
                        val left = (-offset.x / s).roundToInt().coerceIn(0, max(0, bitmap.width - side))
                        val top = (-offset.y / s).roundToInt().coerceIn(0, max(0, bitmap.height - side))
                        onConfirm(left, top, min(side, min(bitmap.width, bitmap.height)))
                    }) { Text("Valider") }
                }
                }
            }
        }
    }
}
