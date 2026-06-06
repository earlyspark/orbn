package com.earlyspark.orbn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * orbn's local store: queryable music-analysis data for the matching engine (source of truth,
 * D10) plus a cache of fetched Oura metrics that feeds the biometric target (M4).
 */
@Database(
    entities = [
        TrackEntity::class,
        OuraDailyEntity::class,
        OuraHeartRateEntity::class,
        OuraSessionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class OrbnDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun ouraDao(): OuraDao

    companion object {
        @Volatile private var INSTANCE: OrbnDatabase? = null

        /**
         * v3 → v4 adds the Oura cache tables. A real migration (rather than destructive fallback)
         * preserves the already-analyzed track library so the user doesn't pay for a full re-tag.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `oura_daily` (
                        `day` TEXT NOT NULL,
                        `readinessScore` INTEGER,
                        `sleepScore` INTEGER,
                        `restingHr` INTEGER,
                        `hrvMs` INTEGER,
                        `stressSummary` TEXT,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `oura_heart_rate` (
                        `timestamp` TEXT NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `source` TEXT,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`timestamp`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `oura_session` (
                        `id` TEXT NOT NULL,
                        `type` TEXT,
                        `lastHr` INTEGER,
                        `avgHrv` INTEGER,
                        `atMillis` INTEGER,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): OrbnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrbnDatabase::class.java,
                    "orbn.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    // Backstop only: analysis data is reproducible from the audio files, so an
                    // unforeseen schema gap can safely rebuild + re-tag rather than crash.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
