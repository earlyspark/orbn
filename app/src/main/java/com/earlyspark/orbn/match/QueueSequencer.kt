package com.earlyspark.orbn.match

import kotlin.math.abs

/**
 * Reorders an already-selected queue (from [Matcher]) for a smoother listen. Selection and variety
 * are untouched — only the order changes, and every track is already inside the matched energy band,
 * so the contour can't drift out of the target zone.
 *
 *  - **Transition smoothing:** greedy nearest-energy-neighbour ordering, so consecutive tracks don't
 *    whiplash on energy (no 0.85 → 0.43 → 0.80). Starts from the top-weighted sampled pick.
 *  - **Artist anti-monotony:** when choosing the next track, prefer a different artist than the
 *    previous one if any are available (falls back to same-artist only when unavoidable).
 *
 * Genre runs are intentionally allowed — genre coherence is a feature for a state-matched player,
 * and genre is a coarse non-matching signal. Pure / deterministic; O(n²), fine for queue-sized lists.
 */
object QueueSequencer {
    fun sequence(queue: List<Matcher.Candidate>): List<Matcher.Candidate> {
        if (queue.size <= 2) return queue
        val remaining = queue.toMutableList()
        val result = ArrayList<Matcher.Candidate>(queue.size)
        result.add(remaining.removeAt(0)) // keep the strongest sampled pick first

        while (remaining.isNotEmpty()) {
            val prev = result.last()
            val differentArtist = remaining.filter {
                it.artist == null || prev.artist == null || it.artist != prev.artist
            }
            val pool = differentArtist.ifEmpty { remaining }
            val next = pool.minByOrNull { abs(it.point.energy - prev.point.energy) }!!
            result.add(next)
            remaining.remove(next)
        }
        return result
    }
}
