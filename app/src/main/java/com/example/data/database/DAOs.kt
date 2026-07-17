package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedTrackDao {
    @Query("SELECT * FROM saved_tracks ORDER BY addedAt DESC")
    fun getAllSavedTracks(): Flow<List<SavedTrackEntity>>

    @Query("SELECT * FROM saved_tracks WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun getFavoriteTracks(): Flow<List<SavedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedTrack(track: SavedTrackEntity)

    @Query("DELETE FROM saved_tracks WHERE id = :id")
    suspend fun deleteSavedTrackById(id: Long)

    @Query("SELECT * FROM saved_tracks WHERE id = :id LIMIT 1")
    suspend fun getSavedTrackById(id: Long): SavedTrackEntity?
}

/** Row shape for "top N distinct values by frequency, most recent first as tiebreak" aggregate queries. */
data class ValueCount(val value: String, val count: Int)

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentQueries(limit: Int = 50): List<SearchHistoryEntity>

    // Most-searched terms, ranked by frequency then recency. Capped to the
    // last 200 searches so old, no-longer-relevant interests fade out over time.
    @Query(
        """
        SELECT query AS value, COUNT(*) AS count FROM (
            SELECT query, timestamp FROM search_history ORDER BY timestamp DESC LIMIT 200
        )
        GROUP BY query
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopQueries(limit: Int = 5): List<ValueCount>

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()
}

@Dao
interface PlayHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlay(entry: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPlays(limit: Int = 50): List<PlayHistoryEntity>

    // "Keep Listening" row: most recently played track *per distinct trackId*
    // (so replaying a song bumps it to the front instead of listing every
    // play as a separate row), capped to a wider recent window before
    // dedup'ing so older repeats don't crowd out genuinely new tracks.
    @Query(
        """
        SELECT * FROM play_history WHERE id IN (
            SELECT MAX(id) FROM (
                SELECT id, trackId FROM play_history ORDER BY timestamp DESC LIMIT 200
            ) GROUP BY trackId
        )
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun getKeepListening(limit: Int = 15): Flow<List<PlayHistoryEntity>>

    // Distinct trackIds played since [sinceTimestamp] - used to figure out
    // which favorites have gone "forgotten" (see MusicRepository.getForgottenFavorites).
    @Query("SELECT DISTINCT trackId FROM play_history WHERE timestamp > :sinceTimestamp")
    suspend fun getRecentlyPlayedTrackIds(sinceTimestamp: Long): List<Long>

    // Top genres by listen frequency, capped to the last 300 plays so taste
    // can drift over time instead of being locked in by old listening habits.
    @Query(
        """
        SELECT genre AS value, COUNT(*) AS count FROM (
            SELECT genre, timestamp FROM play_history WHERE genre != 'Music' ORDER BY timestamp DESC LIMIT 300
        )
        GROUP BY genre
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopGenres(limit: Int = 5): List<ValueCount>

    @Query(
        """
        SELECT artist AS value, COUNT(*) AS count FROM (
            SELECT artist, timestamp FROM play_history ORDER BY timestamp DESC LIMIT 300
        )
        GROUP BY artist
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtists(limit: Int = 5): List<ValueCount>

    @Query("DELETE FROM play_history")
    suspend fun clearPlayHistory()
}

/** Row shape for "how many tracks does each playlist have" aggregate query. */
data class PlaylistTrackCount(val playlistId: Long, val count: Int)

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    // One-shot (non-Flow) read used by PlaylistCloudSync to diff local vs
    // cloud playlists on login/restore, without keeping a live collector open.
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getPlaylistByRemoteId(remoteId: String): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("SELECT playlistId, COUNT(*) AS count FROM playlist_tracks GROUP BY playlistId")
    fun getTrackCounts(): Flow<List<PlaylistTrackCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrack(track: PlaylistTrackEntity)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun getTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    // One-shot variant used by PlaylistCloudSync when uploading/restoring
    // (doesn't need a live subscription, just a snapshot at sync time).
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun getTracksForPlaylistOnce(playlistId: Long): List<PlaylistTrackEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId)")
    suspend fun isTrackInPlaylist(playlistId: Long, trackId: Long): Boolean

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deletePlaylistTrack(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteAllTracksForPlaylist(playlistId: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    // Room (Kotlin default-method) transaction: deleting a playlist must also
    // delete its tracks, or orphaned playlist_tracks rows would pile up and
    // (if a new playlist ever reused the same id) resurface as "ghost" tracks.
    @Transaction
    suspend fun deletePlaylist(id: Long) {
        deleteAllTracksForPlaylist(id)
        deletePlaylistRow(id)
    }
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE jamId = :jamId ORDER BY timestamp ASC")
    fun getMessagesForJam(jamId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE jamId = :jamId")
    suspend fun clearMessagesForJam(jamId: String)
}
