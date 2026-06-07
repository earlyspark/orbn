package com.earlyspark.orbn.visualizer

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Drives projectM from the GL thread. projectM is created lazily on the first surface-size callback
 * (it needs both a current context and the viewport size), resized thereafter, and rendered each
 * frame. [requestDestroy] must be queued onto the GL thread so teardown happens with the context live.
 */
class VisualizerRenderer(private val presetPath: String) : GLSurfaceView.Renderer {

    private var initialized = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // New context — force re-init on the next size callback.
        initialized = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!initialized) {
            NativeVisualizer.nativeInit(presetPath, width, height)
            initialized = true
        } else {
            NativeVisualizer.nativeResize(width, height)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        NativeVisualizer.nativeRenderFrame()
    }

    /** Call from GLSurfaceView.queueEvent so it runs on the GL thread with the context current. */
    fun destroyOnGlThread() {
        NativeVisualizer.nativeDestroy()
        initialized = false
    }
}
