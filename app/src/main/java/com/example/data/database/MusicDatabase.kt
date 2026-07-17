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
    // v6: PlayHistoryEntity now carries a full track snapshot (album/preview/
    // artwork/etc) instead of just title/artist/genre, so the Home screen's
    // "Keep Listening" row can render straight from history. Destructive
    // migration is fine here too - same reasoning as v5 above.
    version = 6,
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
