package com.rummikub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small in-game message: compact, centred above the rack, disappears by itself (tap to dismiss). */
@Composable
fun ToastBanner(message: String, onDismiss: () -> Unit) {
    // Gone after 2 s by itself so it never stays on top of the tiles
    androidx.compose.runtime.LaunchedEffect(message) {
        kotlinx.coroutines.delay(2000)
        onDismiss()
    }
    val isProblem = listOf("impossible", "invalide", "annulé", "inconnue", "non conforme", "ne peut", "erreur")
        .any { message.contains(it, ignoreCase = true) }
    val accent = if (isProblem) Color(0xFFFF8A65) else Color(0xFFFFD54F)
    Row(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .background(Color(0xB32A150C), RoundedCornerShape(24.dp))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (isProblem) "⚠️" else "ℹ️", fontSize = 14.sp)
        Text(message, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Start, maxLines = 3)
    }
}
