package com.rummikub.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.theme.*
import com.rummikub.core.model.PlayerSummary

/**
 * Avatar for a player with real-time VAD voice glow, active turn ring, and mute toggle.
 */
@Composable
fun PlayerAvatarView(
    player: PlayerSummary,
    isActiveTurn: Boolean,
    isSpeaking: Boolean,
    isMutedByMe: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation for the VAD glowing halo when speaking
    val infiniteTransition = rememberInfiniteTransition(label = "vad_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val haloBorderColor by animateColorAsState(
        targetValue = when {
            isSpeaking -> VadSpeakingHalo
            isActiveTurn -> Color(0xFFFFD54F) // Gold when active turn
            else -> Color(0xFF4E342E)
        },
        label = "border_color"
    )

    Column(
        modifier = modifier
            .width(84.dp)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .then(if (isSpeaking) Modifier.scale(pulseScale) else Modifier)
                .clickable { onToggleMute() }
        ) {
            // Speaking Halo background glow
            if (isSpeaking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(VadSpeakingHalo.copy(alpha = 0.35f))
                )
            }

            // Avatar circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF5D4037), Color(0xFF3E2723))
                        )
                    )
                    .border(
                        width = if (isSpeaking || isActiveTurn) 2.5.dp else 1.dp,
                        color = haloBorderColor,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                com.rummikub.app.ui.account.AvatarContent(player.avatar, player.name, 14.sp)
            }

            // Mute overlay badge
            if (isMutedByMe) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(StatusDisconnected)
                        .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }

            // Host Crown Badge
            if (player.isHost) {
                Text(
                    text = "👑",
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Player Name
        Text(
            text = player.name,
            color = if (isActiveTurn) Color(0xFFFFD54F) else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isActiveTurn) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Tile Count Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1B0E09))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = "${player.tileCount} tuiles",
                color = if (player.isConnected) Color(0xFFD7CCC8) else StatusReconnecting,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
