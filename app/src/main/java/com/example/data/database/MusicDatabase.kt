package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedTrackEntity::class,
        ChatMessageEntity::class,
        SearchHistoryEntity::class,
        PlayHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class
    ],
    // v5: added PlaylistEntity.remoteId (stable UUID for cloud backup/restore,
    // see PlaylistCloudSync) - fallbackToDestructiveMigration below means this
    // is a clean recreate on upgrade, which is fine: local Room data here is
    // just an on-device cache, and the whole point of this column is that the
    // real backup now lives in the cloud instead.
    version = 5,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "harmonix_music_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
