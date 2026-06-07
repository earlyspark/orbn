package com.earlyspark.orbn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FeedbackDao {

    @Insert
    suspend fun insert(event: FeedbackEntity)

    /** Every feedback row — input to the feedback bias (the table stays small: one per 👍/👎). */
    @Query("SELECT * FROM feedback")
    suspend fun all(): List<FeedbackEntity>

    @Query("SELECT COUNT(*) FROM feedback")
    suspend fun count(): Int
}
