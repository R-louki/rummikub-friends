package com.rummikub.app.ui.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown

@Composable
fun JoinRoomDialog(
    onDismiss: () -> Unit,
    onJoinRoom: (roomCode: String, playerName: String) -> Unit,
    askName: Boolean = true
) {
    var playerName by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(400.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(WoodDarkBrown)
                .padding(18.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🚪 Rejoindre une Table",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81C784)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Divider(color = WoodBorder)

                if (askName) OutlinedTextField(
                    value = playerName,
                    onValueChange = { playerName = it },
                    label = { Text("Votre pseudo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF81C784),
                        unfocusedBorderColor = Color(0xFF8D6E63)
                    )
                )

                // Room Code with Paste button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { if (it.length <= 6) roomCode = it.uppercase() },
                        label = { Text("Code à 6 lettres (ex: ZRUJ5M)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFFFD54F),
                            unfocusedTextColor = Color(0xFFFFD54F),
                            focusedBorderColor = Color(0xFFFFD54F),
                            unfocusedBorderColor = Color(0xFF8D6E63)
                        )
                    )

                    Button(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrEmpty()) {
                                roomCode = clip.trim().take(6).uppercase()
                            }
                        },
                        modifier = Modifier.height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5D4037))
                    ) {
                        Text("📋 Coller", fontSize = 11.sp, color = Color.White)
                    }
                }

                Button(
                    onClick = {
                        val name = playerName.trim().ifEmpty { "Ami" }
                        onJoinRoom(roomCode.trim(), name)
                    },
                    enabled = roomCode.trim().length >= 4,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Entrer dans la Salle →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
