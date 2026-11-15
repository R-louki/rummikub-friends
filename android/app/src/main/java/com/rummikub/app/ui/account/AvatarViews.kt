package com.rummikub.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rummikub.app.account.AvatarPresets
import com.rummikub.app.ui.theme.WoodDarkBrown

private val AvatarBackground = Brush.radialGradient(listOf(Color(0xFF5D4037), Color(0xFF3E2723)))

/**
 * What is drawn inside an avatar circle: the uploaded photo ("url:..."), a preset emoji ("emoji:...")
 * or, without avatar, the two first letters of the name. Fills its parent.
 */
@Composable
fun AvatarContent(avatar: String?, name: String, textSize: TextUnit) {
    when {
        avatar != null && avatar.startsWith("url:") -> AsyncImage(
            model = avatar.removePrefix("url:"),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        avatar != null && avatar.startsWith("emoji:") -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(avatar.removePrefix("emoji:"), fontSize = textSize * 1.5f)
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(name.take(2).uppercase(), color = Color.White, fontSize = textSize, fontWeight = FontWeight.Bold)
        }
    }
}

/** A standalone round avatar (lobby bar, friends list...). */
@Composable
fun AvatarCircle(
    avatar: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0xFF8D6E63)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AvatarBackground)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AvatarContent(avatar, name, (size.value * 0.36f).sp)
    }
}

/** Choose a preset avatar, or send a photo from the gallery. */
@Composable
fun AvatarPickerDialog(
    currentAvatar: String?,
    name: String,
    onPickEmoji: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f).heightIn(max = 330.dp).clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = WoodDarkBrown)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Left: preview and photo actions
                Column(
                    modifier = Modifier.width(170.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Mon avatar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))
                    AvatarCircle(currentAvatar, name, 84.dp, borderColor = Color(0xFFFFD54F))
                    Button(
                        onClick = onPickPhoto,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("📷 Ma photo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    if (currentAvatar != null) {
                        TextButton(onClick = onRemove) { Text("Retirer l'avatar", fontSize = 11.sp, color = Color(0xFFFF8A80)) }
                    }
                    TextButton(onClick = onDismiss) { Text("Fermer", fontSize = 12.sp, color = Color(0xFFFFCC80)) }
                }

                // Right: preset avatars
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ou choisissez un avatar :", fontSize = 12.sp, color = Color.White)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(AvatarPresets.emojis) { emoji ->
                            val selected = currentAvatar == "emoji:$emoji"
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(AvatarBackground)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFD54F) else Color(0xFF8D6E63), CircleShape)
                                    .clickable { onPickEmoji(emoji) },
                                contentAlignment = Alignment.Center
                            ) { Text(emoji, fontSize = 24.sp) }
                        }
                    }
                }
            }
        }
    }
}
