package com.rummikub.app.ui.lobby

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.components.PlayerAvatarView
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.PlayerSummary

/**
 * Waiting Room Screen displayed after creating or joining a private room.
 */
@Composable
fun WaitingRoomScreen(
    roomCode: String,
    players: List<PlayerSummary>,
    isHost: Boolean,
    gameConfig: GameConfig,
    onStartGame: () -> Unit,
    onLeaveRoom: () -> Unit,
    inviteFriends: List<com.rummikub.core.network.Friend> = emptyList(),
    onlineFriendIds: Set<String> = emptySet(),
    onInviteFriend: (com.rummikub.core.network.Friend) -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(620.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(WoodDarkBrown)
                .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(20.dp))
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with Room Code
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SALLE D'ATTENTE PRIVÉE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD54F)
                        )
                        Text(
                            text = "Partagez le code avec vos amis",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    // Room Code Pill + Copy & Share
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E0E08))
                                .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = roomCode,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFFD54F),
                                letterSpacing = 2.sp
                            )
                        }

                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(roomCode))
                                val shareIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Rejoins ma partie de Rummikub privée entre amis avec le code : $roomCode")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Inviter via"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("📤 Inviter", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                Divider(color = WoodBorder)

                // Connected Players List (2 to 4)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Joueurs connectés (${players.size}/${gameConfig.maxPlayers}) :",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        players.forEach { player ->
                            PlayerAvatarView(
                                player = player,
                                isActiveTurn = false,
                                isSpeaking = false,
                                isMutedByMe = false,
                                onToggleMute = {}
                            )
                        }
                    }
                }

                Divider(color = WoodBorder)

                // Invite friends (signed-in players): no more codes to dictate
                if (inviteFriends.any { it.status == com.rummikub.core.network.FriendStatus.ACCEPTED }) {
                    com.rummikub.app.ui.account.FriendInvitePanel(
                        friends = inviteFriends,
                        onlineIds = onlineFriendIds,
                        onInvite = onInviteFriend
                    )
                    Divider(color = WoodBorder)
                }

                // Rules summary badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("⏱️ Tour : ${if (gameConfig.turnDurationSeconds == 0) "Illimité" else "${gameConfig.turnDurationSeconds}s"}", fontSize = 11.sp, color = Color(0xFFD7CCC8))
                    Text("🎯 1ère pose : ${gameConfig.initialMeldThreshold} pts", fontSize = 11.sp, color = Color(0xFFD7CCC8))
                    Text("🃏 Joker : ${if (gameConfig.jokerAllowedInInitialMeld) "Autorisé" else "Interdit"}", fontSize = 11.sp, color = Color(0xFFD7CCC8))
                }

                // Actions: Start game (Host only) or Leave
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onLeaveRoom,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723))
                    ) {
                        Text("Quitter le salon", fontSize = 12.sp, color = Color(0xFFFFCC80))
                    }

                    if (isHost) {
                        val canStart = players.size >= 2
                        Button(
                            onClick = onStartGame,
                            enabled = canStart,
                            modifier = Modifier.weight(1.5f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (canStart) Color(0xFF2E7D32) else Color(0xFF1B5E20)
                            )
                        ) {
                            Text(
                                text = if (canStart) "🚀 Démarrer la Partie" else "En attente d'un 2ème joueur...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1.5f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF26140B))
                                .border(1.dp, Color(0xFF4E342E), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⏳ En attente de l'hôte pour lancer...",
                                fontSize = 12.sp,
                                color = Color(0xFFFFD54F),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
