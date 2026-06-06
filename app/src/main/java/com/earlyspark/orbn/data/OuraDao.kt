package com.earlyspark.orbn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OuraDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(days: List<OuraDailyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeartRate(samples: List<OuraHeartRateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<OuraSessionEntity>)

    /** Most recent daily row (today's metrics once synced). */
    @Query("SELECT * FROM oura_daily ORDER BY day DESC LIMIT 1")
    suspend fun latestDaily(): OuraDailyEntity?

    /** Most recent intra-day heart-rate sample. */
    @Query("SELECT * FROM oura_heart_rate ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestHeartRate(): OuraHeartRateEntity?

    /** Most recently ended session, if any. */
    @Query("SELECT * FROM oura_session ORDER BY atMillis DESC LIMIT 1")
    suspend fun latestSession(): OuraSessionEntity?
}
