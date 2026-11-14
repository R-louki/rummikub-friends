package com.rummikub.app.ui.lobby

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.ValidationMode

@Composable
fun CreateRoomDialog(
    onDismiss: () -> Unit,
    onCreateRoom: (hostName: String, config: GameConfig) -> Unit
) {
    var hostName by remember { mutableStateOf("Hôte") }
    var turnDuration by remember { mutableStateOf(60) }
    var initialThreshold by remember { mutableStateOf(30) }
    var jokerAllowedInInitial by remember { mutableStateOf(true) }
    var jokerPointStrategy by remember { mutableStateOf(JokerPointStrategy.REPLACED_TILE_VALUE) }
    var validationMode by remember { mutableStateOf(ValidationMode.STRICT_AUTOMATIC) }
    var maxPlayers by remember { mutableStateOf(4) }
    var penaltyTiles by remember { mutableStateOf(3) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(480.dp)
                .heightIn(max = 380.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WoodDarkBrown)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👑 Créer une Table Privée",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD54F)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Divider(color = WoodBorder)

                // Nom du joueur
                OutlinedTextField(
                    value = hostName,
                    onValueChange = { hostName = it },
                    label = { Text("Votre pseudo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFD54F),
                        unfocusedBorderColor = Color(0xFF8D6E63)
                    )
                )

                // Durée du tour
                Text("⏱️ Durée du tour :", fontSize = 12.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30s", 60 to "60s", 90 to "90s", 0 to "Illimité").forEach { (sec, label) ->
                        FilterChip(
                            selected = turnDuration == sec,
                            onClick = { turnDuration = sec },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                // Seuil 1ère pose
                Text("🎯 Seuil 1ère Pose :", fontSize = 12.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30 pts (Classique)", 0 to "0 pt (Libre)", 20 to "20 pts", 40 to "40 pts").forEach { (pts, label) ->
                        FilterChip(
                            selected = initialThreshold == pts,
                            onClick = { initialThreshold = pts },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }

                // Joueurs max & Pénalité
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("👥 Joueurs max :", fontSize = 12.sp, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(2, 3, 4).forEach { count ->
                                FilterChip(
                                    selected = maxPlayers == count,
                                    onClick = { maxPlayers = count },
                                    label = { Text("$count") }
                                )
                            }
                        }
                    }

                    Column {
                        Text("⚠️ Pénalité (tuiles) :", fontSize = 12.sp, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1, 3).forEach { count ->
                                FilterChip(
                                    selected = penaltyTiles == count,
                                    onClick = { penaltyTiles = count },
                                    label = { Text("$count") }
                                )
                            }
                        }
                    }
                }

                // Mode d'arbitrage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode :", fontSize = 12.sp, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = validationMode == ValidationMode.STRICT_AUTOMATIC,
                            onClick = { validationMode = ValidationMode.STRICT_AUTOMATIC },
                            label = { Text("Strict", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = validationMode == ValidationMode.FREE_ARBITRATION,
                            onClick = { validationMode = ValidationMode.FREE_ARBITRATION },
                            label = { Text("Entre Amis", fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bouton Créer
                Button(
                    onClick = {
                        val config = GameConfig(
                            turnDurationSeconds = turnDuration,
                            initialMeldThreshold = initialThreshold,
                            jokerAllowedInInitialMeld = jokerAllowedInInitial,
                            jokerPointStrategy = jokerPointStrategy,
                            validationMode = validationMode,
                            maxPlayers = maxPlayers,
                            penaltyTilesCount = penaltyTiles
                        )
                        onCreateRoom(hostName.trim().ifEmpty { "Hôte" }, config)
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("✓ Ouvrir la Table & Inviter", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
