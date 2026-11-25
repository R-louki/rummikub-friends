package com.rummikub.app.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Petits outils posés sur le chevalet : tri par séries ou par groupes, suggestion de coup, rétablir.
 */
@Composable
fun BoxScope.RackToolbar(
    canAct: Boolean,
    canRedo: Boolean,
    onSortRuns: () -> Unit,
    onSortGroups: () -> Unit,
    onSuggest: () -> Unit,
    onRedo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxSize()
,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("789", true, onSortRuns)
            Chip("999", true, onSortGroups)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("💡", canAct, onSuggest, accent = Color(0xFFFFD54F))
            Chip("↷", canRedo, onRedo)
        }
    }
}

@Composable
private fun Chip(label: String, enabled: Boolean, onClick: () -> Unit, accent: Color = Color(0xFFFFCC80)) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (enabled) Color(0xDD1B0E09) else Color(0x661B0E09))
            .border(1.dp, if (enabled) accent.copy(alpha = 0.55f) else Color(0x22FFFFFF), RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) accent else Color(0x66FFFFFF),
            fontSize = if (label.length > 2) 11.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
