package com.earlyspark.orbn.visualizer

/**
 * Bridges decoded audio (written from ExoPlayer's audio thread, via the TeeAudioProcessor in
 * PlaybackService) to the visualizer (read on the GL thread). Holds the most-recent mono-float
 * chunk; the GL thread takes it once per frame and feeds projectM. A dropped/duplicated chunk now
 * and then is fine for visualization, so this stays lock-free (a volatile reference swap).
 */
object AudioTap {
    @Volatile private var latest: FloatArray? = null

    /** Called from the audio thread with downmixed mono samples in [-1, 1]. */
    fun submit(mono: FloatArray) {
        latest = mono
    }

    /** Called from the GL thread; returns the latest chunk once (null if nothing new). */
    fun take(): FloatArray? {
        val chunk = latest
        latest = null
        return chunk
    }

    /** Clear on teardown so a stale chunk doesn't linger into the next session. */
    fun clear() {
        latest = null
    }
}
