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
        FeedbackEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class OrbnDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun ouraDao(): OuraDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun feedbackDao(): FeedbackDao

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

        /** v7 → v8 adds intra-day movement (latest MET + activity class) to the daily cache. Additive. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `oura_daily` ADD COLUMN `metLatest` REAL")
                db.execSQL("ALTER TABLE `oura_daily` ADD COLUMN `activityClass` INTEGER")
            }
        }

        /**
         * v8 → v9 drops the now-unused daily-stress column. SQLite's DROP COLUMN isn't available on
         * older runtimes, so use the portable recreate-table pattern (create without the column,
         * copy, swap). Preserves the cached daily rows.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `oura_daily_new` (
                        `day` TEXT NOT NULL,
                        `readinessScore` INTEGER,
                        `sleepScore` INTEGER,
                        `restingHr` INTEGER,
                        `hrvMs` INTEGER,
                        `metLatest` REAL,
                        `activityClass` INTEGER,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `oura_daily_new`
                        (`day`, `readinessScore`, `sleepScore`, `restingHr`, `hrvMs`, `metLatest`, `activityClass`, `fetchedAt`)
                    SELECT `day`, `readinessScore`, `sleepScore`, `restingHr`, `hrvMs`, `metLatest`, `activityClass`, `fetchedAt`
                    FROM `oura_daily`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `oura_daily`")
                db.execSQL("ALTER TABLE `oura_daily_new` RENAME TO `oura_daily`")
            }
        }

        /** v9 → v10 adds the thumbs up/down feedback log (D12). Additive; existing data untouched. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feedback` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `trackPath` TEXT NOT NULL,
                        `ratedAt` INTEGER NOT NULL,
                        `rating` INTEGER NOT NULL,
                        `targetEnergy` REAL NOT NULL,
                        `targetValence` REAL,
                        `source` TEXT NOT NULL,
                        `trackEnergy` REAL NOT NULL,
                        `trackValence` REAL NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v10 → v11 makes feedback **one row per track** (`trackPath` PK, was an autogen id), so a
         * rating can be replaced/cleared. Recreate-table pattern; collapse any duplicates to the
         * latest per track by inserting in ascending `ratedAt` order with REPLACE.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feedback_new` (
                        `trackPath` TEXT NOT NULL,
                        `ratedAt` INTEGER NOT NULL,
                        `rating` INTEGER NOT NULL,
                        `targetEnergy` REAL NOT NULL,
                        `targetValence` REAL,
                        `source` TEXT NOT NULL,
                        `trackEnergy` REAL NOT NULL,
                        `trackValence` REAL NOT NULL,
                        PRIMARY KEY(`trackPath`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `feedback_new`
                        (`trackPath`, `ratedAt`, `rating`, `targetEnergy`, `targetValence`, `source`, `trackEnergy`, `trackValence`)
                    SELECT `trackPath`, `ratedAt`, `rating`, `targetEnergy`, `targetValence`, `source`, `trackEnergy`, `trackValence`
                    FROM `feedback` ORDER BY `ratedAt` ASC
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `feedback`")
                db.execSQL("ALTER TABLE `feedback_new` RENAME TO `feedback`")
            }
        }

        fun get(context: Context): OrbnDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrbnDatabase::class.java,
                    "orbn.db"
                )
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11,
                    )
                    // Backstop only: analysis data is reproducible from the audio files, so an
                    // unforeseen schema gap can safely rebuild + re-tag rather than crash.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
