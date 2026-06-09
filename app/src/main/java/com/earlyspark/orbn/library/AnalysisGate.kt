package com.earlyspark.orbn.library

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide gate that lets the foreground visualizer pause background tagging.
 *
 * Batch analysis ([TaggingService]) is memory-heavy: it decodes whole tracks,
 * runs the Essentia DSP pipeline, and feeds ONNX inference. Doing that while the
 * GL visualizer and playback are foreground stacks several large native
 * allocations at once and can push a low-RAM device into the low-memory killer.
 *
 * The visualizer raises this flag while it is resumed; the tagging service checks
 * it between tracks and suspends its loop until the visualizer is gone.
 */
object AnalysisGate {
    private val visualizerActive = AtomicBoolean(false)

    /** Called from the visualizer's lifecycle (onResume → true, onPause → false). */
    fun setVisualizerActive(active: Boolean) = visualizerActive.set(active)

    /** True while the visualizer is in the foreground and tagging should hold off. */
    fun isVisualizerActive(): Boolean = visualizerActive.get()
}
