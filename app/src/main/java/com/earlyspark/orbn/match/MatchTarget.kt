package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.BiometricState

/**
 * The resolved matching target in valence×energy space — source-agnostic (Oura or, later, a manual
 * mood). The selector samples tracks around this target.
 *
 * @property energyCenter   Target energy on the affect plane (0..1).
 * @property energyBand     Half-width / spread of the acceptable energy zone (Gaussian σ).
 * @property valenceCenter  Target valence, or null when valence is FREE (D15 — the Oura case).
 * @property instrumentalMin Minimum instrumentalness, or null for no gate (set by functional moods, D17).
 * @property instrumentalBias Soft lean toward lyric-less tracks (0 = none; >0 favors instrumental
 *                            without excluding vocals — used by the Chill mood, distinct from the gate).
 */
data class MatchTarget(
    val energyCenter: Float,
    val energyBand: Float,
    val valenceCenter: Float? = null,
    val instrumentalMin: Float? = null,
    val instrumentalBias: Float = 0f,
) {
    companion object {
        /** Neutral, fairly wide target when there's no Oura data and no manual mood (D17 fallback). */
        fun neutral() = MatchTarget(energyCenter = 0.5f, energyBand = 0.25f)
    }
}

/**
 * Resolve a [BiometricState] into a [MatchTarget]. Energy comes from Oura; valence stays free
 * (D15) unless the state pins it. The band is floored so the zone is never degenerate (zero σ).
 */
fun BiometricState.toTarget(): MatchTarget = MatchTarget(
    energyCenter = energyCenter,
    energyBand = energyBand.coerceAtLeast(0.05f),
    valenceCenter = if (valenceFree) null else valenceCenter,
    instrumentalMin = null,
)
