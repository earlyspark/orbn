package com.earlyspark.orbn.analysis

/**
 * The result of analyzing a single audio track.
 *
 * All float fields are normalized to 0–1 unless noted.
 *
 * @param bpm          Tempo in beats-per-minute (not normalized — raw value, e.g. 128.0).
 * @param keyStrength  Confidence of the key detection (0–1).
 * @param energy       Normalized RMS energy of the track (0–1).
 * @param valence      "Happiness" score from the mood_happy MusiCNN head (0–1).
 * @param moodTagNames  Ordered list of mood/genre tag label strings.
 * @param moodTagScores Parallel list of activation scores for each tag.
 * @param key          Human-readable key string, e.g. "A minor" or "C major".
 */
data class TrackAnalysis(
    val bpm: Float,
    val keyStrength: Float,
    val energy: Float,
    val valence: Float,
    val moodTagNames: List<String>,
    val moodTagScores: List<Float>,
    val key: String,
) {
    /** Convenience: whether the track leans "happy" (valence > 0.5). */
    val isHappy: Boolean get() = valence > 0.5f

    /** Plain-language mood word derived from the happy/non-happy valence. */
    val moodWord: String get() = when {
        valence > 0.66f -> "bright"
        valence > 0.33f -> "neutral"
        else            -> "melancholic"
    }

    /** One-line summary for logcat / debug UI. */
    fun summary(): String =
        "%.0f BPM  $key  energy=%.2f  valence=%.2f  [$moodWord]"
            .format(bpm, energy, valence)
}
