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
import com.rummikub.app.ui.theme.FeltMediumGreen
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
    onOpenTheme: () -> Unit = {},
    onCreateRoom: (hostName: String, config: GameConfig) -> Unit,
    onJoinRoom: (roomCode: String, playerName: String) -> Unit,
    onCancelConnecting: () -> Unit,
    latencyMs: Long = 0L,
    onRetryConnection: () -> Unit = {},
    onOpenAudio: () -> Unit = {},
    startingMessage: String? = null,
    accountPseudo: String? = null,
    accountAvatar: String? = null,
    onEditAvatar: () -> Unit = {},
    pendingFriendRequests: Int = 0,
    onOpenFriends: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val isConnecting = connectionStatus is ConnectionStatus.Connecting

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(FeltMediumGreen, FeltDarkGreen, com.rummikub.app.ui.theme.ThemeState.current.deepest)
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

        // Account bar: who is signed in, friends, history
        if (accountPseudo != null) {
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.rummikub.app.ui.account.AvatarCircle(
                    avatar = accountAvatar,
                    name = accountPseudo,
                    size = 34.dp,
                    modifier = Modifier.clickable(onClick = onEditAvatar)
                )
                Text(accountPseudo, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp))
                TextButton(onClick = onOpenFriends, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (pendingFriendRequests > 0) "👥 Amis ($pendingFriendRequests)" else "👥 Amis", color = Color(0xFFFFD54F), fontSize = 12.sp)
                }
                TextButton(onClick = onOpenHistory, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("📜 Historique", color = Color(0xFFFFD54F), fontSize = 12.sp)
                }
                TextButton(onClick = onSignOut, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Déconnexion", color = Color(0xFFFFCC80), fontSize = 11.sp)
                }
            }
        }

        // Top right: network quality and audio settings
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (connectionStatus !is ConnectionStatus.Disconnected || accountPseudo != null) {
                com.rummikub.app.ui.components.NetworkIndicator(connectionStatus, latencyMs)
            }
            TextButton(onClick = onOpenTheme) {
                Text("🎨 Thème", color = Color(0xFFFFD54F), fontSize = 12.sp)
            }
            TextButton(onClick = onOpenAudio) {
                Text("🎵 Musique & audio", color = Color(0xFFFFD54F), fontSize = 12.sp)
            }
        }

        // Server unreachable: a calm banner instead of a pop-up
        if (connectionStatus is ConnectionStatus.Failed) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3E2723))
                    .border(1.dp, Color(0xFFFF8A65), RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Serveur injoignable pour le moment", color = Color.White, fontSize = 12.sp)
                TextButton(onClick = onRetryConnection) { Text("Réessayer", color = Color(0xFFFFD54F), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // Dialogs
        if (showCreateDialog) {
            CreateRoomDialog(
                askName = accountPseudo == null,
                onDismiss = { showCreateDialog = false },
                onCreateRoom = { hostName, config ->
                    showCreateDialog = false
                    onCreateRoom(hostName, config)
                }
            )
        }

        if (showJoinDialog) {
            JoinRoomDialog(
                askName = accountPseudo == null,
                onDismiss = { showJoinDialog = false },
                onJoinRoom = { code, name ->
                    showJoinDialog = false
                    onJoinRoom(code, name)
                }
            )
        }

        // "Preparing the game" overlay (computer table: no waiting room)
        if (startingMessage != null && connectionStatus !is ConnectionStatus.Connecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color(0xFFFFD54F), strokeWidth = 3.dp)
                    Text(startingMessage, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
                }
            }
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
