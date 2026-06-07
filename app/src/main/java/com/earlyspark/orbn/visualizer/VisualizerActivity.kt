package com.earlyspark.orbn.visualizer

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import java.io.File

/**
 * Full-screen projectM visualizer (M6 spike, P2). Standalone activity, launched from the home
 * screen (long-press the orb) — kept separate from MainActivity while projectM is unproven.
 *
 * P2 feeds dummy PCM (in native code) so the preset visibly reacts without real audio; P3 wires
 * the live ExoPlayer tap. The real "visualizer is the home screen" integration is M6 proper.
 */
class VisualizerActivity : ComponentActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VisualizerRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val presetPath = copyPresetToFiles()
        renderer = VisualizerRenderer(presetPath)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            // Spike: no chrome yet, so tap anywhere to exit back to home (back gesture also works).
            setOnClickListener { finish() }
        }
        setContentView(glView)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        // Tear projectM down on the GL thread (context still current) before pausing rendering.
        glView.queueEvent { renderer.destroyOnGlThread() }
        glView.onPause()
        super.onPause()
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
