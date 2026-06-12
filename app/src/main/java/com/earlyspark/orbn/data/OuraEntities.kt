package com.earlyspark.orbn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of the user's daily Oura metrics, one row per calendar day. Used to compute the
 * daily energy baseline (D18). Retained without a purge as a knowing single-user exception (D19);
 * a 60-day purge MUST be added before any multi-user distribution.
 */
@Entity(tableName = "oura_daily")
data class OuraDailyEntity(
    @PrimaryKey val day: String, // YYYY-MM-DD
    val readinessScore: Int? = null,
    val sleepScore: Int? = null,
    val restingHr: Int? = null, // bpm — lowest HR overnight, used as HRR baseline
    val hrvMs: Int? = null, // average overnight HRV (rmssd-based), ms
    val metLatest: Float? = null, // most recent 1-min MET (daily_activity) — intra-day movement
    val activityClass: Int? = null, // latest class_5_min: 0 non-wear..5 high activity
    // Daytime-stress delta bookkeeping (StressSignal): the day-cumulative counters as last
    // observed, when they last changed, and the last valid delta-derived lean + its derivation time.
    val stressHighSec: Long? = null,
    val recoveryHighSec: Long? = null,
    val stressChangedAt: Long? = null,
    val stressNudge: Float? = null,
    val stressNudgeAt: Long? = null,
    // Intra-day movement series for the body timeline: 5-min mean METs as a comma-joined string
    // anchored at [metSeriesStart] (ISO-8601), trimmed at fetch time to "now" so the trailing
    // end-of-day filler Oura pre-sizes the day with (F16) is never stored.
    val metSeriesStart: String? = null,
    val metSeries: String? = null,
    val fetchedAt: Long,
)

/**
 * Daytime-stress counter movements (StressSignal.Observation), one row per sync batch that
 * advanced the counters. The body timeline draws stress/recovery bands from **attributable**
 * rows only (delta plausibly within its window); smeared backfills are kept for tallies/QA but
 * carry no positional meaning.
 */
@Entity(tableName = "oura_stress_obs")
data class OuraStressObsEntity(
    @PrimaryKey val observedAt: Long, // epoch millis of the fetch that saw the counters move
    val day: String, // the stress document's day (YYYY-MM-DD)
    val stressHighSec: Long, // cumulative counters as of this observation
    val recoveryHighSec: Long,
    val dStressSec: Long, // movement since the previous observation
    val dRecoverySec: Long,
    val windowStartAt: Long, // when the counters previously changed — the delta's accrual window
    val attributable: Boolean,
)

/**
 * Local cache of intra-day heart-rate samples (~5-min cadence, sync-gated). The most recent
 * sample drives the intra-day arousal nudge (D18). Keyed by the sample's ISO timestamp.
 */
@Entity(tableName = "oura_heart_rate")
data class OuraHeartRateEntity(
    @PrimaryKey val timestamp: String, // ISO-8601
    val bpm: Int,
    val source: String? = null,
    val fetchedAt: Long,
)

/**
 * Local cache of logged "Moment" sessions (breathing/meditation/nap/rest). Carries a higher-
 * fidelity intra-day read than the 5-min HR: when a session is more recent than the latest HR
 * sample, [lastHr]/[avgHrv] override it as the arousal source. [atMillis] is the session's end
 * time (epoch millis) for recency comparison.
 */
@Entity(tableName = "oura_session")
data class OuraSessionEntity(
    @PrimaryKey val id: String,
    val type: String? = null,
    val lastHr: Int? = null,
    val avgHrv: Int? = null,
    val atMillis: Long? = null,
    val fetchedAt: Long,
)
