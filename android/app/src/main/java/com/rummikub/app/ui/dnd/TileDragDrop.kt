package com.rummikub.app.ui.dnd

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent

private const val TILE_CLIP_LABEL = "rummikub_tile"

/** How easily a rack tile starts to move under the finger: 0 = low, 1 = normal, 2 = high. */
object DragSettings {
    var level by mutableStateOf(1)
    val slopFactor: Float get() = when (level) { 0 -> 2.2f; 2 -> 0.45f; else -> 1f }
}

/**
 * Extracts the tile id carried by a drag-and-drop event started with [tileDragSource].
 */
fun DragAndDropEvent.tileId(): String? {
    val clip = toAndroidDragEvent().clipData ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
}

/**
 * Makes a tile draggable (long press to lift) and tappable (tap to select).
 * Both gestures share one detector so they never fight over pointer events.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tileDragSource(
    tileId: String,
    enabled: Boolean,
    immediateDrag: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLong by rememberUpdatedState(onLongPress)
    if (!enabled) return this
    if (immediateDrag) {
        // Rack tiles: the tile follows the finger as soon as it moves (no long press needed)
        return this.dragAndDropSource {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragged = false
                var held = false
                val deadline = System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
                while (true) {
                    // Holding still: select the tiles that follow this one (5 held in 5-6-7 selects 5, 6 and 7)
                    val event = if (held || currentOnLong == null) {
                        awaitPointerEvent()
                    } else {
                        withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() }
                    }
                    if (event == null) {
                        held = true
                        currentOnLong?.invoke()
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!dragged && !held) currentOnTap()
                        break
                    }
                    val moved = change.position - down.position
                    if (!dragged && moved.getDistance() > viewConfiguration.touchSlop * DragSettings.slopFactor) {
                        dragged = true
                        startTransfer(
                            DragAndDropTransferData(clipData = ClipData.newPlainText(TILE_CLIP_LABEL, tileId))
                        )
                        break
                    }
                }
            }
        }
    }
    return this.dragAndDropSource {
        detectTapGestures(
            onTap = { currentOnTap() },
            onLongPress = {
                startTransfer(
                    DragAndDropTransferData(
                        clipData = ClipData.newPlainText(TILE_CLIP_LABEL, tileId)
                    )
                )
            }
        )
    }
}

/**
 * A zone that accepts a dragged tile (drop) or a tap (to place the selected tile).
 * [content] receives whether a tile is currently hovering over the zone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DropZone(
    enabled: Boolean,
    onDropTile: (tileId: String) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(hovered: Boolean) -> Unit
) {
    var hovered by remember { mutableStateOf(false) }
    val currentOnDrop by rememberUpdatedState(onDropTile)
    val currentOnTap by rememberUpdatedState(onTap)

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovered = false
                val id = event.tileId() ?: return false
                currentOnDrop(id)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) {
                hovered = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hovered = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovered = false
            }
        }
    }

    val zoneModifier = if (enabled) {
        modifier
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                },
                target = target
            )
            .clickable { currentOnTap() }
    } else {
        modifier
    }

    Box(modifier = zoneModifier) {
        content(hovered && enabled)
    }
}
