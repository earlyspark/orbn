package com.earlyspark.orbn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// orbn's pixel-art mascot — a small tamagotchi-style character drawn on a 44×50 pixel grid (the
// proportions are ported 1:1 from the approved design). It replaces the old abstract breathing orb.
//
// What it encodes / how it moves (all timer-driven — deliberately NOT synced to the audio; only the
// full-screen visualizer reacts to sound):
//   • energy  → the head's hue: cool aqua (calm) → mint (neutral) → warm peach (lively). This is the
//                load-bearing biometric/mood read the old orb's colour used to carry.
//   • playing → the character is "awake": it blinks, occasionally bobs, glances around, and smiles now
//                and then. Paused → it dozes (eyes shut, mouth neutral, no bob). Toggling play/pause
//                visibly perks it up / settles it down.
//   • burstTick (a deliberate re-pick) → a happy reaction: wide eyes, a smile, a little double-bounce.
//   • nudgeTick (orb tapped with no library) → it glances down toward the "add music" cue and hops.
//
// All motion is slow and eased / low-amplitude (1px hops), so there is no strobing — preserved from the
// orb's photosensitivity-safe constraint.

private val BAND = Color(0xFF3C3489)   // headphone band + feet (dark indigo)
private val CUP = Color(0xFF534AB7)    // ear-cup outer + body + arms (purple)
private val CUP_INNER = Color(0xFFAFA9EC)
private val EYE = Color(0xFF085041)    // eyes + mouth (dark teal)
private val WHITE = Color(0xFFFFFFFF)
private val CHEEK = Color(0xFFF09595)

// The head hue is read off the "circumplex of affect": a colour wheel over valence (x) × energy (y).
// Neutral (the resting / no-signal point) is the character's true mint; the further the state sits
// from neutral, the more saturated the mood colour. Anchors run anticlockwise from +valence (east),
// at 45° steps, so each mood lands on its own hue — happy=gold, excited=orange, angry=red, sad=blue,
// chill=green, calm=teal — instead of everything collapsing onto green.
private val MINT = Color(0xFF9FE1CB)      // neutral — the character's identity colour (matches the icon)
private val WHEEL = listOf(
    Color(0xFFDEC487), //   0° E  — pleasant / happy → soft muted gold (kept dim — bright gold glares)
    Color(0xFFE0A074), //  45° NE — elated → muted amber
    Color(0xFFA878F0), //  90° N  — high arousal / excited → electric violet
    Color(0xFFD98A8A), // 135° NW — tense / angry → dusty rose-red (kept dim — pure red glares)
    Color(0xFF5E8FF0), // 180° W  — sad → blue
    Color(0xFF7E8AEA), // 225° SW — melancholy → indigo
    Color(0xFF5FCFC9), // 270° S  — calm → teal
    Color(0xFF9AB39F), // 315° SE — content / chill → dusty sage (desaturated; vivid green glares)
)
private val BURST_POP = Color(0xFFF47ECB) // brief pink flush when a new track is picked

/**
 * Map an affective state (valence, energy — each 0..1) to the head colour. Neutral → [MINT];
 * departures rotate around [WHEEL] by the state's angle and saturate with its distance from neutral.
 */
private fun affectColor(valence: Float, energy: Float): Color {
    val vx = valence - 0.5f
    val ey = energy - 0.5f
    val radius = (hypot(vx, ey) / 0.5f).coerceIn(0f, 1f)
    if (radius < 0.001f) return MINT
    var ang = atan2(ey, vx)                 // 0 = +valence (east), +π/2 = +energy (north)
    if (ang < 0f) ang += (2 * PI).toFloat()
    val seg = ang / (PI / 4).toFloat()      // 0..8 around the wheel
    val i = seg.toInt() % 8
    val hue = lerp(WHEEL[i], WHEEL[(i + 1) % 8], seg - seg.toInt())
    // Saturate faster than linear so a named mood (radius ~0.6–1.0) reads as its true hue rather than
    // a half-mint blend, while small departures (Oura nudges near neutral) still stay soft.
    return lerp(MINT, hue, (radius * 1.5f).coerceIn(0f, 1f))
}

