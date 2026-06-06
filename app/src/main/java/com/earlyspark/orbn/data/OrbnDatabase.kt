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
        PlayEventEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class OrbnDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun ouraDao(): OuraDao
    abstract fun playEventDao(): PlayEventDao

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

        /**
         * v4 → v5 adds the M5 analysis features (danceability, voice/instrumental). Existing rows
         * get the columns as NULL and their `analyzedAt` reset, which re-queues them through the
         * tagging worker once so the new features are filled in (analysis is reproducible).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `danceability` REAL")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `voiceInstrumental` REAL")
                db.execSQL("UPDATE `tracks` SET `analyzedAt` = NULL")
            }
        }

        /** v5 → v6 adds the play-history log (D12). Additive; existing data untouched. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_events` (
                        `id` TEXT NOT NULL,
                        `trackPath` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        `energyTarget` REAL,
                        `readiness` INTEGER,
                        `arousal` REAL,
                        `source` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6 → v7 adds embedded artist/title columns to tracks (read-only metadata). Additive. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `artist` TEXT")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `title` TEXT")
            }
        }

        fun get(context: Context): OrbnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrbnDatabase::class.java,
                    "orbn.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // Backstop only: analysis data is reproducible from the audio files, so an
                    // unforeseen schema gap can safely rebuild + re-tag rather than crash.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
