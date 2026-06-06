package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.AffectPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatcherTest {

    private fun cand(id: String, energy: Float, valence: Float = 0.5f, instrumental: Float? = null) =
        Matcher.Candidate(id, AffectPoint(valence, energy), instrumental)

    @Test
    fun queue_is_distinct_and_correct_length() {
        val pool = (1..10).map { cand("t$it", it / 10f) }
        val q = Matcher.buildQueue(pool, MatchTarget(0.5f, 0.2f), count = 5, random = Random(1))
        assertEquals(5, q.size)
        assertEquals("no duplicates", 5, q.map { it.id }.toSet().size)
    }

    @Test
    fun count_is_capped_at_pool_size() {
        val pool = listOf(cand("only", 0.5f))
        val q = Matcher.buildQueue(pool, MatchTarget(0.5f, 0.2f), count = 10, random = Random(1))
        assertEquals(1, q.size)
    }

    @Test
    fun empty_pool_yields_empty_queue() {
        assertTrue(Matcher.buildQueue(emptyList(), MatchTarget.neutral(), 5, Random(1)).isEmpty())
    }

    @Test
    fun strongly_favors_tracks_near_the_target_energy() {
        // "near" sits on the target center; "far" is well outside the band.
        val pool = listOf(cand("near", 0.5f), cand("far", 0.95f))
        var nearFirst = 0
        repeat(1000) { seed ->
            val first = Matcher.buildQueue(pool, MatchTarget(0.5f, 0.15f), count = 1, random = Random(seed.toLong())).first()
            if (first.id == "near") nearFirst++
        }
        // Weighted, not guaranteed — but the far track should rarely win.
        assertTrue("near won only $nearFirst/1000", nearFirst > 850)
    }

    @Test
    fun still_returns_variety_not_just_the_closest() {
        // Three in-band tracks: over many single-picks, more than one distinct track should appear
        // (proof it's sampling, not nearest-point).
        val pool = listOf(cand("a", 0.48f), cand("b", 0.50f), cand("c", 0.52f))
        val firsts = (0 until 200).map {
            Matcher.buildQueue(pool, MatchTarget(0.5f, 0.2f), 1, Random(it.toLong())).first().id
        }.toSet()
        assertTrue("expected variety, saw $firsts", firsts.size >= 2)
    }

    @Test
    fun instrumental_gate_filters_when_set() {
        val pool = listOf(cand("vocal", 0.5f, instrumental = 0.1f), cand("instr", 0.5f, instrumental = 0.9f))
        val q = Matcher.buildQueue(pool, MatchTarget(0.5f, 0.2f, instrumentalMin = 0.5f), 5, Random(1))
        assertEquals(listOf("instr"), q.map { it.id })
    }
}
