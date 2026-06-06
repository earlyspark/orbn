package com.earlyspark.orbn.model

/**
 * The per-track analysis features the affect fold consumes, decoded from a stored TrackEntity.
 *
 * Pure Kotlin — no Android/Room dependency — so the fold ([com.earlyspark.orbn.match.AffectFold])
 * is unit-testable on the JVM. The mapping from the DB row (which parses `moodTagsJson` and the
 * key string) lives at the Android boundary, not here.
 *
 * @property bpm           Tempo in BPM (raw).
 * @property loudness      Normalized RMS loudness, 0..1.
 * @property danceability  Probability the track is danceable, 0..1 — the rhythmic-drive signal.
 * @property happy/sad/aggressive/relaxed  Mood-head probabilities, 0..1.
 * @property isMajorKey    True major, false minor, null if unknown — a small valence nudge.
 * @property instrumental  Probability the track is instrumental, 0..1 (gates functional moods).
 */
data class TrackFeatures(
    val bpm: Float,
    val loudness: Float,
    val danceability: Float,
    val happy: Float,
    val sad: Float,
    val aggressive: Float,
    val relaxed: Float,
    val isMajorKey: Boolean? = null,
    val instrumental: Float? = null,
)
