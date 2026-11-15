package com.rummikub.app.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.ValidationMode

private val Gold = Color(0xFFFFD54F)

/**
 * "Create a table", laid out for a landscape phone: game mode and players on the left,
 * rules on the right, one big button at the bottom. No scrolling on a normal screen.
 */
@Composable
fun CreateRoomDialog(
    onDismiss: () -> Unit,
    onCreateRoom: (hostName: String, config: GameConfig) -> Unit,
    askName: Boolean = true
) {
    var hostName by remember { mutableStateOf("Hôte") }
    var turnDuration by remember { mutableStateOf(60) }
    var initialThreshold by remember { mutableStateOf(30) }
    var jokerAllowedInInitial by remember { mutableStateOf(true) }
    val jokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
    var validationMode by remember { mutableStateOf(ValidationMode.STRICT_AUTOMATIC) }
    var maxPlayers by remember { mutableStateOf(4) }
    var penaltyTiles by remember { mutableStateOf(3) }
    var vsComputer by remember { mutableStateOf(false) }
    var computerTableSize by remember { mutableStateOf(2) } // total players at the table, 2..4

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, WoodBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("👑 Créer une table", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Gold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Text("✕", color = Color.White, fontSize = 14.sp) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // ---------- LEFT: who plays ----------
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Mode de jeu")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeCard("👥", "Entre amis", "en ligne", !vsComputer, Modifier.weight(1f)) { vsComputer = false }
                            ModeCard("🤖", "Contre l'ordinateur", "solo", vsComputer, Modifier.weight(1f)) { vsComputer = true }
                        }

                        if (vsComputer) {
                            SectionTitle("Joueurs à la table")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 4).forEach { n ->
                                    FilterChip(
                                        selected = computerTableSize == n,
                                        onClick = { computerTableSize = n },
                                        label = { Text("$n  (vous + ${n - 1} ordi)", fontSize = 10.sp) }
                                    )
                                }
                            }
                        } else {
                            SectionTitle("Joueurs maximum")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 4).forEach { count ->
                                    FilterChip(
                                        selected = maxPlayers == count,
                                        onClick = { maxPlayers = count },
                                        label = { Text("$count joueurs", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        if (askName) {
                            OutlinedTextField(
                                value = hostName,
                                onValueChange = { hostName = it },
                                label = { Text("Votre pseudo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Gold,
                                    unfocusedBorderColor = Color(0xFF8D6E63)
                                )
                            )
                        }
                    }

                    // ---------- RIGHT: rules ----------
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1AFFFFFF))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SectionTitle("Règles")
                        RuleRow("⏱️ Durée du tour") {
                            listOf(30 to "30 s", 60 to "60 s", 90 to "90 s", 0 to "Illimité").forEach { (sec, label) ->
                                Chip(label, turnDuration == sec) { turnDuration = sec }
                            }
                        }
                        RuleRow("🎯 1ère pose") {
                            listOf(30 to "30 pts", 20 to "20", 40 to "40", 0 to "Libre").forEach { (pts, label) ->
                                Chip(label, initialThreshold == pts) { initialThreshold = pts }
                            }
                        }
                        RuleRow("🃏 Joker 1ère pose") {
                            Chip("Autorisé", jokerAllowedInInitial) { jokerAllowedInInitial = true }
                            Chip("Interdit", !jokerAllowedInInitial) { jokerAllowedInInitial = false }
                        }
                        RuleRow("⚠️ Pénalité") {
                            listOf(0, 1, 2, 3).forEach { count -> Chip(if (count == 0) "Aucune" else "$count tuile${if (count > 1) "s" else ""}", penaltyTiles == count) { penaltyTiles = count } }
                        }
                        RuleRow("⚖️ Arbitrage") {
                            Chip("Strict", validationMode == ValidationMode.STRICT_AUTOMATIC) { validationMode = ValidationMode.STRICT_AUTOMATIC }
                            Chip("Entre amis", validationMode == ValidationMode.FREE_ARBITRATION) { validationMode = ValidationMode.FREE_ARBITRATION }
                        }
                    }
                }

                // ---------- Create ----------
                Button(
                    onClick = {
                        val config = GameConfig(
                            turnDurationSeconds = turnDuration,
                            initialMeldThreshold = initialThreshold,
                            jokerAllowedInInitialMeld = jokerAllowedInInitial,
                            jokerPointStrategy = jokerPointStrategy,
                            validationMode = validationMode,
                            maxPlayers = if (vsComputer) computerTableSize else maxPlayers,
                            penaltyTilesCount = penaltyTiles,
                            botCount = if (vsComputer) computerTableSize - 1 else 0
                        )
                        onCreateRoom(hostName.trim().ifEmpty { "Hôte" }, config)
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text(
                        if (vsComputer) "▶  Jouer contre l'ordinateur" else "✓  Ouvrir la table",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Gold.copy(alpha = 0.85f), letterSpacing = 1.sp)
}

/** One selectable card for the game mode. */
@Composable
private fun ModeCard(icon: String, title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x33FFD54F) else Color(0x14FFFFFF))
            .border(if (selected) 2.dp else 1.dp, if (selected) Gold else Color(0x44FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 22.sp)
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

/** A rule: its label on the left, its choices on the right, on a single line. */
@Composable
private fun RuleRow(label: String, choices: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = Color.White, modifier = Modifier.width(112.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = choices)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
        modifier = Modifier.height(30.dp)
    )
}
