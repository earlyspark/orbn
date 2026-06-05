package com.earlyspark.orbn.analysis

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point for on-device audio analysis.
 *
 * Call [analyze] with an absolute file path to get a [TrackAnalysis].
 * The heavy lifting runs in the native library on an IO dispatcher.
 *
 * Usage:
 *   val analyzer = AudioAnalyzer(assets)
 *   val result   = analyzer.analyze("/sdcard/Android/data/com.earlyspark.orbn/files/Music/track.mp3")
 */
class AudioAnalyzer(private val assetManager: AssetManager) {

    /**
     * Analyze a single audio file. Suspends on [Dispatchers.IO]; safe to call from any
     * coroutine scope. Returns null if the file cannot be decoded or is too short.
     */
    suspend fun analyze(filePath: String): TrackAnalysis? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Analyzing: $filePath")
        try {
            analyzeTrack(assetManager, filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed for $filePath", e)
            null
        }
    }

    // ── JNI ──────────────────────────────────────────────────────────────────

    /**
     * Native implementation: decode audio → Essentia DSP → ONNX mood inference.
     * Returns a [TrackAnalysis] or null on failure.
     */
    private external fun analyzeTrack(assetManager: AssetManager, filePath: String): TrackAnalysis?

    companion object {
        private const val TAG = "AudioAnalyzer"

        init {
            System.loadLibrary("orbn_analysis")
        }
    }
}