/**
 * Orbn — the app's pixel-art mascot. Owns all of its own idle animation; the caller only feeds it the
 * live signals.
 *
 * @param valence effective mood valence in 0..1 (with [energy], drives the head hue); 0.5 = neutral,
 *                which is what Oura uses since it leaves valence free
 * @param energy effective biometric/mood energy in 0..1 (with [valence], drives the head hue)
 * @param playing whether audio is currently playing (awake vs. dozing)
 * @param burstTick one-shot counter, bumped on a deliberate re-pick → happy reaction + colour pop
 * @param nudgeTick one-shot counter, bumped when the orb is tapped with an empty library → glance down
 * @param animate false = hold still (reduce-motion setting): no idle loops or reactions. State cues
 *                (dozing eyes, affect hue) still apply — they're state, not motion.
 */
@Composable
fun Orbn(
    valence: Float,
    energy: Float,
    playing: Boolean,
    burstTick: Int,
    nudgeTick: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    // --- Mutable expression state, nudged by the timers/reactions below. ---
    var blink by remember { mutableStateOf(false) }
    var smile by remember { mutableStateOf(false) }
    var look by remember { mutableIntStateOf(0) }        // pupil catch-light x shift: -1, 0, +1
    var lookDown by remember { mutableStateOf(false) }   // pupil catch-light glances down
    var wide by remember { mutableStateOf(false) }       // surprised-happy (burst) — eyes a touch taller

    // A gentle vertical nod: eased down-and-up over ~1s and under one grid cell tall. The rounding in
    // the draw step turns this into a few small sub-pixel steps, so it glides rather than snapping.
    val bobY = remember { Animatable(0f) }   // 0..~0.55 grid cells, applied as a translate in draw
    suspend fun nod() {
        bobY.animateTo(0.55f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        bobY.animateTo(0f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
    }

    // Blink: an occasional quick shut, rarely a double. Only while awake. Kept infrequent so the
    // character feels calm, not twitchy. (Re-keying on `animate` kills/restarts the idle loops when
    // the reduce-motion setting flips; each loop opens with a delay, so there's no visual pop.)
    LaunchedEffect(playing, animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(6000, 13000))
            if (playing) {
                blink = true; delay(110); blink = false
                if (Random.nextFloat() < 0.15f) { delay(130); blink = true; delay(110); blink = false }
            }
        }
    }

    // Bob: a single gentle nod every so often — NOT a steady breathing pulse, and not music-synced.
    LaunchedEffect(playing, animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(12000, 24000))
            if (playing) nod()
        }
    }

    // Smile: rests neutral, eases into a brief smile now and then. Only while awake.
    LaunchedEffect(playing, animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(10000, 20000))
            if (playing) { smile = true; delay(Random.nextLong(1800, 3200)); smile = false }
        }
    }

    // Gaze: a subtle catch-light shift so the eyes glance around. Only while awake.
    LaunchedEffect(playing, animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(8000, 16000))
            if (playing) { look = listOf(-1, 1, -1, 0).random(); delay(Random.nextLong(700, 1300)); look = 0 }
        }
    }

    // Play/pause transition: starting playback perks it up (quick blink → gentle nod → brief smile).
    // The try/finally guarantees the transient flags reset even if this coroutine is cancelled mid-perk
    // (nod() animates the shared bobY Animatable — a competing nod from another effect cancels us, see
    // the burst effect below). Without it a stuck `blink` would shut the eyes while actually playing.
    LaunchedEffect(playing, animate) {
        if (playing && animate) {
            try {
                blink = true; delay(110); blink = false
                nod()
                smile = true; delay(1600); smile = false
            } finally {
                blink = false; smile = false
            }
        }
    }

    // Deliberate re-pick → a happy little reaction (one gentle nod, not a double-hop).
    // nod() animates the single shared bobY Animatable; when a mood is applied the queue rebuild kicks
    // playback, firing the play-transition nod() above, whose competing bobY.animateTo cancels this
    // coroutine mid-reaction. The try/finally resets `wide`/`smile` regardless, so they never latch on —
    // a stuck `wide` keeps `dozing` false and the mascot can't sleep when later paused.
    // (Keyed on burstTick only — adding `animate` to the key would replay a stale burst when
    // re-enabling motion; the condition alone suppresses it while reduced.)
    LaunchedEffect(burstTick) {
        if (burstTick > 0 && animate) {
            try {
                wide = true; smile = true
                nod()
                delay(700)
            } finally {
                smile = false; wide = false
            }
        }
    }

    // …and the head briefly flushes a different colour, then eases back. Slow ease, no strobe.
    val burstColor = remember { Animatable(0f) }
    LaunchedEffect(burstTick) {
        if (burstTick > 0 && animate) {
            burstColor.snapTo(0f)
            burstColor.animateTo(1f, tween(durationMillis = 420, easing = LinearOutSlowInEasing))
            burstColor.animateTo(0f, tween(durationMillis = 1200, easing = FastOutSlowInEasing))
        }
    }

    // Empty-library nudge → glance down toward the "add music" cue + a gentle nod.
    LaunchedEffect(nudgeTick) {
        if (nudgeTick > 0 && animate) {
            lookDown = true; look = 0
            nod()
            delay(300); lookDown = false
        }
    }

    // Dozing when paused (and not mid-burst): eyes shut, mouth neutral, no bob.
    val dozing = !playing && !wide
    // Head hue from the affect wheel, with the brief re-pick colour pop layered on top.
    val headColor = lerp(affectColor(valence, energy), BURST_POP, 0.8f * burstColor.value)

    Canvas(modifier = modifier) {
        // Frame the character on its *content* (cols 4..40, rows 3..39 → ~36×36), not the full 44×50
        // grid, so there's no dead space below the feet floating it upward. A little padding (frame =
        // 40 cells) leaves breathing room. Cells snap to whole device pixels so the art stays crisp.
        val frame = 40f
        val cx = 22f // content centre x in grid coords
        val cy = 21f // content centre y in grid coords
        val scale = min(size.width, size.height) / frame
        val ox = size.width / 2f - cx * scale
        val oy = size.height / 2f - cy * scale
        // The nod in device pixels (a sleeping pet doesn't bob). px() rounds y+dy to whole device
        // pixels, so this eased value advances in a few small steps — it glides, never snaps.
        val bobPx = if (dozing) 0f else bobY.value * scale
        // dy defaults to the bob offset; the feet pass dy = 0f so they stay planted.
        fun px(x: Int, y: Int, w: Int, h: Int, c: Color, dy: Float = bobPx) {
            val l = (ox + x * scale).roundToInt().toFloat()
            val t = (oy + y * scale + dy).roundToInt().toFloat()
            val r = (ox + (x + w) * scale).roundToInt().toFloat()
            val b2 = (oy + (y + h) * scale + dy).roundToInt().toFloat()
            drawRect(c, topLeft = Offset(l, t), size = Size(r - l, b2 - t))
        }

        // Headphone band — arcs over the top of the head.
        px(12, 3, 20, 2, BAND)
        px(10, 4, 2, 2, BAND)
        px(32, 4, 2, 2, BAND)
        px(9, 5, 2, 4, BAND)
        px(33, 5, 2, 4, BAND)

        // Ear cups — chunky pads on each side of the head.
        px(5, 8, 6, 10, CUP); px(4, 9, 8, 8, CUP); px(6, 10, 4, 6, CUP_INNER)
        px(33, 8, 6, 10, CUP); px(32, 9, 8, 8, CUP); px(34, 10, 4, 6, CUP_INNER)

        // Head — three stacked rects give the rounded-square silhouette. Hue follows affect.
        px(11, 5, 22, 20, headColor)
        px(9, 7, 26, 16, headColor)
        px(10, 6, 24, 18, headColor)

        // Eyes — shut when dozing or blinking; otherwise open with a movable catch-light.
        if (dozing || blink) {
            px(15, 14, 3, 1, EYE)
            px(26, 14, 3, 1, EYE)
        } else {
            val eh = if (wide) 4 else 3
            val ey = if (wide) 11 else 12
            px(15, ey, 3, eh, EYE)
            px(26, ey, 3, eh, EYE)
            val ly = if (lookDown) 1 else 0
            px(16 + look, ey + ly, 1, 1, WHITE)
            px(27 + look, ey + ly, 1, 1, WHITE)
        }

        // Cheeks.
        px(12, 17, 3, 2, CHEEK)
        px(29, 17, 3, 2, CHEEK)

        // Mouth — neutral, or a small smile.
        if (smile && !dozing) {
            px(19, 20, 6, 1, EYE)
            px(18, 19, 1, 1, EYE)
            px(25, 19, 1, 1, EYE)
        } else {
            px(19, 19, 6, 1, EYE)
        }

        // Body + nub arms.
        px(14, 25, 16, 10, CUP); px(13, 26, 18, 8, CUP)
        px(9, 27, 5, 4, CUP); px(30, 27, 5, 4, CUP)

        // Feet — planted; they don't bob with the rest of the body.
        px(15, 35, 6, 4, BAND, dy = 0f)
        px(23, 35, 6, 4, BAND, dy = 0f)
    }
}
