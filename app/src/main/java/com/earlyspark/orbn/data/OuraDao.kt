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

    // --- Body timeline -----------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStressObs(obs: OuraStressObsEntity)

    /** All counter movements for one day, oldest first — band source for the body timeline. */
    @Query("SELECT * FROM oura_stress_obs WHERE day = :day ORDER BY observedAt ASC")
    suspend fun stressObsForDay(day: String): List<OuraStressObsEntity>

    /** The daily row for one specific day (the timeline reads its persisted MET series). */
    @Query("SELECT * FROM oura_daily WHERE day = :day")
    suspend fun daily(day: String): OuraDailyEntity?

    /**
     * HR samples within an ISO-timestamp range, oldest first. ISO-8601 with a fixed offset sorts
     * lexicographically within a day, which is all the timeline needs.
     */
    @Query("SELECT * FROM oura_heart_rate WHERE timestamp >= :fromIso AND timestamp < :toIso ORDER BY timestamp ASC")
    suspend fun heartRateBetween(fromIso: String, toIso: String): List<OuraHeartRateEntity>
}
