package com.earlyspark.orbn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * orbn's local store. Single source of fast, queryable analysis data for the
 * matching engine. (File-tag write-back, the portable copy, comes in M2b.)
 */
@Database(entities = [TrackEntity::class], version = 3, exportSchema = false)
abstract class OrbnDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile private var INSTANCE: OrbnDatabase? = null

        fun get(context: Context): OrbnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrbnDatabase::class.java,
                    "orbn.db"
                )
                    // Analysis data is fully reproducible from the audio files, so a
                    // schema change just rebuilds the DB and re-tags (no migration needed).
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
