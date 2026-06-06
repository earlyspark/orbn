package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.AffectPoint
import kotlin.math.exp
import kotlin.random.Random

/**
 * Builds a play queue from analyzed tracks and a [MatchTarget].
 *
 * **Weighted random sampling within the target zone (D16), NOT nearest-point.** Always picking the
 * closest track replays the same handful forever — the #1 failure mode of biometric/mood players
 * (see the prior-art note). Instead each track gets a Gaussian weight on its energy distance to the
 * target (σ = band): a *soft* zone that strongly favors fit but never hard-excludes, so even a small
 * or skewed library still yields a varied queue. Valence adds a weight term only when the target
 * pins it (a manual mood); under Oura valence is free (D15). A functional mood can gate by
 * instrumentalness.
 *
 * Pure and deterministic given a seeded [Random] — unit-tested on the JVM. Shaping rules (recency,
 * anti-monotony, transition smoothing) layer on in later M5.2 steps.
 */
object Matcher {

    /** A selectable track: id (path) + affect coordinate, plus instrumentalness (gating) and artist (sequencing). */
    data class Candidate(
        val id: String,
        val point: AffectPoint,
        val instrumental: Float? = null,
        val artist: String? = null,
    )

    private const val VALENCE_SIGMA = 0.25f
    private const val MIN_SIGMA = 0.05f

    /**
     * Sample up to [count] distinct candidates, weighted by fit to [target]. Order is the sampled
     * order (later steps reorder for smooth transitions). Returns fewer than [count] only when the
     * pool is smaller.
     */
    fun buildQueue(
        candidates: List<Candidate>,
        target: MatchTarget,
        count: Int,
        random: Random = Random.Default,
        recency: Map<String, Float> = emptyMap(),
    ): List<Candidate> {
        // Functional-mood gate (no-op under Oura, where instrumentalMin is null).
        val pool = candidates.filter { c ->
            val min = target.instrumentalMin
            min == null || (c.instrumental != null && c.instrumental >= min)
        }.toMutableList()
        if (pool.isEmpty() || count <= 0) return emptyList()

        // Fit weight × recency multiplier (recently-played tracks down-weighted, never excluded).
        val weights = pool.mapTo(ArrayList()) { weightFor(it, target) * (recency[it.id] ?: 1f) }
        val n = minOf(count, pool.size)
        val result = ArrayList<Candidate>(n)

        repeat(n) {
            val total = weights.sum()
            val idx = if (total <= 0f) {
                // All weights underflowed to ~0 (target far from everything): pick uniformly.
                random.nextInt(pool.size)
            } else {
                var r = random.nextDouble() * total
                var i = 0
                while (i < weights.size - 1) {
                    r -= weights[i]
                    if (r <= 0.0) break
                    i++
                }
                i
            }
            result.add(pool.removeAt(idx))
            weights.removeAt(idx)
        }
        return result
    }

    private fun weightFor(c: Candidate, t: MatchTarget): Float {
        val sigma = t.energyBand.coerceAtLeast(MIN_SIGMA)
        var w = gaussian(c.point.energy, t.energyCenter, sigma)
        t.valenceCenter?.let { w *= gaussian(c.point.valence, it, VALENCE_SIGMA) }
        return w
    }

    private fun gaussian(x: Float, mu: Float, sigma: Float): Float {
        val z = (x - mu) / sigma
        return exp(-0.5f * z * z)
    }
}
