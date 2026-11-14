package com.rummikub.app.ui.dialogs

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
import com.rummikub.core.audio.FocusMusicTrack
import com.rummikub.core.audio.VoiceChatMode
import com.rummikub.core.model.GameConfig

/**
 * Game Settings and Audio Mixer Dialog.
 */
@Composable
fun GameSettingsDialog(
    musicVolume: Float,
    voiceVolume: Float,
    sfxVolume: Float,
    voiceMode: VoiceChatMode,
    isMicMuted: Boolean,
    isMusicPlaying: Boolean,
    currentTrack: FocusMusicTrack?,
    gameConfig: GameConfig,
    onMusicVolumeChanged: (Float) -> Unit,
    onVoiceVolumeChanged: (Float) -> Unit,
    onSfxVolumeChanged: (Float) -> Unit,
    onVoiceModeChanged: (VoiceChatMode) -> Unit,
    onToggleMic: () -> Unit,
    onTrackSelected: (FocusMusicTrack) -> Unit,
    onStopMusic: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(460.dp)
                .heightIn(max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WoodDarkBrown)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        text = "⚙️ Paramètres & Audio",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Divider(color = WoodBorder)

                // Microphone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎙️ Micro :", fontSize = 12.sp, color = Color(0xFF00E5FF))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = voiceMode == VoiceChatMode.OPEN_MIC,
                            onClick = { onVoiceModeChanged(VoiceChatMode.OPEN_MIC) },
                            label = { Text("Ouvert", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = voiceMode == VoiceChatMode.PUSH_TO_TALK,
                            onClick = { onVoiceModeChanged(VoiceChatMode.PUSH_TO_TALK) },
                            label = { Text("Appuyer pour parler", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = isMicMuted,
                            onClick = onToggleMic,
                            label = { Text(if (isMicMuted) "Coupé" else "Couper", fontSize = 11.sp) }
                        )
                    }
                }

                Text("🔉 Volume voix des amis", fontSize = 12.sp, color = Color(0xFF00E5FF))
                Slider(
                    value = voiceVolume,
                    onValueChange = onVoiceVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))
                )

                Divider(color = WoodBorder)

                Text("🎲 Bruitages des tuiles", fontSize = 12.sp, color = Color(0xFF81C784))
                Slider(
                    value = sfxVolume,
                    onValueChange = onSfxVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF81C784), activeTrackColor = Color(0xFF81C784))
                )

                Divider(color = WoodBorder)

                // Ambience
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎵 Ambiance sonore", fontSize = 12.sp, color = Color(0xFFFFD54F))
                    Text(
                        text = if (isMusicPlaying && currentTrack != null) currentTrack.title else "Coupée",
                        fontSize = 10.sp,
                        color = Color(0xAAFFFFFF)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FocusMusicTrack.DEFAULT_PLAYLIST.forEach { track ->
                        val isSelected = isMusicPlaying && currentTrack?.id == track.id
                        Button(
                            onClick = { onTrackSelected(track) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFFF8F00) else Color(0xFF3E2723)
                            ),
                            contentPadding = PaddingValues(1.dp)
                        ) {
                            Text(track.genre.displayName, fontSize = 10.sp, color = Color.White)
                        }
                    }
                    Button(
                        onClick = onStopMusic,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isMusicPlaying) Color(0xFF7F1D1D) else Color(0xFF3E2723)
                        ),
                        contentPadding = PaddingValues(1.dp)
                    ) {
                        Text("Couper", fontSize = 10.sp, color = Color.White)
                    }
                }
                Slider(
                    value = musicVolume,
                    onValueChange = onMusicVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F))
                )

                Divider(color = WoodBorder)

                Text(
                    text = "Règles : tour ${if (gameConfig.turnDurationSeconds == 0) "illimité" else "${gameConfig.turnDurationSeconds}s"} · 1ère pose ${gameConfig.initialMeldThreshold} pts · pénalité ${gameConfig.penaltyTilesCount} tuile(s)",
                    fontSize = 10.sp,
                    color = Color(0xAAFFFFFF)
                )
            }
        }
    }
}
