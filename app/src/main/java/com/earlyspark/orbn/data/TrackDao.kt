package com.earlyspark.orbn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /** All tracks still needing analysis. */
    @Query("SELECT * FROM tracks WHERE analyzedAt IS NULL")
    suspend fun unanalyzed(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path")
    suspend fun byPath(path: String): TrackEntity?

    /** One-shot snapshot of every analyzed track — input to the matching engine. */
    @Query("SELECT * FROM tracks WHERE analyzedAt IS NOT NULL")
    suspend fun analyzed(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY path")
    fun allTracks(): Flow<List<TrackEntity>>

    /** Live count of every known file. */
    @Query("SELECT COUNT(*) FROM tracks")
    fun totalCount(): Flow<Int>

    /** Live count of files that have been analyzed. */
    @Query("SELECT COUNT(*) FROM tracks WHERE analyzedAt IS NOT NULL")
    fun analyzedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    /** Remove rows whose files are no longer present on disk. */
    @Query("DELETE FROM tracks WHERE path NOT IN (:paths)")
    suspend fun deleteMissing(paths: List<String>)

    /** Wipe everything (used when the folder is empty). */
    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}
