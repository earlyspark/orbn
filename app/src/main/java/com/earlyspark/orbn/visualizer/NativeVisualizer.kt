package com.earlyspark.orbn.visualizer

/**
 * JNI bindings to the projectM visualizer (M6 spike, P2). All methods must be called on the GL
 * thread with a current GLES 3 context (projectM resolves GL from, and renders into, that context).
 */
object NativeVisualizer {
    init {
        // projectM first (orbn_visualizer depends on it), then our bridge.
        System.loadLibrary("projectM-4")
        System.loadLibrary("orbn_visualizer")
    }

    external fun nativeInit(presetPath: String, width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    /** Switch to a different preset (smooth blend). Call on the GL thread, after init. */
    external fun nativeLoadPreset(presetPath: String)
    /** Feed decoded mono PCM (from the live audio tap) into projectM before rendering. */
    external fun nativeAddPcm(samples: FloatArray, count: Int)
    external fun nativeRenderFrame()
    external fun nativeDestroy()
}
