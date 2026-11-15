package com.rummikub.app.ui.lobby

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.account.AvatarCircle
import com.rummikub.app.ui.account.relativeSeen
import com.rummikub.app.ui.components.NetworkIndicator
import com.rummikub.app.ui.components.PlayerAvatarView
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.PlayerSummary
import com.rummikub.core.network.ConnectionStatus
import com.rummikub.core.network.Friend
import com.rummikub.core.network.FriendStatus

private val Gold = Color(0xFFFFD54F)

/**
 * Waiting room of a private table, for a landscape phone: the table (code, players, rules, start) on the
 * left and a big friends panel on the right, so inviting somebody takes one tap.
 */
@Composable
fun WaitingRoomScreen(
    roomCode: String,
    players: List<PlayerSummary>,
    isHost: Boolean,
    gameConfig: GameConfig,
    onStartGame: () -> Unit,
    onLeaveRoom: () -> Unit,
    connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
    latencyMs: Long = 0L,
    inviteFriends: List<Friend> = emptyList(),
    onlineFriendIds: Set<String> = emptySet(),
    lastSeen: Map<String, String> = emptyMap(),
    invitedIds: Set<String> = emptySet(),
    signedIn: Boolean = false,
    onInviteFriend: (Friend) -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== LEFT: the table ====================
            Card(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SALLE D'ATTENTE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = 1.sp)
                        NetworkIndicator(connectionStatus, latencyMs)
                    }

                    // Code of the table, to copy or share
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E0E08))
                                .border(1.5.dp, Gold, RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(roomCode, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Gold, letterSpacing = 3.sp)
                        }
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(roomCode)) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("📋 Copier", fontSize = 11.sp, color = Color.White) }
                        OutlinedButton(
                            onClick = {
                                val share = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Rejoins ma partie de Rummikub avec le code : $roomCode")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(share, "Inviter via"))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("📤 Partager", fontSize = 11.sp, color = Color.White) }
                    }

                    Divider(color = WoodBorder)

                    // Players: filled seats and free seats
                    Text("Joueurs ${players.size}/${gameConfig.maxPlayers}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        players.forEach { player ->
                            PlayerAvatarView(
                                player = player,
                                isActiveTurn = false,
                                isSpeaking = false,
                                isMutedByMe = false,
                                onToggleMute = {}
                            )
                        }
                        repeat((gameConfig.maxPlayers - players.size).coerceAtLeast(0)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp).padding(4.dp)) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, Color(0x55FFFFFF), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Text("+", fontSize = 18.sp, color = Color(0x88FFFFFF)) }
                                Text("libre", fontSize = 10.sp, color = Color(0x88FFFFFF))
                            }
                        }
                    }

                    // Rules in one line
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RuleChip("⏱️", if (gameConfig.turnDurationSeconds == 0) "Illimité" else "${gameConfig.turnDurationSeconds} s")
                        RuleChip("🎯", if (gameConfig.initialMeldThreshold == 0) "Pose libre" else "${gameConfig.initialMeldThreshold} pts")
                        RuleChip("🃏", if (gameConfig.jokerAllowedInInitialMeld) "Joker ok" else "Sans joker")
                        RuleChip("⚠️", if (gameConfig.penaltyTilesCount == 0) "Sans pénalité" else "${gameConfig.penaltyTilesCount} tuile${if (gameConfig.penaltyTilesCount > 1) "s" else ""}")
                    }

                    Spacer(Modifier.weight(1f))

                    // Actions
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onLeaveRoom,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Quitter", fontSize = 13.sp, color = Color(0xFFFFCC80)) }

                        if (isHost) {
                            val canStart = players.size >= 2
                            Button(
                                onClick = onStartGame,
                                enabled = canStart,
                                modifier = Modifier.weight(2f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E7D32),
                                    disabledContainerColor = Color(0xFF1B5E20).copy(alpha = 0.6f)
                                )
                            ) {
                                Text(
                                    text = if (canStart) "🚀  Démarrer la partie" else "En attente d'un 2ème joueur...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(2f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF26140B))
                                    .border(1.dp, Color(0xFF4E342E), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⏳ En attente de l'hôte...", fontSize = 13.sp, color = Gold, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ==================== RIGHT: friends ====================
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
            ) {
                val accepted = inviteFriends
                    .filter { it.status == FriendStatus.ACCEPTED }
                    .sortedByDescending { it.userId in onlineFriendIds }
                val onlineCount = accepted.count { it.userId in onlineFriendIds }

                Column(
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("👥 Inviter des amis", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gold)
                        if (accepted.isNotEmpty()) {
                            Text("$onlineCount en ligne", fontSize = 11.sp, color = Color(0xFF69F0AE))
                        }
                    }
                    Divider(color = WoodBorder)

                    when {
                        !signedIn -> EmptyFriendsHint("Connectez-vous avec Google pour inviter vos amis en un geste.\n\nEn attendant, partagez le code de la table.")
                        accepted.isEmpty() -> EmptyFriendsHint("Pas encore d'ami.\n\nAjoutez vos amis depuis l'accueil (bouton Amis) : ils pourront être invités ici sans code.")
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(accepted, key = { it.friendshipId }) { friend ->
                                val online = friend.userId in onlineFriendIds
                                val invited = friend.userId in invitedIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (online) Color(0x2200E676) else Color(0x14FFFFFF))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box {
                                        AvatarCircle(friend.avatar, friend.pseudo, 38.dp)
                                        Box(
                                            modifier = Modifier
                                                .size(11.dp)
                                                .align(Alignment.BottomEnd)
                                                .clip(CircleShape)
                                                .background(if (online) Color(0xFF00E676) else Color(0xFF616161))
                                                .border(1.5.dp, WoodDarkBrown, CircleShape)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(friend.pseudo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (online) "En ligne" else relativeSeen(lastSeen[friend.userId]),
                                            fontSize = 11.sp,
                                            color = if (online) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.55f)
                                        )
                                    }
                                    Button(
                                        onClick = { onInviteFriend(friend) },
                                        enabled = online && !invited,
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFE65100),
                                            disabledContainerColor = if (invited) Color(0xFF2E7D32) else Color(0xFF3E2723)
                                        )
                                    ) {
                                        Text(
                                            text = when {
                                                invited -> "Invité ✓"
                                                online -> "Inviter"
                                                else -> "Hors ligne"
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (online || invited) Color.White else Color(0x77FFFFFF)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleChip(icon: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(icon, fontSize = 12.sp)
        Text(label, fontSize = 11.sp, color = Color(0xFFD7CCC8))
    }
}

@Composable
private fun ColumnScope.EmptyFriendsHint(text: String) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
