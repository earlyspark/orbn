package com.earlyspark.orbn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FeedbackDao {

    /** Upsert the track's rating — a new 👍/👎 replaces any prior one (one row per track). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: FeedbackEntity)

    /** Null out a track's rating (History "clear"). */
    @Query("DELETE FROM feedback WHERE trackPath = :path")
    suspend fun clear(path: String)

    /** Every feedback row (one per track) — input to the feedback bias and the History ratings. */
    @Query("SELECT * FROM feedback")
    suspend fun all(): List<FeedbackEntity>

    @Query("SELECT COUNT(*) FROM feedback")
    suspend fun count(): Int
}
