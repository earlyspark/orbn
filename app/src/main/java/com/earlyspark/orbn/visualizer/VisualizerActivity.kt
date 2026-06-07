package com.earlyspark.orbn.visualizer

import android.content.ComponentName
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.earlyspark.orbn.match.Mood
import com.earlyspark.orbn.match.QueueBuilder
import com.earlyspark.orbn.model.WhyThisTrack
import com.earlyspark.orbn.model.biometricReadout
import com.earlyspark.orbn.model.energyWord
import com.earlyspark.orbn.oura.Oura
import com.earlyspark.orbn.playback.PlaybackService
import com.earlyspark.orbn.ui.MoodSheet
import com.earlyspark.orbn.ui.RefreshBanner
import com.earlyspark.orbn.ui.WhyThisTrackSheet
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen projectM visualizer (M6 spike). Standalone activity, launched from the home screen
 * (long-press the orb) — kept separate from MainActivity while projectM is unproven.
 *
 * Gestures:
 *  - single tap: play / pause the current track
 *  - double tap: switch to the next visualization preset
 *  - long press: exit back to home
 *  - swipe left / up / down: mood picker / why-this-track / rematch (parity with home, D24)
 *  - system back gesture: also exits
 *
 * (Single-tap is confirmed only after the double-tap window passes, so play/pause lands ~300ms after
 * the tap — the cost of having double-tap on the same surface.)
 *
 * The status/nav bars are hidden (immersive), and the screen is kept awake only while a track is
 * actually playing — paused viz, or the home screen, sleep normally.
 */
class VisualizerActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VisualizerRenderer
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var presetPaths: List<String> = emptyList()
    private var presetIndex: Int = 0
    private val vizPrefs by lazy { getSharedPreferences("orbn_viz", MODE_PRIVATE) }
    private lateinit var presetLabel: TextView
    private lateinit var statusBox: LinearLayout
    private lateinit var trackLine: TextView
    private lateinit var bioLine: TextView

    private val queueBuilder by lazy { QueueBuilder(applicationContext) }

    // Compose overlay state (the home sheets reused over the GL surface).
    private val showOverride = MutableStateFlow(false)
    private val whyThisTrack = MutableStateFlow<WhyThisTrack?>(null)
    private val banner = MutableStateFlow<String?>(null)
    private val manualMood = MutableStateFlow<Mood?>(null)
    private var bannerJob: Job? = null

    private var downAtMs: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var longPressFired: Boolean = false
    private var lastTapUpAtMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val singleTapRunnable = Runnable { togglePlayPause() }
    private val longPressRunnable = Runnable {
        Log.d(TAG, "long-press → exit viz")
        longPressFired = true
        finish()
    }

    /** Keeps the screen awake while playing, and shows the now-playing/biometric lines while paused. */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreenOn(isPlaying)
            updateStatusOverlay(isPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        presetPaths = copyPresetsToFiles()
        presetIndex = restorePresetIndex() // resume the preset from last time
        renderer = VisualizerRenderer(presetPaths.getOrNull(presetIndex) ?: "")

        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAtMs = ev.eventTime
                        downX = ev.x
                        downY = ev.y
                        longPressFired = false
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Any meaningful drag cancels the pending gestures.
                        if (kotlin.math.abs(ev.x - ev.getX(0)) > SLOP || kotlin.math.abs(ev.y - ev.getY(0)) > SLOP) {
                            handler.removeCallbacks(longPressRunnable)
                            handler.removeCallbacks(singleTapRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val held = ev.eventTime - downAtMs
                        if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) > SWIPE_THRESHOLD) {
                            // A swipe — fire the matching action, never a tap/preset change.
                            handler.removeCallbacks(singleTapRunnable)
                            lastTapUpAtMs = 0L
                            onSwipe(dx, dy)
                        } else if (!longPressFired && held <= TAP_MAX_MS) {
                            if (ev.eventTime - lastTapUpAtMs <= doubleTapMs) {
                                // Second tap within the window → double tap: switch preset.
                                handler.removeCallbacks(singleTapRunnable)
                                lastTapUpAtMs = 0L
                                nextPreset()
                            } else {
                                // First tap → wait out the double-tap window before play/pause.
                                lastTapUpAtMs = ev.eventTime
                                handler.postDelayed(singleTapRunnable, doubleTapMs)
                            }
                        }
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        handler.removeCallbacks(singleTapRunnable)
                    }
                }
                true
            }
        }
        presetLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setShadowLayer(6f, 0f, 0f, Color.BLACK) // legible over any preset
            setPadding(32, 16, 32, 16)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0x99000000.toInt())
            }
            alpha = 0f
        }
        statusBox = buildStatusBox()
        val root = FrameLayout(this).apply {
            addView(
                glView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                statusBox,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER },
            )
            addView(
                presetLabel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    leftMargin = 40
                    bottomMargin = 100
                },
            )
        }
        // Compose overlay on top of the GL surface, hosting the same home sheets (mood / why / banner).
        // When all are hidden it draws nothing and lets touches fall through to the GL view's gestures.
        manualMood.value = queueBuilder.manualMood()
        val overlay = ComposeView(this).apply { setContent { VizOverlays() } }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        hideSystemBars()
        showPresetLabel(presetPaths.getOrNull(presetIndex)) // announce the preset we resumed on
        connectController()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars() // bars can reappear after some system interactions
        glView.onResume()
    }

    override fun onPause() {
        // Tear projectM down on the GL thread (context still current) before pausing rendering.
        glView.queueEvent { renderer.destroyOnGlThread() }
        glView.onPause()
        AudioTap.clear() // drop any buffered chunk so it doesn't linger into the next session
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(singleTapRunnable)
        handler.removeCallbacks(longPressRunnable)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }

    /** Hide the status + navigation bars (immersive); a swipe from an edge reveals them transiently. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, glView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The paused-state overlay: now-playing line + the shared biometric readout (no "orbn" title). */
    private fun buildStatusBox(): LinearLayout {
        trackLine = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        bioLine = TextView(this).apply {
            setTextColor(0xFF7FB0FF.toInt()) // soft blue, matching the home readout accent
            textSize = 12f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding(0, 14, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 0, 48, 0)
            addView(trackLine)
            addView(bioLine)
            alpha = 0f
        }
    }

    /** Paused → show the now-playing + biometric lines (persistent). Playing → fade them out. */
    private fun updateStatusOverlay(isPlaying: Boolean) {
        if (isPlaying) {
            statusBox.animate().alpha(0f).setDuration(400L).start()
            return
        }
        val track = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
        trackLine.text = track?.let { "paused · $it" } ?: "paused"
        lifecycleScope.launch {
            val state = runCatching { Oura.repository(this@VisualizerActivity).currentState() }.getOrNull()
            bioLine.text = biometricReadout(state)
        }
        statusBox.animate().cancel()
        statusBox.animate().alpha(1f).setDuration(300L).start()
    }

    /** Connect to the running PlaybackService so taps can drive playback + keep-awake tracks state. */
    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.let {
                it.addListener(playerListener)
                updateKeepScreenOn(it.isPlaying)
                updateStatusOverlay(it.isPlaying)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Classify a swipe (D24, parity with home): left=mood, up=why-this-track, down=rematch. */
    private fun onSwipe(dx: Float, dy: Float) {
        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx < 0) showOverride.value = true // swipe left → mood picker
        } else {
            if (dy < 0) openWhyThisTrack()        // swipe up → why this track
            else reMatch()                        // swipe down → rematch
        }
    }

    /** The reused home sheets, rendered over the visualizer; hidden sheets draw nothing. */
    @Composable
    private fun VizOverlays() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val bannerMsg by banner.collectAsState()
            val overrideVisible by showOverride.collectAsState()
            val why by whyThisTrack.collectAsState()
            val mood by manualMood.collectAsState()
            RefreshBanner(message = bannerMsg)
            MoodSheet(
                visible = overrideVisible,
                current = mood,
                onApply = ::pickMood,
                onDismiss = { showOverride.value = false },
            )
            WhyThisTrackSheet(
                info = why,
                onThumbsUp = ::thumbsUp,
                onThumbsDown = ::thumbsDown,
                onDismiss = { whyThisTrack.value = null },
            )
        }
    }

    /** Assemble the "why this track" detail for the now-playing item (shared engine). */
    private fun openWhyThisTrack() {
        val c = controller ?: return
        val path = c.currentMediaItem?.mediaId ?: run { showBanner("Nothing playing yet"); return }
        val title = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: "This track"
        val artist = c.currentMediaItem?.mediaMetadata?.artist?.toString()
        lifecycleScope.launch { whyThisTrack.value = queueBuilder.whyThisTrack(path, title, artist) }
    }

    /** Swipe-down rematch: pull fresh Oura, rebuild the queue, keep play/pause state. */
    private fun reMatch() {
        val c = controller ?: return
        lifecycleScope.launch {
            showBanner("Re-tuning to how you are now…")
            runCatching { Oura.repository(this@VisualizerActivity).refresh() }
            queueBuilder.applyTo(c, autoPlay = c.isPlaying)
            showBanner("Re-matched · feeling ${energyWord(queueBuilder.currentTarget().energyCenter)}")
        }
    }

    /** Apply a manual mood (or Default) and re-tune, keeping play/pause state. */
    private fun pickMood(mood: Mood?) {
        queueBuilder.setManualMood(mood)
        manualMood.value = mood
        showOverride.value = false
        val c = controller ?: return
        lifecycleScope.launch {
            queueBuilder.applyTo(c, autoPlay = c.isPlaying)
            showBanner(if (mood != null) "Mood · ${mood.label}" else "Following Oura")
        }
    }

    private fun thumbsUp() {
        val path = whyThisTrack.value?.trackPath ?: return
        lifecycleScope.launch { queueBuilder.recordFeedback(path, rating = +1) }
        whyThisTrack.value = null
        showBanner("Noted — more like this")
    }

    private fun thumbsDown() {
        val path = whyThisTrack.value?.trackPath ?: return
        lifecycleScope.launch { queueBuilder.recordFeedback(path, rating = -1) }
        whyThisTrack.value = null
        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        showBanner("Skipped — noted for next time")
    }

    /** Top banner, auto-clearing after a beat (same as home). */
    private fun showBanner(message: String) {
        banner.value = message
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch {
            delay(2200)
            banner.value = null
        }
    }

    /** Advance to the next bundled preset (wraps), and remember it for next time. Loaded on the GL thread. */
    private fun nextPreset() {
        if (presetPaths.isEmpty()) return
        presetIndex = (presetIndex + 1) % presetPaths.size
        val path = presetPaths[presetIndex]
        vizPrefs.edit().putString(KEY_PRESET, File(path).name).apply()
        showPresetLabel(path)
        Log.d(TAG, "double-tap → preset ${presetIndex + 1}/${presetPaths.size}")
        glView.queueEvent { renderer.loadPreset(path) }
    }

    /** Briefly show the current preset's filename (for spotting/curating duds), then fade out. */
    private fun showPresetLabel(path: String?) {
        val name = path?.let { File(it).name.removeSuffix(".milk") } ?: return
        presetLabel.text = name
        presetLabel.animate().cancel()
        presetLabel.alpha = 1f
        presetLabel.animate().alpha(0f).setStartDelay(2000L).setDuration(600L).start()
    }

    /** Restore the last-used preset by name (stable across re-entry and pack changes); 0 if none/unknown. */
    private fun restorePresetIndex(): Int {
        val name = vizPrefs.getString(KEY_PRESET, null) ?: return 0
        return presetPaths.indexOfFirst { File(it).name == name }.takeIf { it >= 0 } ?: 0
    }

    /** Copy every bundled `.milk` preset out of assets to real file paths projectM can load. */
    private fun copyPresetsToFiles(): List<String> {
        val dir = File(filesDir, "presets").apply { mkdirs() }
        val names = assets.list("presets")?.filter { it.endsWith(".milk") }?.sorted() ?: emptyList()
        return names.map { name ->
            val out = File(dir, name)
            assets.open("presets/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            out.absolutePath
        }
    }

    private companion object {
        const val TAG = "OrbnViz"
        /** SharedPreferences key: name of the last-viewed preset, so it persists across re-entry. */
        const val KEY_PRESET = "preset"
        /** Max press duration that still counts as a tap. */
        const val TAP_MAX_MS = 250L
        /** Hold this long to trigger long-press (exit). Tuned generous so a tap never misfires. */
        const val LONG_PRESS_MS = 700L
        /** Drag pixels that invalidate the pending gestures. */
        const val SLOP = 24f
        /** Drag distance that commits a swipe (higher than home so immersive viewing rarely misfires). */
        const val SWIPE_THRESHOLD = 150f
    }
}
