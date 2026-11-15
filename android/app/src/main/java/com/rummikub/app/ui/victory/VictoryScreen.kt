package com.rummikub.app.ui.victory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown

/**
 * Player score summary for the end-of-game screen.
 */
data class PlayerEndScore(
    val playerId: String,
    val playerName: String,
    val isWinner: Boolean,
    val remainingTileCount: Int,
    val scoreDelta: Int,
    val totalScore: Int = 0,
    val hadJokerPenalty: Boolean = false
)

/**
 * End-of-Game Screen displaying official Rummikub scoring:
 * - Winner receives the positive sum of all loser penalties.
 * - Losers receive negative scores equal to remaining tiles (Joker = -30 pts).
 */
@Composable
fun VictoryScreen(
    scores: List<PlayerEndScore>,
    winnerName: String,
    isMeWinner: Boolean,
    roundNumber: Int = 1,
    onPlayAgain: () -> Unit,
    onBackToLobby: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(540.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(WoodDarkBrown)
                .border(2.5.dp, Color(0xFFFFD54F), RoundedCornerShape(20.dp))
                .padding(22.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Banner
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isMeWinner) "🏆 VICTOIRE ÉCLATANTE !" else "🎉 PARTIE TERMINÉE",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD54F),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (isMeWinner) "Vous avez vidé votre chevalet !" else "$winnerName a posé toutes ses tuiles !",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }

                Divider(color = WoodBorder)

                // Scores Table
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MANCHE $roundNumber · CLASSEMENT CUMULÉ :",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81C784)
                    )

                    scores.sortedByDescending { it.totalScore }.forEachIndexed { index, score ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (score.isWinner) Color(0xFF1B5E20).copy(alpha = 0.6f) else Color(0x33000000))
                                .border(1.dp, if (score.isWinner) Color(0xFF81C784) else Color(0xFF3E2723), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = when (index) {
                                        0 -> "🥇"
                                        1 -> "🥈"
                                        2 -> "🥉"
                                        else -> "  "
                                    },
                                    fontSize = 14.sp
                                )
                                Column {
                                    Text(
                                        text = score.playerName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (score.isWinner) Color(0xFFFFD54F) else Color.White
                                    )
                                    if (!score.isWinner) {
                                        Text(
                                            text = "${score.remainingTileCount} tuiles restantes" + if (score.hadJokerPenalty) " (dont Joker: -30 pts)" else "",
                                            fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${score.totalScore} pts",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = "cette manche : " + (if (score.scoreDelta > 0) "+${score.scoreDelta}" else "${score.scoreDelta}"),
                                    fontSize = 10.sp,
                                    color = if (score.scoreDelta > 0) Color(0xFF69F0AE) else Color(0xFFFF8A80)
                                )
                            }
                        }
                    }
                }

                Divider(color = WoodBorder)

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onBackToLobby,
                        modifier = Modifier.weight(1f).height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723))
                    ) {
                        Text("Retour au Salon", fontSize = 12.sp, color = Color.White)
                    }

                    Button(
                        onClick = onPlayAgain,
                        modifier = Modifier.weight(1f).height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                    ) {
                        Text("Rejouer une Manche", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
