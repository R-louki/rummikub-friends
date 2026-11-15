package com.rummikub.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.core.network.ConnectionStatus

/** How good the connection is, from the measured round-trip time. */
enum class NetworkQuality(val bars: Int, val color: Color) {
    EXCELLENT(4, Color(0xFF00E676)),
    GOOD(3, Color(0xFF9CCC65)),
    FAIR(2, Color(0xFFFFB300)),
    POOR(1, Color(0xFFFF5252)),
    OFFLINE(0, Color(0xFFFF5252));

    companion object {
        fun fromLatency(ms: Long): NetworkQuality = when {
            ms <= 0L -> GOOD // not measured yet
            ms <= 90L -> EXCELLENT
            ms <= 170L -> GOOD
            ms <= 320L -> FAIR
            else -> POOR
        }
    }
}

/**
 * Signal-strength style indicator of the connection to the game server: four bars and the ping.
 * It replaces the error notifications: a weak or lost connection is shown here, calmly, and the
 * app reconnects by itself.
 */
@Composable
fun NetworkIndicator(
    status: ConnectionStatus,
    latencyMs: Long,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val busy = status is ConnectionStatus.Connecting || status is ConnectionStatus.Reconnecting
    val quality = if (status is ConnectionStatus.Connected) NetworkQuality.fromLatency(latencyMs) else NetworkQuality.OFFLINE

    val pulse by rememberInfiniteTransition(label = "network_pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "network_pulse_alpha"
    )

    val color = if (busy) Color(0xFFFFB300) else quality.color
    val filled = if (busy) 2 else quality.bars

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.alpha(if (busy) pulse else 1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in 1..4) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((4 + i * 4).dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (i <= filled) color else Color(0x33FFFFFF))
                )
            }
        }
        if (showLabel) {
            Text(
                text = when {
                    status is ConnectionStatus.Reconnecting -> "Reconnexion"
                    status is ConnectionStatus.Connecting -> "Connexion"
                    status is ConnectionStatus.Connected -> if (latencyMs > 0) "$latencyMs ms" else "En ligne"
                    else -> "Hors ligne"
                },
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}
