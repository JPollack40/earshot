package com.earshot.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Podcast::class, Episode::class, Transcript::class, Bookmark::class, Playlist::class, Listen::class],
    version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao
    abstract fun transcripts(): TranscriptDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun playlists(): PlaylistDao
    abstract fun listens(): ListenDao

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

        /** v6: saved transcripts and bookmarks (new tables; nothing existing changes). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transcripts` (`episodeId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`timed` INTEGER NOT NULL DEFAULT 1, `segmentsJson` TEXT NOT NULL, `plain` TEXT NOT NULL, " +
                        "`dirty` INTEGER NOT NULL DEFAULT 0, `changedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`episodeId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `episodeId` TEXT NOT NULL, " +
                        "`feedUrl` TEXT NOT NULL, `episodeTitle` TEXT NOT NULL, `podcastTitle` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, `audioUrl` TEXT, `positionMs` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 1, " +
                        "`changedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_episodeId` ON `bookmarks` (`episodeId`)")
            }
        }

        /**
         * v7 (phase 7): automation rules on podcasts (all nullable, so nothing changes until set),
         * plus new tables playlists and listens. No existing data is touched.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN newEpisodeAction TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN includeWords TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN excludeWords TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN minMinutes INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN maxMinutes INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN inboxDays INTEGER")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN inboxKeep INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, `filtersJson` TEXT NOT NULL, " +
                        "`deleted` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 1, " +
                        "`changedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listens` (`id` TEXT NOT NULL, `episodeId` TEXT NOT NULL, " +
                        "`feedUrl` TEXT NOT NULL, `podcastTitle` TEXT NOT NULL, `episodeTitle` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, " +
                        "`fromMs` INTEGER NOT NULL, `toMs` INTEGER NOT NULL, `speed` REAL NOT NULL, " +
                        "`device` TEXT NOT NULL, `dirty` INTEGER NOT NULL DEFAULT 1, " +
                        "`changedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listens_startedAt` ON `listens` (`startedAt`)")
            }
        }

        /**
         * v8 (phase 10): Podcasting 2.0 extras (funding, people, finished shows, season and episode
         * numbers), the feed's stated length, skipped chapters, and the per-podcast ad trim. All new
         * columns are nullable or default to 0, so nothing existing changes.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingUrl TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN fundingText TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN personsJson TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN complete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN adTrim TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN season INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN episodeNo REAL")
                db.execSQL("ALTER TABLE episodes ADD COLUMN personsJson TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN feedDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN skippedChapters TEXT")
            }
        }

        /** Episodes published within this window are uploaded even without listening state. */
        const val RECENT_MS = 60L * 24 * 60 * 60 * 1000

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "earshot.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                // Upgrades must always migrate (subscriptions live here). Only a downgrade, which
                // only happens when installing an older dev build, may start from scratch.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
