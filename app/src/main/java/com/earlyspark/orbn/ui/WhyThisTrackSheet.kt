package com.earlyspark.orbn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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

private val PanelBg = Color(0xFF12131A)
private val Accent = Color(0xFF5B8DEF)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextDim = Color(0xFF7C8499)

/**
 * The data behind the "why this track" sheet, assembled at the Android boundary (MainActivity) from
 * the current track's stored analysis + the active matching target. The raw numbers live here (the
 * one place the user sees them); everywhere else speaks in words.
 */
data class WhyThisTrack(
    val title: String,
    val artist: String?,
    val energyLabel: String,   // e.g. "lively"
    val energyValue: Float,    // 0..1 raw
    val valenceLabel: String,  // e.g. "warm"
    val topMood: String?,      // dominant mood head, or null
    val reason: String,        // one plain-language match sentence
)

/**
 * Why-this-track bottom sheet (D24 swipe-up, Spotify-style reveal). Shows the now-playing track's
 * mood/energy tags + one plain-language reason it fits the current target. Null [info] → hidden.
 */
@Composable
fun WhyThisTrackSheet(info: WhyThisTrack?, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = info != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // Scrim — tap to dismiss.
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
            // Snapshot so the content stays put during the exit animation after info clears.
            val w = info ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(PanelBg)
                    .systemBarsPadding()
                    .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 28.dp),
            ) {
                Text(w.title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                w.artist?.let {
                    Text(it, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "energy: ${w.energyLabel}  ·  ${"%.2f".format(w.energyValue)}",
                    color = Accent,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("feel: ${w.valenceLabel}", color = TextDim, fontSize = 14.sp)
                w.topMood?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("mostly $it", color = TextDim, fontSize = 14.sp)
                }
                Spacer(Modifier.height(18.dp))
                Text(w.reason, color = TextPrimary, fontSize = 15.sp)
            }
        }
    }
}
