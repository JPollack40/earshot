package com.earshot.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Podcast::class, Episode::class], version = 2, exportSchema = false)
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "earshot.db")
                .addMigrations(MIGRATION_1_2)
                // Upgrades must always migrate (subscriptions live here). Only a downgrade, which
                // only happens when installing an older dev build, may start from scratch.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
