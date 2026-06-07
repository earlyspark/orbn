package com.earlyspark.orbn.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackBiasTest {

    private val now = 1_000_000_000_000L
    private val target = MatchTarget(energyCenter = 0.5f, energyBand = 0.2f)

    private fun rating(rating: Int, energy: Float = 0.5f, ageMs: Long = 0L) =
        FeedbackBias.Rating(rating, now - ageMs, energy)

    @Test
    fun no_feedback_yields_empty_map() {
        assertTrue(FeedbackBias.multipliers(emptyMap(), target, now).isEmpty())
    }

    @Test
    fun thumbs_down_in_same_state_downweights_below_one() {
        val m = FeedbackBias.multipliers(mapOf("t" to listOf(rating(-1))), target, now)
        assertTrue("expected <1, got ${m["t"]}", m["t"]!! < 1f)
    }

    @Test
    fun thumbs_up_in_same_state_boosts_above_one() {
        val m = FeedbackBias.multipliers(mapOf("t" to listOf(rating(+1))), target, now)
        assertTrue("expected >1, got ${m["t"]}", m["t"]!! > 1f)
    }

    @Test
    fun far_off_state_barely_counts() {
        // A thumbs-down given while energy was 0.95, evaluated at target 0.5 → near no effect.
        val far = FeedbackBias.multipliers(mapOf("t" to listOf(rating(-1, energy = 0.95f))), target, now)["t"]!!
        val near = FeedbackBias.multipliers(mapOf("t" to listOf(rating(-1, energy = 0.5f))), target, now)["t"]!!
        assertTrue("far-state feedback should be weaker: far=$far near=$near", far > near)
        assertEquals("far-off ≈ no effect", 1f, far, 0.1f)
    }

    @Test
    fun old_feedback_fades() {
        val recent = FeedbackBias.multipliers(mapOf("t" to listOf(rating(-1))), target, now)["t"]!!
        val old = FeedbackBias.multipliers(
            mapOf("t" to listOf(rating(-1, ageMs = 120L * 24 * 60 * 60 * 1000))), target, now,
        )["t"]!!
        assertTrue("old feedback should fade toward 1: recent=$recent old=$old", old > recent)
    }

    @Test
    fun multipliers_are_clamped() {
        val many = List(50) { rating(-1) }
        val m = FeedbackBias.multipliers(mapOf("t" to many), target, now)["t"]!!
        assertTrue("clamped at floor", m >= 0.25f)
    }
}
