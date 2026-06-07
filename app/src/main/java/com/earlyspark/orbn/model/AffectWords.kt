package com.earlyspark.orbn.model

/**
 * Plain-language words for the affect numbers (D24 / SPEC §13 readout rework). "0.60" means nothing
 * to a normal user; the home + viz readout speaks in words, and the raw number is shown only in the
 * "why this track" detail. Pure Kotlin (no Android deps) so it's shared everywhere and JVM-testable.
 */

/**
 * Energy 0..1 → a calm↔charged word. Energy is *arousal* (activation), independent of mood — so the
 * words stay valence-neutral: a loud, sad song is legitimately "intense", not "lively". Boundaries
 * are inclusive on the low side.
 */
fun energyWord(energy: Float): String = when {
    energy < 0.20f -> "calm"
    energy < 0.40f -> "mellow"
    energy < 0.60f -> "moderate"
    energy < 0.80f -> "intense"
    else -> "charged"
}

/** Oura readiness 0..100 → a recovery word; null score → null (omit from the readout). */
fun readinessWord(score: Int?): String? = when {
    score == null -> null
    score < 70 -> "run down"
    score < 85 -> "steady"
    else -> "well recovered"
}

/** Valence 0..1 → a sad↔bright word, for the "why this track" detail. */
fun valenceWord(valence: Float): String = when {
    valence < 0.20f -> "downbeat"
    valence < 0.40f -> "wistful"
    valence < 0.60f -> "even"
    valence < 0.80f -> "warm"
    else -> "bright"
}
