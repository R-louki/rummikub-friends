package com.rummikub.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rummikub.app.ui.theme.ThemeState

/** Pick the colour of the table (applies immediately, remembered on this phone). */
@Composable
fun ThemePickerDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF2A150C)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🎨 Couleur du thème", color = Color(0xFFFFD54F), fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ThemeState.palettes.forEachIndexed { i, p ->
                        val selected = i == ThemeState.index
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(i) }.padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(p.light, p.medium, p.dark)))
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFD54F) else Color(0x66FFFFFF), CircleShape)
                            )
                            Text(p.name, color = if (selected) Color(0xFFFFD54F) else Color.White, fontSize = 11.sp)
                        }
                    }
                }
                Text("Touchez une couleur : elle s'applique tout de suite.", color = Color(0xB3FFFFFF), fontSize = 11.sp)
            }
        }
    }
}
