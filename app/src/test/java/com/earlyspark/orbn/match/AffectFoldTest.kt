package com.earlyspark.orbn.match

import com.earlyspark.orbn.model.TrackFeatures
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the affect fold. Thresholds reflect the real-library spread the weights were
 * tuned against (2026-06-06): danceable/energetic tracks read high energy, mellow acoustic low,
 * and — the regression this guards — melodic trance (danceable but NOT aggressive) reads mid-high
 * rather than the ~0.06 the old mood-only energy produced.
 */
class AffectFoldTest {

    private fun feat(
        bpm: Float,
        loud: Float,
        dance: Float,
        aggr: Float = 0f,
        relax: Float = 0f,
        happy: Float = 0f,
        sad: Float = 0f,
        major: Boolean? = null,
    ) = TrackFeatures(
        bpm = bpm, loudness = loud, danceability = dance,
        happy = happy, sad = sad, aggressive = aggr, relaxed = relax,
        isMajorKey = major,
    )

    @Test
    fun trance_danceable_but_not_aggressive_reads_mid_high() {
        // "Ethereal Mist" profile — the old bug case (mood-only energy ≈ 0.06).
        val e = AffectFold.fold(feat(bpm = 110f, loud = 0.58f, dance = 1.0f, aggr = 0.02f, relax = 0.91f)).energy
        assertTrue("expected mid-high, got $e", e in 0.55f..0.75f)
    }

    @Test
    fun mellow_acoustic_reads_low() {
        // "Raining in Baltimore" profile.
        val e = AffectFold.fold(feat(bpm = 95f, loud = 0.28f, dance = 0.02f, relax = 0.99f)).energy
        assertTrue("expected low, got $e", e < 0.30f)
    }

    @Test
    fun energetic_rock_reads_high() {
        // "Buddy Holly" profile — loud, aggressive, danceable.
        val e = AffectFold.fold(feat(bpm = 111f, loud = 0.74f, dance = 0.96f, aggr = 1f, relax = 0f)).energy
        assertTrue("expected high, got $e", e > 0.75f)
    }

    @Test
    fun danceability_separates_tracks_at_the_same_tempo() {
        val danceable = AffectFold.fold(feat(bpm = 110f, loud = 0.58f, dance = 1.0f, relax = 0.91f)).energy
        val mellow = AffectFold.fold(feat(bpm = 110f, loud = 0.42f, dance = 0.24f, relax = 0.99f)).energy
        assertTrue("danceable ($danceable) should exceed mellow ($mellow) at equal tempo", danceable > mellow)
    }

    @Test
    fun valence_tracks_happy_sad_and_key() {
        val happyMajor = AffectFold.fold(feat(110f, 0.5f, 0.5f, happy = 0.9f, sad = 0.05f, major = true)).valence
        val sadMinor = AffectFold.fold(feat(110f, 0.5f, 0.5f, happy = 0.05f, sad = 0.9f, major = false)).valence
        assertTrue("happy/major valence high, got $happyMajor", happyMajor > 0.8f)
        assertTrue("sad/minor valence low, got $sadMinor", sadMinor < 0.2f)
    }

    @Test
    fun outputs_are_clamped_to_unit_range() {
        val p = AffectFold.fold(feat(300f, 1f, 1f, aggr = 1f, relax = 0f, happy = 1f, sad = 0f, major = true))
        assertTrue(p.energy in 0f..1f && p.valence in 0f..1f)
    }
}
