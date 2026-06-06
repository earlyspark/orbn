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
    val stressSummary: String? = null, // e.g. "restored" / "normal" / "stressful"
    val fetchedAt: Long,
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
