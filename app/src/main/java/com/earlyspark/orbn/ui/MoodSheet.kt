package com.earlyspark.orbn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlyspark.orbn.match.Mood

private val PanelBg = Color(0xFF12131A)
private val Accent = Color(0xFF5B8DEF)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextDim = Color(0xFF7C8499)

private const val DEFAULT_DESC = "Let orbn decide."

/**
 * Mood-override side sheet (D17 / D26, swipe-left). A grid of mood chips on the valence×energy
 * circumplex. Tapping a chip only **selects** it and previews what it does — nothing changes until
 * **Apply** (which only appears once the selection differs from the active mood). "Default" clears
 * the override and follows Oura.
 *
 * @param visible Whether the sheet is shown.
 * @param current The active mood, or null for Default.
 * @param onApply Commit the chosen mood (or null for Default) — closes + re-tunes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodSheet(
    visible: Boolean,
    current: Mood?,
    onApply: (Mood?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — tap to cancel without applying.
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
            // Local selection — seeded from the active mood; applied only on "Set mood".
            var selected by remember(visible) { mutableStateOf(current) }
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(PanelBg)
                    // Absorb taps so they don't fall through to the scrim and close the sheet.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Swipe right (opposite of the swipe-left that opened it) → dismiss.
                    .pointerInput(Unit) {
                        val threshold = 100.dp.toPx()
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onHorizontalDrag = { change, amount -> dx += amount; change.consume() },
                            onDragEnd = { if (dx > threshold) onDismiss() },
                        )
                    }
                    .systemBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // Match the "why this song" drawer's title styling for cross-drawer consistency.
                Text("Mood", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(24.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoodChip("Default", selected = selected == null) { selected = null }
                    Mood.entries.forEach { mood ->
                        MoodChip(mood.label, selected = selected == mood) { selected = mood }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // Preview of the highlighted mood — reserve height so the layout doesn't jump.
                Text(
                    text = selected?.description ?: DEFAULT_DESC,
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.heightIn(min = 40.dp),
                )
                Spacer(Modifier.height(16.dp))
                // Apply only appears once the selection differs from the active mood — but its slot is
                // ALWAYS reserved (fixed height) so the centered column doesn't jump when it shows/hides.
                // A plain clickable Text (not a Button) so it lines up flush-left with the rest.
                Box(modifier = Modifier.height(36.dp)) {
                    if (selected != current) {
                        Text(
                            "Apply",
                            color = Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { onApply(selected) }
                                .padding(vertical = 8.dp),
                        )
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
