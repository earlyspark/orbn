package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.AffectPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QueueSequencerTest {

    private fun c(id: String, energy: Float, artist: String? = null) =
        Matcher.Candidate(id, AffectPoint(0.5f, energy), artist = artist)

    private fun totalJump(list: List<Matcher.Candidate>): Double =
        list.zipWithNext().sumOf { abs(it.first.point.energy - it.second.point.energy).toDouble() }

    @Test
    fun smooths_energy_contour_and_preserves_the_set() {
        val zig = listOf(c("a", 0.1f), c("b", 0.9f), c("c", 0.2f), c("d", 0.8f), c("e", 0.3f))
        val seq = QueueSequencer.sequence(zig)
        assertTrue("expected smaller total jump", totalJump(seq) <= totalJump(zig))
        assertEquals("same tracks, none lost/duplicated", zig.map { it.id }.toSet(), seq.map { it.id }.toSet())
        assertEquals(zig.size, seq.size)
    }

    @Test
    fun avoids_back_to_back_same_artist_when_possible() {
        // Equal energies → smoothing is neutral, so the artist rule drives the order.
        val pool = listOf(
            c("a1", 0.5f, "A"), c("a2", 0.5f, "A"),
            c("b1", 0.5f, "B"), c("b2", 0.5f, "B"),
        )
        val seq = QueueSequencer.sequence(pool)
        val adjacentSame = seq.zipWithNext().count { it.first.artist == it.second.artist }
        assertEquals("no back-to-back same artist", 0, adjacentSame)
    }

    @Test
    fun falls_back_when_only_one_artist_remains() {
        val pool = listOf(c("a1", 0.5f, "A"), c("a2", 0.5f, "A"), c("a3", 0.5f, "A"))
        val seq = QueueSequencer.sequence(pool)
        assertEquals(3, seq.size) // doesn't drop tracks just because artists collide
    }

    @Test
    fun small_queues_pass_through() {
        val q = listOf(c("a", 0.5f), c("b", 0.5f))
        assertEquals(q, QueueSequencer.sequence(q))
    }
}
