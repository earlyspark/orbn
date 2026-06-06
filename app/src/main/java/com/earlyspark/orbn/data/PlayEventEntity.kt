package com.earlyspark.orbn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One logged listening event (D12 — rich logging from v1, can't backfill). Captures what played /
 * was skipped *and the biometric state at the time*, which is the data future personalization needs
 * (the differentiator prior art — e.g. the HeartDJ thesis — flagged as unsolved). The recency
 * penalty in matching reads the PLAYED rows.
 *
 * Append-only; keyed by a random UUID so the table never collides and survives reinstalls cheaply.
 */
@Entity(tableName = "play_events")
data class PlayEventEntity(
    @PrimaryKey val id: String,
    val trackPath: String,
    val type: String,        // "PLAYED" | "SKIPPED"
    val playedAt: Long,      // epoch millis
    // Biometric snapshot at play time (null when no Oura state was available):
    val energyTarget: Float? = null,
    val readiness: Int? = null,
    val arousal: Float? = null,
    val source: String? = null, // BiometricState.Source name, e.g. "OURA"
)

/** Projection: the most recent PLAYED time per track, for the recency penalty. */
data class TrackLastPlayed(
    val trackPath: String,
    val lastPlayed: Long,
)
