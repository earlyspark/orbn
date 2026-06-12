package com.earlyspark.orbn.model

import com.earlyspark.orbn.model.BodyTimeline.Companion.BLOCK_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTimelineTest {

    private val now = 1_000_000_000_000L

    @Test
    fun single_stress_block_ends_at_the_observation() {
        val bands = BodyTimeline.placeBands(dStressSec = 900L, dRecoverySec = 0L, observedAt = now)
        assertEquals(1, bands.size)
        assertEquals(now, bands[0].endMillis)
        assertEquals(now - BLOCK_MILLIS, bands[0].startMillis)
        assertTrue(!bands[0].recovery)
    }

    @Test
    fun multi_block_stress_is_contiguous_before_the_observation() {
        val bands = BodyTimeline.placeBands(dStressSec = 2700L, dRecoverySec = 0L, observedAt = now)
        assertEquals(1, bands.size)
        assertEquals(now - 3 * BLOCK_MILLIS, bands[0].startMillis)
        assertEquals(now, bands[0].endMillis)
    }

    @Test
    fun mixed_delta_places_recovery_before_stress() {
        val bands = BodyTimeline.placeBands(dStressSec = 900L, dRecoverySec = 900L, observedAt = now)
        assertEquals(2, bands.size)
        val stress = bands.first { !it.recovery }
        val recovery = bands.first { it.recovery }
        assertEquals(now, stress.endMillis)
        assertEquals(stress.startMillis, recovery.endMillis)
        assertEquals(stress.startMillis - BLOCK_MILLIS, recovery.startMillis)
    }

    @Test
    fun sub_block_remainder_is_dropped() {
        // Counters move in whole 900-s blocks; anything less is quantization noise.
        assertTrue(BodyTimeline.placeBands(600L, 0L, now).isEmpty())
    }

    @Test
    fun empty_timeline_detects_emptiness() {
        val t = BodyTimeline(0L, 1L, emptyList(), emptyList(), emptyList())
        assertTrue(t.isEmpty)
    }
}
