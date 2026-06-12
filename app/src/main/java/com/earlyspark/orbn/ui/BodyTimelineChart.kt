package com.earlyspark.orbn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earlyspark.orbn.model.BodyTimeline
import java.time.Instant
import java.time.ZoneId

private val HrLine = Color(0xFF6FA3DC)
private val StressBand = Color(0x21D85A30) // coral @ ~13% — overlay, never shouts
private val RecoveryBand = Color(0x261D9E75) // sage @ ~15%
private val MetBar = Color(0xFF3D4250)
private val GridLine = Color(0x14FFFFFF)
private val LabelDim = Color(0xFF838B9C)

/**
 * The body timeline (why-this-track sheet, second stage): today's HR curve over full-height
 * stress/recovery overlay bands, with the 5-min movement bars in a lower lane. Drawn entirely
 * from [BodyTimeline] — gaps in HR coverage render as gaps (sync-gated cache, no interpolation),
 * and bands exist only where the stress delta history could place them (attributable syncs).
 */
@Composable
fun BodyTimelineChart(timeline: BodyTimeline, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = LabelDim, fontSize = 10.sp)
    val zone = remember { ZoneId.systemDefault() }

    Canvas(modifier = modifier) {
        val axisPad = 26.dp.toPx() // bottom strip for hour labels
        val gutter = AXIS_GUTTER.toPx() // left strip for bpm/MET tick + unit labels
        val plotH = size.height - axisPad
        val hrH = plotH * 0.71f
        val metTop = plotH * 0.74f
        val metH = plotH - metTop
        val span = (timeline.endMillis - timeline.startMillis).coerceAtLeast(1L)
        fun x(t: Long): Float =
            gutter + (t - timeline.startMillis).toFloat() / span * (size.width - gutter)

        // Stress/recovery bands first — full plot height, behind both data layers.
        timeline.bands.forEach { band ->
            val left = x(band.startMillis.coerceAtLeast(timeline.startMillis))
            val right = x(band.endMillis.coerceAtMost(timeline.endMillis))
            drawRect(
                color = if (band.recovery) RecoveryBand else StressBand,
                topLeft = Offset(left, 0f),
                size = Size(right - left, plotH),
            )
        }

        // HR scale: pad the observed range a touch; keep at least a 40-bpm span so a quiet
        // day doesn't zoom noise into drama.
        val bpmMin = (timeline.hr.minOfOrNull { it.bpm } ?: 50) - 5
        val bpmMax = (timeline.hr.maxOfOrNull { it.bpm } ?: 100) + 5
        val bpmLo = minOf(bpmMin, bpmMax - 40)
        val bpmSpan = (bpmMax - bpmLo).coerceAtLeast(1)
        fun yHr(bpm: Int): Float = hrH - (bpm - bpmLo).toFloat() / bpmSpan * hrH

        // Axis units + faint reference lines with right-aligned ticks in the gutter.
        drawText(measurer, "bpm", Offset(0f, 0f), labelStyle)
        drawText(measurer, "MET", Offset(0f, metTop), labelStyle)
        val refs = listOf(60, 90).filter { it in (bpmLo + 4)..(bpmMax - 4) }
        refs.forEach { bpm ->
            val y = yHr(bpm)
            drawLine(GridLine, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
            val tickLayout = measurer.measure(AnnotatedString("$bpm"), labelStyle)
            drawText(
                tickLayout,
                topLeft = Offset(gutter - 8.dp.toPx() - tickLayout.size.width, y - tickLayout.size.height / 2f),
            )
        }

        // HR polyline; a break larger than GAP_MILLIS starts a new segment (real gap).
        val path = Path()
        var prev: BodyTimeline.HrPoint? = null
        timeline.hr.forEach { p ->
            val px = x(p.atMillis)
            val py = yHr(p.bpm)
            if (prev == null || p.atMillis - prev!!.atMillis > GAP_MILLIS) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
            prev = p
        }
        drawPath(path, HrLine, style = Stroke(width = 2.dp.toPx()))

        // Movement lane: 5-min mean-MET bars on their own small scale.
        val metMax = maxOf(4f, timeline.met.maxOfOrNull { it.met } ?: 4f)
        val barW = (5 * 60_000L).toFloat() / span * size.width
        timeline.met.forEach { m ->
            val h = (m.met / metMax * metH).coerceAtLeast(1.5f)
            drawRect(
                color = MetBar,
                topLeft = Offset(x(m.atMillis), metTop + (metH - h)),
                size = Size((barW - 1f).coerceAtLeast(1f), h),
            )
        }

        // Hour labels: 6 AM / 12 PM / 6 PM where they fall inside the window.
        val startHour = Instant.ofEpochMilli(timeline.startMillis).atZone(zone)
        listOf(6 to "6 AM", 12 to "12 PM", 18 to "6 PM").forEach { (hour, label) ->
            val t = startHour.toLocalDate().atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            if (t in timeline.startMillis..timeline.endMillis) {
                drawText(measurer, label, Offset(x(t) - 10.dp.toPx(), plotH + 6.dp.toPx()), labelStyle)
            }
        }
    }
}

/** An HR silence longer than this is a real coverage gap — break the line, don't bridge it. */
private const val GAP_MILLIS = 20 * 60 * 1000L

/** Width of the chart's left axis strip — the legend indents by the same amount to align. */
internal val AXIS_GUTTER = 34.dp

/**
 * Legend for [BodyTimelineChart]: each entry shows the actual mark (line / shaded band / bar)
 * rather than describing it in words. Band swatches use a stronger alpha than the chart overlay
 * so they read at swatch size. Indented by [AXIS_GUTTER] to left-align with the plot area.
 */
@Composable
fun BodyTimelineLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = AXIS_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("heart rate") {
            Box(Modifier.size(16.dp, 3.dp).background(HrLine, RoundedCornerShape(2.dp)))
        }
        LegendItem("movement") {
            Box(Modifier.size(7.dp, 11.dp).background(MetBar, RoundedCornerShape(1.dp)))
        }
        LegendItem("high stress") {
            Box(Modifier.size(10.dp, 13.dp).background(Color(0xFFD85A30).copy(alpha = 0.35f)))
        }
        LegendItem("recovery") {
            Box(Modifier.size(10.dp, 13.dp).background(Color(0xFF1D9E75).copy(alpha = 0.38f)))
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(5.dp))
        Text(label, color = LabelDim, fontSize = 10.sp)
    }
}
