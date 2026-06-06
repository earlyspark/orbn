package com.earlyspark.orbn.match

/**
 * Turns "when was each track last played" into a per-track weight multiplier for the [Matcher].
 *
 * A just-played track decays to [floor] (strongly down-weighted, but never zero — so it can still
 * resurface if it's the only fit, which matters on a small library), recovering linearly back to
 * 1.0 over [windowMs]. Tracks not in [lastPlayed] (or older than the window) get 1.0. Pure — the
 * Room query that supplies [lastPlayed] lives at the Android boundary.
 */
object RecencyPenalty {
    fun multipliers(
        lastPlayed: Map<String, Long>,
        now: Long,
        windowMs: Long,
        floor: Float,
    ): Map<String, Float> = lastPlayed.mapValues { (_, playedAt) ->
        val age = (now - playedAt).coerceAtLeast(0L)
        if (age >= windowMs) 1f
        else (floor + (1f - floor) * (age.toFloat() / windowMs)).coerceIn(floor, 1f)
    }
}
