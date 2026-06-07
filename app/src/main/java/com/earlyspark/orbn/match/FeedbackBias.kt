package com.earlyspark.orbn.match

import kotlin.math.exp

/**
 * Turns the user's thumbs up/down history into a per-track weight multiplier for the [Matcher] —
 * the learning half of the feedback loop (D12/D18: personalize over time, never absolute).
 *
 * A rating only counts to the extent the **current** target resembles the state it was given in
 * (Gaussian on energy distance), and **fades** with age (exponential recency). The summed signal
 * `s` maps through `exp(K·s)`, clamped: a track repeatedly thumbed-down in a similar state is
 * strongly down-weighted *there* (but untouched in other states), a thumbed-up track is boosted.
 * No feedback for a track → it's absent from the map → the Matcher treats it as 1.0 (no effect).
 *
 * Pure and deterministic — the Room query that supplies the ratings lives at the Android boundary,
 * so this is JVM-unit-tested.
 */
object FeedbackBias {

    /** A single rating, reduced to what the bias needs. */
    data class Rating(val rating: Int, val ratedAt: Long, val targetEnergy: Float)

    private const val CONTEXT_SIGMA = 0.20f                     // how close in energy a state must be to count
    private const val RECENCY_TAU_MS = 30L * 24 * 60 * 60 * 1000 // ~month e-folding time
    private const val K = 0.8f                                  // strength of the exp mapping
    private const val FLOOR = 0.25f                             // most a track can be down-weighted
    private const val CEIL = 2.5f                               // most a track can be boosted

    fun multipliers(
        byTrack: Map<String, List<Rating>>,
        target: MatchTarget,
        now: Long,
    ): Map<String, Float> = byTrack.mapValues { (_, ratings) ->
        var s = 0f
        for (r in ratings) {
            val z = (r.targetEnergy - target.energyCenter) / CONTEXT_SIGMA
            val contextSim = exp(-0.5f * z * z)
            val age = (now - r.ratedAt).coerceAtLeast(0L)
            val recency = exp(-age.toFloat() / RECENCY_TAU_MS)
            s += r.rating * contextSim * recency
        }
        exp(K * s).coerceIn(FLOOR, CEIL)
    }
}
