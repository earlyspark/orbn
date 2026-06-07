package com.earlyspark.orbn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlyspark.orbn.model.WhyThisTrack
import kotlinx.coroutines.launch

private val PanelBg = Color(0xFF14151B)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextValue = Color(0xFFC4CAD6)
private val TextDim = Color(0xFF838B9C)
private val DividerColor = Color(0xFF262A35)

/**
 * Why-this-track bottom sheet (D24 swipe-up). One cohesive type scale — a prominent title, dim
 * labels, light values — Pandora/Spotify-style: title + artist stacked with thumb icons inline, the
 * "why" sentence promoted up, then the song's affect stats (Energy/Feel tappable to explain).
 * Null [info] → hidden. [onThumbsUp] reinforces; [onThumbsDown] records + skips to the next track.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhyThisTrackSheet(
    info: WhyThisTrack?,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = info != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = info != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val w = info ?: return@AnimatedVisibility
            val analyzed = w.energyLabel != "—"

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(PanelBg)
                    // Absorb taps so they don't fall through to the scrim and close the sheet.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // Swipe down (opposite of the swipe-up that opened it) → dismiss.
                    .pointerInput(Unit) {
                        val threshold = 100.dp.toPx()
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dy = 0f },
                            onVerticalDrag = { change, amount -> dy += amount; change.consume() },
                            onDragEnd = { if (dy > threshold) onDismiss() },
                        )
                    }
                    .systemBarsPadding()
                    .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
            ) {
                // Title + artist (left) with thumb icons inline (right) — no separate row.
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f).padding(top = 6.dp, end = 8.dp)) {
                        Text(
                            w.title,
                            color = TextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.basicMarquee(),
                        )
                        w.artist?.let {
                            Text(
                                it,
                                color = TextDim,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(top = 3.dp).basicMarquee(),
                            )
                        }
                    }
                    IconButton(onClick = onThumbsUp) {
                        Icon(Icons.Filled.ThumbUp, contentDescription = "More like this", tint = TextDim, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onThumbsDown) {
                        // No ThumbDown in the core icon set — Material's is ThumbUp rotated 180°, so flip it.
                        Icon(Icons.Filled.ThumbUp, contentDescription = "Not now", tint = TextDim, modifier = Modifier.size(22.dp).rotate(180f))
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(w.reason, color = TextValue, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))

                if (analyzed) {
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = DividerColor, thickness = 1.dp, modifier = Modifier.padding(end = 8.dp))
                    Spacer(Modifier.height(14.dp))

                    // Energy is the matched axis — show YOUR word (same as the home readout); the song's
                    // own energy + the "for variety" nuance live in the tooltip.
                    StatRow(
                        icon = Icons.Filled.MonitorHeart,
                        label = "Energy",
                        value = w.targetEnergyLabel,
                        explanation = "Your energy right now, read from your heart rate and movement — on a " +
                            "0 to 1 scale (1 = most energetic), you're at ${"%.2f".format(w.targetEnergyValue)}.",
                    )
                    StatRow(
                        icon = Icons.Filled.Favorite,
                        label = "Feel",
                        value = w.valenceLabel,
                        explanation = "How bright or downbeat the song feels — computed from its happy vs. sad " +
                            "tone and whether it's in a major or minor key.",
                    )
                    w.topMood?.let {
                        StatRow(
                            icon = Icons.Filled.MusicNote,
                            label = "Mood",
                            value = it,
                            explanation = "The song's main mood — the strongest feeling orbn hears in it " +
                                "(happy, sad, aggressive or relaxed). “Mixed” means none stands out clearly.",
                        )
                    }
                }
            }
        }
    }
}

/**
 * A label + value row. If [explanation] is non-null the row is tappable and shows it in a tooltip
 * (a small info glyph hints at it); tapping anywhere outside the tooltip closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatRow(icon: ImageVector, label: String, value: String, explanation: String?) {
    if (explanation == null) {
        StatRowContent(icon, label, value, modifier = Modifier)
        return
    }
    val tooltipState = rememberTooltipState(isPersistent = true) // stays until an outside tap
    val scope = rememberCoroutineScope()
    val tooltipWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                modifier = Modifier.width(tooltipWidth),
                colors = TooltipDefaults.richTooltipColors(
                    containerColor = Color(0xFF1B2233),
                    contentColor = TextValue,
                ),
            ) { Text(explanation, fontSize = 13.sp) }
        },
        state = tooltipState,
    ) {
        StatRowContent(
            icon, label, value,
            modifier = Modifier.clickable { scope.launch { tooltipState.show() } },
        )
    }
}

@Composable
private fun StatRowContent(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.width(54.dp))
        Text(value, color = TextValue, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
