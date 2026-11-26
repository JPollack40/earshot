package com.earshot.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Podcast::class, Episode::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "earshot.db")
                // Fine during early development. Real migrations start once sync (phase 5) holds the source of truth.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
