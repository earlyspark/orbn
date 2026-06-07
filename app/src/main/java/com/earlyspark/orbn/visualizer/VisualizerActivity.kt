package com.earlyspark.orbn.visualizer

import android.content.ComponentName
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.earlyspark.orbn.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

/**
 * Full-screen projectM visualizer (M6 spike, P2). Standalone activity, launched from the home
 * screen (long-press the orb) — kept separate from MainActivity while projectM is unproven.
 *
 * Gestures (kept consistent with the home orb so the same touch works in both places):
 *  - single tap: play / pause the current track
 *  - long press: exit back to home (matches "long-press to enter the viz" from home)
 *  - system back gesture: also exits
 *
 * P3 wires the live ExoPlayer tap into the renderer (audio-reactive visuals). The real
 * "visualizer is the home screen" integration is M6 proper.
 */
class VisualizerActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VisualizerRenderer
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var downAtMs: Long = 0L
    private var longPressFired: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val presetPath = copyPresetToFiles()
        renderer = VisualizerRenderer(presetPath)

        // Explicit gesture timing (not GestureDetector, whose 500ms long-press is too short for a
        // deliberate "press to switch modes" — a normal tap held ~500ms misfires as long-press).
        val handler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            Log.d(TAG, "long-press → exit viz")
            longPressFired = true
            finish()
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAtMs = ev.eventTime
                        longPressFired = false
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Any meaningful drag cancels both gestures.
                        if (kotlin.math.abs(ev.x - ev.getX(0)) > SLOP || kotlin.math.abs(ev.y - ev.getY(0)) > SLOP) {
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        val held = ev.eventTime - downAtMs
                        if (!longPressFired && held <= TAP_MAX_MS) {
                            Log.d(TAG, "tap (${held}ms) → toggle play/pause")
                            togglePlayPause()
                        }
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
                }
                true
            }
        }
        setContentView(glView)

        connectController()
    }

    override fun onResume() {
        super.onResume()
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onDestroy()
    }

    /** Connect to the running PlaybackService so single-tap can toggle play/pause. */
    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
        }, MoreExecutors.directExecutor())
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private companion object {
        const val TAG = "OrbnViz"
        /** Max press duration that still counts as a tap. */
        const val TAP_MAX_MS = 250L
        /** Hold this long to trigger long-press (exit). Tuned generous so a tap never misfires. */
        const val LONG_PRESS_MS = 700L
        /** Drag pixels that invalidate both gestures. */
        const val SLOP = 24f
    }

    /** Copy a bundled preset out of assets to a real file path projectM can load. */
    private fun copyPresetToFiles(): String {
        val out = File(filesDir, "preset.milk")
        assets.open("presets/211-wave.milk").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.absolutePath
    }
}
