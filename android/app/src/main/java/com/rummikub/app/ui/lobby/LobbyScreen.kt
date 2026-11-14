package com.rummikub.app.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.GameConfig
import com.rummikub.core.network.ConnectionStatus

/**
 * Main Welcome / Lobby Screen in Landscape.
 * While the first connection is in progress, a blocking overlay explains what is happening
 * (the free-tier server may need up to a minute to wake up) and offers to cancel.
 */
@Composable
fun LobbyScreen(
    connectionStatus: ConnectionStatus,
    onCreateRoom: (hostName: String, config: GameConfig) -> Unit,
    onJoinRoom: (roomCode: String, playerName: String) -> Unit,
    onCancelConnecting: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val isConnecting = connectionStatus is ConnectionStatus.Connecting

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF134226), FeltDarkGreen, Color(0xFF06140B))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            // Game Brand Logo & Subtitle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "RUMMIKUB",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFFD54F),
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "FRIENDS & LIVE VOICE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    letterSpacing = 3.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Parties privées multijoueur • Zéro Rupture Réseau • Focus Music",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            // Action Buttons Card
            Card(
                modifier = Modifier
                    .width(420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(WoodDarkBrown)
                    .border(2.dp, WoodBorder, RoundedCornerShape(18.dp))
                    .padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                    ) {
                        Text(
                            text = "👑 Créer une Table Privée",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { showJoinDialog = true },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text(
                            text = "🚪 Rejoindre avec un Code",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Dialogs
        if (showCreateDialog) {
            CreateRoomDialog(
                onDismiss = { showCreateDialog = false },
                onCreateRoom = { hostName, config ->
                    showCreateDialog = false
                    onCreateRoom(hostName, config)
                }
            )
        }

        if (showJoinDialog) {
            JoinRoomDialog(
                onDismiss = { showJoinDialog = false },
                onJoinRoom = { code, name ->
                    showJoinDialog = false
                    onJoinRoom(code, name)
                }
            )
        }

        // Connecting overlay
        if (connectionStatus is ConnectionStatus.Connecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* swallow taps */ },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(380.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFFFD54F), strokeWidth = 3.dp)
                        Text(
                            text = "Connexion en cours",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = connectionStatus.message,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                        if (connectionStatus.attempt > 1) {
                            Text(
                                text = "Tentative ${connectionStatus.attempt}",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(onClick = onCancelConnecting) {
                            Text("Annuler", color = Color(0xFFFFCC80))
                        }
                    }
                }
            }
        }
    }
}
