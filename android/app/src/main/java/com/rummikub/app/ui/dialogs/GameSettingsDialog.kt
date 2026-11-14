package com.rummikub.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.rummikub.core.audio.FocusGenre
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
    currentTrack: FocusMusicTrack?,
    gameConfig: GameConfig,
    onMusicVolumeChanged: (Float) -> Unit,
    onVoiceVolumeChanged: (Float) -> Unit,
    onSfxVolumeChanged: (Float) -> Unit,
    onVoiceModeChanged: (VoiceChatMode) -> Unit,
    onTrackSelected: (FocusMusicTrack) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(460.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(WoodDarkBrown)
                .padding(18.dp),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙️ Paramètres & Mixage Audio",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Divider(color = WoodBorder)

                // Volume Sliders
                Text("🔊 Volume Musique de Concentration", fontSize = 12.sp, color = Color(0xFFFFD54F))
                Slider(
                    value = musicVolume,
                    onValueChange = onMusicVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD54F), activeTrackColor = Color(0xFFFFD54F))
                )

                Text("🎙️ Volume Voix des Amis (Live Voice)", fontSize = 12.sp, color = Color(0xFF00E5FF))
                Slider(
                    value = voiceVolume,
                    onValueChange = onVoiceVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))
                )

                Text("🎲 Effets Sonores (Bruitages Tuiles)", fontSize = 12.sp, color = Color(0xFF81C784))
                Slider(
                    value = sfxVolume,
                    onValueChange = onSfxVolumeChanged,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF81C784), activeTrackColor = Color(0xFF81C784))
                )

                Divider(color = WoodBorder)

                // Microphone Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode Micro :", fontSize = 13.sp, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = voiceMode == VoiceChatMode.OPEN_MIC,
                            onClick = { onVoiceModeChanged(VoiceChatMode.OPEN_MIC) },
                            label = { Text("Open Mic (VAD)") }
                        )
                        FilterChip(
                            selected = voiceMode == VoiceChatMode.PUSH_TO_TALK,
                            onClick = { onVoiceModeChanged(VoiceChatMode.PUSH_TO_TALK) },
                            label = { Text("Push-to-Talk") }
                        )
                    }
                }

                // Focus Playlist
                Text("🎵 Piste d'Ambiance :", fontSize = 13.sp, color = Color.White)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FocusMusicTrack.DEFAULT_PLAYLIST.forEach { track ->
                        val isSelected = currentTrack?.id == track.id
                        Button(
                            onClick = { onTrackSelected(track) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFFF8F00) else Color(0xFF3E2723)
                            ),
                            contentPadding = PaddingValues(1.dp)
                        ) {
                            Text(track.genre.displayName.take(8), fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
