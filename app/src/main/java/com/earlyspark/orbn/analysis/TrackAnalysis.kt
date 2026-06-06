package com.earlyspark.orbn.analysis

/**
 * The result of analyzing a single audio track.
 *
 * Field order MUST match the JNI constructor call in orbn_analysis.cpp.
 *
 * The affect model is **valence × energy** (see SPEC):
 *  - valence = pleasantness (unpleasant ↔ pleasant)
 *  - energy  = activation/intensity (calm ↔ energetic)
 *
 * @param bpm             Tempo in beats-per-minute (raw, e.g. 128.0).
 * @param keyStrength     Confidence of the key detection (0–1).
 * @param loudness        Normalized RMS loudness (0–1) — an input feature.
 * @param valence         Pleasantness (0–1): high = happy & not sad.
 * @param energy          Activation/intensity (0–1): high = aggressive & not relaxed.
 * @param genre           Top genre label (rosamerica taxonomy), e.g. "rock".
 * @param genreConfidence Activation of the winning genre (0–1).
 * @param key             Human-readable key, e.g. "A minor".
 * @param moodTagNames    Mood labels: happy, sad, aggressive, relaxed.
 * @param moodTagScores   Parallel raw scores for each mood label.
 * @param danceability    Probability the track is danceable (0–1) — a rhythmic-groove feature
 *                        that feeds the corrected energy fold (M5), distinct from raw BPM.
 * @param voiceInstrumental Probability the track is instrumental (0–1) — gates functional moods
 *                        like "focused"/"background" (D17).
 */
data class TrackAnalysis(
    val bpm: Float,
    val keyStrength: Float,
    val loudness: Float,
    val valence: Float,
    val energy: Float,
    val genre: String,
    val genreConfidence: Float,
    val key: String,
    val moodTagNames: List<String>,
    val moodTagScores: List<Float>,
    val danceability: Float,
    val voiceInstrumental: Float,
) {
    /** Plain-language mood word from the valence/energy quadrant. */
    val moodWord: String get() = when {
        valence >= 0.5f && energy >= 0.5f -> "upbeat"
        valence >= 0.5f && energy <  0.5f -> "serene"
        valence <  0.5f && energy >= 0.5f -> "intense"
        else                              -> "melancholic"
    }

    /** One-line summary for logcat / debug UI. */
    fun summary(): String =
        "%.0f BPM  $key  loud=%.2f  val=%.2f  energy=%.2f  $genre  [$moodWord]"
            .format(bpm, loudness, valence, energy)
}
