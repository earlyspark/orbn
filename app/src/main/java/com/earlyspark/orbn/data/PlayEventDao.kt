package com.earlyspark.orbn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity)

    /** Most recent PLAYED time per track since [since] — input to the recency penalty. */
    @Query(
        "SELECT trackPath, MAX(playedAt) AS lastPlayed FROM play_events " +
            "WHERE type = 'PLAYED' AND playedAt > :since GROUP BY trackPath"
    )
    suspend fun lastPlayedSince(since: Long): List<TrackLastPlayed>

    /** Total events logged (debug / sanity). */
    @Query("SELECT COUNT(*) FROM play_events")
    suspend fun count(): Int
}
