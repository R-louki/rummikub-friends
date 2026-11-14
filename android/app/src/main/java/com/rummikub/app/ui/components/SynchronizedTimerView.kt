package com.rummikub.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.theme.StatusReconnecting
import com.rummikub.app.ui.theme.TimerUrgentRed

/**
 * Synchronized turn timer with urgent countdown alert and grace period banner.
 */
@Composable
fun SynchronizedTimerView(
    remainingMillis: Long,
    totalDurationSeconds: Int,
    isGracePeriod: Boolean,
    gracePeriodCountdownSeconds: Int?,
    modifier: Modifier = Modifier
) {
    if (isGracePeriod) {
        val seconds = gracePeriodCountdownSeconds ?: 30
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(StatusReconnecting.copy(alpha = 0.90f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚡ Reconnexion joueur... ${seconds}s",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    if (totalDurationSeconds <= 0) {
        // Unlimited time mode
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF26140B))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("∞ Illimité", color = Color(0xFFFFD54F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    val totalMillis = totalDurationSeconds * 1000f
    val progress = (remainingMillis / totalMillis).coerceIn(0f, 1f)
    val remainingSeconds = (remainingMillis / 1000).toInt()
    val isUrgent = remainingSeconds in 1..10

    val infiniteTransition = rememberInfiniteTransition(label = "urgent_pulse")
    val urgentScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "urgent_scale"
    )

    val timerColor by animateColorAsState(
        targetValue = if (isUrgent) TimerUrgentRed else Color(0xFF81C784),
        label = "timer_color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .then(if (isUrgent) Modifier.scale(urgentScale) else Modifier)
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            color = timerColor,
            trackColor = Color(0x33FFFFFF),
            strokeWidth = 3.5.dp
        )
        Text(
            text = remainingSeconds.toString(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
