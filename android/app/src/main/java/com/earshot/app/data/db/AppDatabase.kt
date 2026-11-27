package com.earshot.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Podcast::class, Episode::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao

    companion object {
        /** v2: episodes.downloadedAt, used to expire old downloads. Existing downloads are backfilled by the cleanup. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: inbox and queue state on episodes; podcasts.subscribed so unsubscribing can keep queued episodes. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN inInbox INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN queuePosition INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN subscribed INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v4: per-podcast playback settings (null = app default) and time saved per episode. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN speed REAL")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN smartSpeed INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN voiceBoost INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN skipBackMs INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN skipForwardMs INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN timeSavedMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5: pinned podcasts, and sync bookkeeping (dirty + changedAt) on podcasts and episodes.
         * Everything the first sync should upload is marked dirty: every podcast, and every episode
         * with listening state or published in the last 60 days (so the web player's Latest tab has them).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL("ALTER TABLE podcasts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN changedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN changedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE podcasts SET dirty = 1, changedAt = $now")
                db.execSQL(
                    "UPDATE episodes SET dirty = 1, changedAt = $now WHERE played = 1 OR positionMs > 0 OR inInbox = 1 " +
                        "OR queuePosition IS NOT NULL OR pubDate > ${now - RECENT_MS}",
                )
            }
        }

        /** Episodes published within this window are uploaded even without listening state. */
        const val RECENT_MS = 60L * 24 * 60 * 60 * 1000

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "earshot.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Upgrades must always migrate (subscriptions live here). Only a downgrade, which
                // only happens when installing an older dev build, may start from scratch.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
