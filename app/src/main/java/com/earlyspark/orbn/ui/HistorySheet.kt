package com.earlyspark.orbn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlyspark.orbn.model.HistoryEntry

private val PanelBg = Color(0xFF14151B)
private val Accent = Color(0xFF5B8DEF)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextValue = Color(0xFFC4CAD6)
private val TextDim = Color(0xFF838B9C)
private val DividerColor = Color(0xFF24262F)

/**
 * History side sheet (swipe-right; slides in from the left — mirror of the mood drawer). A scrollable
 * list of recent plays (with repeats), each showing the track, the energy you were in *then*, and
 * your current 👍/👎 for it — tappable to set, tap the active one again to clear. Lets you give,
 * change, or null out feedback after the fact.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistorySheet(
    visible: Boolean,
    entries: List<HistoryEntry>,
    onRate: (HistoryEntry, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — tap to close.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(PanelBg)
                    // Absorb taps so they don't fall through to the scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Swipe left (opposite of the swipe-right that opened it) → dismiss. Vertical
                    // drags fall through to the list's scroll.
                    .pointerInput(Unit) {
                        val threshold = 100.dp.toPx()
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onHorizontalDrag = { change, amount -> dx += amount; change.consume() },
                            onDragEnd = { if (dx < -threshold) onDismiss() },
                        )
                    }
                    .systemBarsPadding()
                    .padding(start = 24.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            ) {
                Text("History", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                if (entries.isEmpty()) {
                    Text("Nothing played yet.", color = TextDim, fontSize = 13.sp)
                } else {
                    LazyColumn {
                        items(entries, key = { it.id }) { entry ->
                            HistoryRow(entry, onRate)
                            HorizontalDivider(color = DividerColor, thickness = 1.dp, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(e: HistoryEntry, onRate: (HistoryEntry, Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                e.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false, modifier = Modifier.basicMarquee(),
            )
            e.artist?.let {
                Text(it, color = TextDim, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.MonitorHeart, contentDescription = "energy",
                    tint = TextDim, modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "${e.energyLabel} · ${"%.2f".format(e.energyValue)}",
                    color = TextValue, fontSize = 11.sp,
                )
            }
        }
        // Tap to set; tap the active one again to clear (rating 0).
        ThumbButton(down = false, active = e.rating == 1) { onRate(e, if (e.rating == 1) 0 else 1) }
        ThumbButton(down = true, active = e.rating == -1) { onRate(e, if (e.rating == -1) 0 else -1) }
    }
}

@Composable
private fun ThumbButton(down: Boolean, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.ThumbUp,
            contentDescription = if (down) "thumbs down" else "thumbs up",
            tint = if (active) Accent else TextDim,
            modifier = Modifier.size(20.dp).then(if (down) Modifier.rotate(180f) else Modifier),
        )
    }
}
