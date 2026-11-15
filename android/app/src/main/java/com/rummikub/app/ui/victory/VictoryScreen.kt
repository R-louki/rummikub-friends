package com.rummikub.app.ui.victory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.rummikub.app.ui.account.AvatarCircle
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown

private val Gold = Color(0xFFFFD54F)

/** Player score summary for the end-of-round screen. */
data class PlayerEndScore(
    val playerId: String,
    val playerName: String,
    val isWinner: Boolean,
    val remainingTileCount: Int,
    val scoreDelta: Int,
    val totalScore: Int = 0,
    val hadJokerPenalty: Boolean = false,
    val avatar: String? = null
)

/** A line of the final ranking of a whole game (all the cumulated rounds). */
data class FinalStanding(
    val playerName: String,
    val totalScore: Int,
    val roundsWon: Int,
    val avatar: String? = null
)

/**
 * End-of-round screen, laid out for a landscape phone: the cumulated ranking on the left, the choices
 * on the right so they are always visible.
 *
 * A game is made of several rounds whose scores add up. The host chooses between the next round and
 * ending the game (final ranking); the other players wait for that choice or leave the table.
 * With [finalStandings] set, it shows the final result of the whole game instead.
 */
@Composable
fun VictoryScreen(
    scores: List<PlayerEndScore>,
    winnerName: String,
    isMeWinner: Boolean,
    roundNumber: Int,
    isHost: Boolean,
    onNextRound: () -> Unit,
    onEndGame: () -> Unit,
    onLeave: () -> Unit,
    finalStandings: List<FinalStanding>? = null,
    roundsPlayed: Int = roundNumber
) {
    val isFinal = finalStandings != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .border(2.5.dp, Gold, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // ---------- LEFT: result and ranking ----------
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            isFinal -> "🏁 FIN DE LA PARTIE"
                            isMeWinner -> "🏆 MANCHE $roundNumber GAGNÉE !"
                            else -> "MANCHE $roundNumber TERMINÉE"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Gold
                    )
                    Text(
                        text = when {
                            isFinal -> "$roundsPlayed manche${if (roundsPlayed > 1) "s" else ""} jouée${if (roundsPlayed > 1) "s" else ""} · vainqueur : $winnerName"
                            isMeWinner -> "Vous avez vidé votre chevalet !"
                            else -> "$winnerName a posé toutes ses tuiles"
                        },
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = if (isFinal) "CLASSEMENT FINAL" else "CLASSEMENT CUMULÉ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81C784),
                        letterSpacing = 1.sp
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (finalStandings != null) {
                            finalStandings.forEachIndexed { index, st ->
                                RankRow(
                                    rank = index,
                                    name = st.playerName,
                                    avatar = st.avatar,
                                    highlighted = index == 0,
                                    detail = "${st.roundsWon} manche${if (st.roundsWon > 1) "s" else ""} gagnée${if (st.roundsWon > 1) "s" else ""}",
                                    total = st.totalScore,
                                    delta = null
                                )
                            }
                        } else {
                            scores.sortedByDescending { it.totalScore }.forEachIndexed { index, s ->
                                RankRow(
                                    rank = index,
                                    name = s.playerName,
                                    avatar = s.avatar,
                                    highlighted = s.isWinner,
                                    detail = if (s.isWinner) "a vidé son chevalet"
                                    else "${s.remainingTileCount} tuiles restantes" + if (s.hadJokerPenalty) " (joker : -30)" else "",
                                    total = s.totalScore,
                                    delta = s.scoreDelta
                                )
                            }
                        }
                    }
                }

                // ---------- RIGHT: the choices, always visible ----------
                Column(
                    modifier = Modifier
                        .width(250.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        isFinal -> {
                            Text("🏆", fontSize = 40.sp)
                            Text(winnerName, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Gold, textAlign = TextAlign.Center)
                            Text("remporte la partie", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                            ChoiceButton("Retour à l'accueil", null, Color(0xFF2E7D32), onLeave)
                        }
                        isHost -> {
                            ChoiceButton("▶  Prochaine manche", "les scores s'additionnent", Color(0xFFE65100), onNextRound)
                            ChoiceButton("🏁  Terminer la partie", "classement final pour tous", Color(0xFF455A64), onEndGame)
                            TextButton(onClick = onLeave) {
                                Text("Quitter la table", fontSize = 11.sp, color = Color(0xFFFFCC80))
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x33FFD54F))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "⏳ L'hôte choisit : prochaine manche ou fin de la partie",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                            ChoiceButton("Quitter la table", null, Color(0xFF455A64), onLeave)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankRow(
    rank: Int,
    name: String,
    avatar: String?,
    highlighted: Boolean,
    detail: String,
    total: Int,
    delta: Int?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) Color(0xFF1B5E20).copy(alpha = 0.6f) else Color(0x33000000))
            .border(1.dp, if (highlighted) Color(0xFF81C784) else Color(0xFF3E2723), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (rank) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "  ${rank + 1}" },
            fontSize = 16.sp,
            modifier = Modifier.width(26.dp)
        )
        AvatarCircle(avatar = avatar, name = name, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (highlighted) Gold else Color.White, maxLines = 1)
            Text(detail, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$total pts", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
            if (delta != null) {
                Text(
                    text = "cette manche : " + (if (delta > 0) "+$delta" else "$delta"),
                    fontSize = 10.sp,
                    color = if (delta >= 0) Color(0xFF69F0AE) else Color(0xFFFF8A80)
                )
            }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, hint: String?, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (hint != null) 62.dp else 50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
            if (hint != null) Text(hint, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}
