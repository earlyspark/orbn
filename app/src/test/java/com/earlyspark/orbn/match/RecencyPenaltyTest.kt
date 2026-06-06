package com.earlyspark.orbn.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecencyPenaltyTest {

    private val window = 2 * 60 * 60 * 1000L // 2h
    private val floor = 0.1f
    private val now = 1_000_000_000_000L

    @Test
    fun just_played_is_at_the_floor() {
        val m = RecencyPenalty.multipliers(mapOf("t" to now), now, window, floor)
        assertEquals(floor, m["t"]!!, 0.001f)
    }

    @Test
    fun fully_recovered_after_the_window() {
        val m = RecencyPenalty.multipliers(mapOf("t" to now - window), now, window, floor)
        assertEquals(1f, m["t"]!!, 0.001f)
    }

    @Test
    fun older_than_window_is_one() {
        val m = RecencyPenalty.multipliers(mapOf("t" to now - 5 * window), now, window, floor)
        assertEquals(1f, m["t"]!!, 0.001f)
    }

    @Test
    fun recovers_monotonically() {
        val recent = RecencyPenalty.multipliers(mapOf("t" to now - window / 10), now, window, floor)["t"]!!
        val older = RecencyPenalty.multipliers(mapOf("t" to now - window / 2), now, window, floor)["t"]!!
        assertTrue("older should have recovered more: recent=$recent older=$older", older > recent)
        assertTrue(recent in floor..1f && older in floor..1f)
    }
}
