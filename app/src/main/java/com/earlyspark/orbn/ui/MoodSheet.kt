package com.earlyspark.orbn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlyspark.orbn.match.Mood

private val PanelBg = Color(0xFF12131A)
private val Accent = Color(0xFF5B8DEF)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextDim = Color(0xFF7C8499)

/**
 * Mood-override side sheet (D17 manual emotional mood / D24 swipe-left). A grid of mood chips that
 * pin the matching target in valence×energy space; "Default" clears the override and follows Oura.
 * Slides in from the right edge; picking a chip applies immediately and dismisses.
 *
 * @param visible Whether the sheet is shown.
 * @param current The active mood, or null for Default (following Oura).
 * @param onPick  Called with the chosen mood, or null for Default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodSheet(
    visible: Boolean,
    current: Mood?,
    onPick: (Mood?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — tap to cancel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(PanelBg)
                    .systemBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Mood", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Text("set what plays — overrides Oura", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoodChip("Default", selected = current == null) { onPick(null) }
                    Mood.entries.forEach { mood ->
                        MoodChip(mood.label, selected = current == mood) { onPick(mood) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 14.sp) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = TextDim,
            selectedContainerColor = Accent,
            selectedLabelColor = Color(0xFF0A0A0F),
        ),
    )
}
