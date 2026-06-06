package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.AffectPoint
import com.earlyspark.orbn.model.TrackFeatures

/**
 * Folds a track's analysis features into an [AffectPoint] (valence × energy).
 *
 * **Energy** blends four signals rather than mood alone — the fix for the M1/M2 finding where
 * energy came only from the aggressive/relaxed mood head, burying danceable-but-not-aggressive
 * music (melodic trance scored ~0.06). Danceability — the rhythmic-drive signal, distinct from raw
 * BPM — leads the blend; loudness and tempo ground it; mood activation stays a contributor.
 *
 * **Valence** = pleasantness from the happy/sad heads, with a small major/minor key nudge.
 *
 * Pure and deterministic (no Android deps), so it's JVM-unit-tested and re-tunable without
 * re-analyzing the library (the fold runs at match-time on stored features).
 *
 * Weights tuned 2026-06-06 against the real library; verified spread: electronic/energetic rock
 * 0.7–0.9, melodic trance ~0.6 (was ~0.06), mellow acoustic 0.1–0.4.
 */
object AffectFold {
    // Energy weights — sum to 1.0.
    private const val W_DANCEABILITY = 0.40f
    private const val W_LOUDNESS     = 0.30f
    private const val W_TEMPO        = 0.10f
    private const val W_MOOD         = 0.20f

    // Tempo normalization range (BPM → 0..1). ~60 = slow ballad, ~180 = fast/uptempo.
    private const val TEMPO_MIN = 60f
    private const val TEMPO_MAX = 180f

    // Valence shift for a clearly major (happier) or minor (sadder) key.
    private const val KEY_NUDGE = 0.05f

    fun fold(f: TrackFeatures): AffectPoint {
        val moodActivation = ((f.aggressive + (1f - f.relaxed)) / 2f).coerceIn(0f, 1f)
        val tempoNorm = ((f.bpm - TEMPO_MIN) / (TEMPO_MAX - TEMPO_MIN)).coerceIn(0f, 1f)

        val energy = (
            W_DANCEABILITY * f.danceability +
                W_LOUDNESS * f.loudness +
                W_TEMPO * tempoNorm +
                W_MOOD * moodActivation
            ).coerceIn(0f, 1f)

        var valence = (f.happy + (1f - f.sad)) / 2f
        when (f.isMajorKey) {
            true -> valence += KEY_NUDGE
            false -> valence -= KEY_NUDGE
            null -> {}
        }

        return AffectPoint(valence = valence.coerceIn(0f, 1f), energy = energy)
    }
}
