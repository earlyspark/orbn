package com.earlyspark.orbn.oura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressSignalTest {

    private val now = 1_000_000_000_000L
    private val day = "2026-06-10"

    private fun state(s: Long?, r: Long?, changedAt: Long?, nudge: Float? = null, nudgeAt: Long? = null) =
        StressSignal.State(s, r, changedAt, nudge, nudgeAt)

    // --- update ----------------------------------------------------------------------------

    @Test
    fun first_observation_baselines_without_a_nudge() {
        val st = StressSignal.update(null, null, day, 900L, 0L, now)
        assertEquals(900L, st.stressHighSec)
        assertEquals(0L, st.recoveryHighSec)
        assertEquals(now, st.changedAt)
        assertNull(st.nudge)
    }

    @Test
    fun missing_document_carries_previous_state() {
        val prev = state(900L, 0L, now - 60_000, nudge = 0.5f, nudgeAt = now - 60_000)
        val st = StressSignal.update(prev, day, null, null, null, now)
        assertEquals(prev, st)
    }

    @Test
    fun day_rollover_rebaselines_without_a_nudge() {
        val prev = state(7200L, 900L, now - 60_000, nudge = 0.5f, nudgeAt = now - 60_000)
        val st = StressSignal.update(prev, day, "2026-06-11", 0L, 0L, now)
        assertEquals(0L, st.stressHighSec)
        assertNull(st.nudge)
        assertEquals(now, st.changedAt)
    }

    @Test
    fun unchanged_counters_keep_the_previous_nudge_decaying() {
        val prev = state(900L, 0L, now - 600_000, nudge = 0.5f, nudgeAt = now - 600_000)
        val st = StressSignal.update(prev, day, day, 900L, 0L, now)
        assertEquals(prev, st) // changedAt untouched → window keeps growing
    }

    @Test
    fun stress_delta_within_window_yields_positive_nudge() {
        // 900 s of new stress over a 1800-s window → nudge = +0.5.
        val prev = state(900L, 0L, now - 1800_000, null, null)
        val st = StressSignal.update(prev, day, day, 1800L, 0L, now)
        assertEquals(0.5f, st.nudge!!, 0.001f)
        assertEquals(now, st.nudgeAt)
        assertEquals(now, st.changedAt)
    }

    @Test
    fun recovery_delta_yields_negative_nudge() {
        val prev = state(900L, 0L, now - 1800_000, null, null)
        val st = StressSignal.update(prev, day, day, 900L, 900L, now)
        assertEquals(-0.5f, st.nudge!!, 0.001f)
    }

    @Test
    fun mixed_delta_nets_out() {
        // +900 stress and +900 recovery over 3600 s → net 0.
        val prev = state(0L, 0L, now - 3600_000, null, null)
        val st = StressSignal.update(prev, day, day, 900L, 900L, now)
        assertEquals(0f, st.nudge!!, 0.001f)
    }

    @Test
    fun backfill_smear_abstains_and_clears_the_old_nudge() {
        // +5400 s landing in an 11-min window (tonight's observed batch) → abstain.
        val prev = state(1800L, 0L, now - 660_000, nudge = 0.4f, nudgeAt = now - 660_000)
        val st = StressSignal.update(prev, day, day, 7200L, 900L, now)
        assertNull(st.nudge)
        assertNull(st.nudgeAt)
        assertEquals(7200L, st.stressHighSec) // counters still recorded as the new baseline
        assertEquals(now, st.changedAt)
    }

    @Test
    fun negative_delta_abstains() {
        // Cloud reprocessing shrank a counter → no lean, baseline updated.
        val prev = state(1800L, 0L, now - 1800_000, null, null)
        val st = StressSignal.update(prev, day, day, 900L, 0L, now)
        assertNull(st.nudge)
        assertEquals(900L, st.stressHighSec)
    }

    @Test
    fun quantization_overshoot_within_slack_is_accepted() {
        // 900-s block landing in a 600-s window: over the window but within slack → valid, capped.
        val prev = state(0L, 0L, now - 600_000, null, null)
        val st = StressSignal.update(prev, day, day, 900L, 0L, now)
        assertEquals(1f, st.nudge!!, 0.001f) // 900/600 coerced to 1
    }

    // --- lean ------------------------------------------------------------------------------

    @Test
    fun lean_is_zero_when_abstaining() {
        assertEquals(0f, StressSignal.lean(null, null, now), 0f)
    }

    @Test
    fun fresh_full_stress_nudge_leans_by_max() {
        assertEquals(StressSignal.MAX_LEAN, StressSignal.lean(1f, now, now), 0.001f)
    }

    @Test
    fun lean_decays_to_zero() {
        val half = StressSignal.lean(1f, now - StressSignal.DECAY_MS / 2, now)
        assertEquals(StressSignal.MAX_LEAN / 2, half, 0.001f)
        assertEquals(0f, StressSignal.lean(1f, now - StressSignal.DECAY_MS, now), 0f)
        assertEquals(0f, StressSignal.lean(1f, now - 2 * StressSignal.DECAY_MS, now), 0f)
    }

    @Test
    fun recovery_lean_is_negative() {
        assertTrue(StressSignal.lean(-0.6f, now, now) < 0f)
    }
}
