package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Track
import com.example.data.model.TrackSource

@Entity(tableName = "saved_tracks")
data class SavedTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val previewUrl: String,
    val artworkUrl: String,
    val durationMs: Long,
    val genre: String,
    val isFavorite: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
    // Persisted so a favorited YouTube full-song still plays correctly
    // (not just its iTunes-preview counterpart) when reopened from Library.
    val source: String = "ITUNES",
    val youtubeVideoId: String? = null,
    val isVideo: Boolean = false
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        isFavorite = isFavorite,
        source = if (source == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    companion object {
        fun fromTrack(track: Track, isFavorite: Boolean = track.isFavorite) = SavedTrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            previewUrl = track.previewUrl,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            genre = track.genre,
            isFavorite = isFavorite,
            source = track.source.name,
            youtubeVideoId = track.youtubeVideoId,
            isVideo = track.isVideo
        )
    }
}

/**
 * One row per search the user actually ran (post-debounce, non-blank query).
 * Powers the "search history" half of personalization: recent/frequent query
 * text is fed back in as extra search terms for the Home feed.
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * One row per track the user actually listened to (play started), with the
 * *resolved* genre (never the YouTube-default "Music" placeholder) so genre
 * affinity can be computed directly with SQL GROUP BY instead of re-resolving
 * genres for every history row on every read.
 *
 * Also carries a full track snapshot (album/preview/artwork/etc, same idea as
 * [PlaylistTrackEntity]) so the "Keep Listening" row on Home can render and
 * replay a track directly from history without an extra DB join or network
 * call - added fields default to "" / null / false so this stays additive
 * for any pre-existing rows (harmless with fallbackToDestructiveMigration).
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val title: String,
    val artist: String,
    val genre: String,
    val timestamp: Long = System.currentTimeMillis(),
    val album: String = "",
    val previewUrl: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0,
    val source: String = "ITUNES",
    val youtubeVideoId: String? = null,
    val isVideo: Boolean = false
) {
    fun toTrack(): Track = Track(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        source = if (source == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    companion object {
        fun fromTrack(track: Track, genre: String) = PlayHistoryEntity(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            genre = genre,
            album = track.album,
            previewUrl = track.previewUrl,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            source = track.source.name,
            youtubeVideoId = track.youtubeVideoId,
            isVideo = track.isVideo
        )
    }
}

/**
 * A user-created playlist. Tracks live in [PlaylistTrackEntity], keyed by
 * (playlistId, trackId) - so a playlist owns its own snapshot of each track's
 * fields and doesn't break if the track is later removed from Favorites.
 *
 * [remoteId] is a stable, device-independent key (random UUID, generated once
 * at creation) used purely for cloud backup/restore (see PlaylistCloudSync) -
 * unlike [id] (Room's local autoIncrement key, which restarts from 1 on every
 * fresh install and can't be trusted to mean "the same playlist" across
 * devices), [remoteId] stays the same for a given playlist forever, so a
 * restored playlist can be recognized as "already have this one" instead of
 * being re-imported as a duplicate every time the app is reinstalled.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val remoteId: String = java.util.UUID.randomUUID().toString()
)

/** One track inside one playlist. Composite key so a track can't be added twice to the same playlist. */
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val previewUrl: String,
    val artworkUrl: String,
    val durationMs: Long,
    val genre: String,
    val source: String = "ITUNES",
    val youtubeVideoId: String? = null,
    val isVideo: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toTrack(): Track = Track(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        source = if (source == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    companion object {
        fun fromTrack(playlistId: Long, track: Track) = PlaylistTrackEntity(
            playlistId = playlistId,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            previewUrl = track.previewUrl,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            genre = track.genre,
            source = track.source.name,
            youtubeVideoId = track.youtubeVideoId,
            isVideo = track.isVideo
        )
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val jamId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String,
    val text: String,
    val timestamp: Long,
    val replyToId: String?,
    val replyToText: String?,
    val replyToSenderName: String?,
    val reactionsJson: String, // JSON serialization of reactions
    val status: String // "SENT", "DELIVERED", "READ"
)
