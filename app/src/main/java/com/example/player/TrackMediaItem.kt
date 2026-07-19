package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.data.model.Track
import com.example.data.model.TrackSource

/**
 * MetroList-style playback needs every item ExoPlayer/MediaSession knows about to be a real,
 * independently playable [MediaItem] in the player's own timeline (that's what makes native
 * Next/Previous/seek/auto-advance/queue and the system media notification all work correctly -
 * see MusicService's doc comment for why the old single-item approach couldn't do this).
 *
 * Since this app has no local song database keyed by media id (Metrolist resolves extra track
 * info from its Room database instead), every field MusicPlayer/NowPlayingScreen/Jam needs is
 * embedded directly in the MediaItem's [MediaMetadata] extras bundle - so a [Track] round-trips
 * through the player's real playlist with zero loss, and MusicPlayer can rebuild its whole
 * `queue`/`currentTrack` state straight from the connected MediaController's timeline.
 *
 * mediaId / customCacheKey scheme (also used by MusicService.createDataSourceFactory() to know
 * how to resolve+cache each item):
 *   - YouTube track: "yt:<youtubeVideoId>"
 *   - iTunes track:  "it:<track.id>"
 */

private const val KEY_ID = "id"
private const val KEY_TITLE = "title"
private const val KEY_ARTIST = "artist"
private const val KEY_ALBUM = "album"
private const val KEY_PREVIEW_URL = "previewUrl"
private const val KEY_ARTWORK_URL = "artworkUrl"
private const val KEY_DURATION_MS = "durationMs"
private const val KEY_GENRE = "genre"
private const val KEY_IS_FAVORITE = "isFavorite"
private const val KEY_SOURCE = "source"
private const val KEY_YOUTUBE_VIDEO_ID = "youtubeVideoId"
private const val KEY_IS_VIDEO = "isVideo"

fun Track.toMediaId(): String =
    if (source == TrackSource.YOUTUBE && !youtubeVideoId.isNullOrBlank()) {
        "yt:$youtubeVideoId"
    } else {
        "it:$id"
    }

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putLong(KEY_ID, id)
        putString(KEY_TITLE, title)
        putString(KEY_ARTIST, artist)
        putString(KEY_ALBUM, album)
        putString(KEY_PREVIEW_URL, previewUrl)
        putString(KEY_ARTWORK_URL, artworkUrl)
        putLong(KEY_DURATION_MS, durationMs)
        putString(KEY_GENRE, genre)
        putBoolean(KEY_IS_FAVORITE, isFavorite)
        putString(KEY_SOURCE, source.name)
        putString(KEY_YOUTUBE_VIDEO_ID, youtubeVideoId)
        putBoolean(KEY_IS_VIDEO, isVideo)
    }

    val metadataBuilder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.ifBlank { "Unknown Artist" })
        .setExtras(extras)
    if (!artworkUrl.isNullOrBlank()) {
        runCatching { Uri.parse(artworkUrl) }.getOrNull()?.let { metadataBuilder.setArtworkUri(it) }
    }
    if (durationMs > 0) {
        metadataBuilder.setDurationMs(durationMs)
    }

    val mediaId = toMediaId()
    // Real, non-empty uri is required by MediaItem/ExoPlayer even though the resolving data
    // source (MusicService.createDataSourceFactory) always decides the actual bytes source at
    // load time - for YouTube this is only ever a placeholder key, never fetched directly.
    val uri = if (source == TrackSource.YOUTUBE) {
        "https://music.youtube.com/watch?v=$youtubeVideoId"
    } else {
        previewUrl
    }

    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(mediaId)
        .setCustomCacheKey(mediaId)
        .setMediaMetadata(metadataBuilder.build())
        .build()
}

/** Reconstructs the original [Track] from a MediaItem built by [toMediaItem], or null if this
 *  MediaItem wasn't one of ours (e.g. came from a generic external controller). */
fun MediaItem.toTrackOrNull(): Track? {
    val extras = mediaMetadata.extras ?: return null
    if (!extras.containsKey(KEY_ID)) return null
    val source = runCatching {
        TrackSource.valueOf(extras.getString(KEY_SOURCE) ?: TrackSource.ITUNES.name)
    }.getOrDefault(TrackSource.ITUNES)
    return Track(
        id = extras.getLong(KEY_ID),
        title = extras.getString(KEY_TITLE) ?: mediaMetadata.title?.toString().orEmpty(),
        artist = extras.getString(KEY_ARTIST) ?: mediaMetadata.artist?.toString().orEmpty(),
        album = extras.getString(KEY_ALBUM).orEmpty(),
        previewUrl = extras.getString(KEY_PREVIEW_URL).orEmpty(),
        artworkUrl = extras.getString(KEY_ARTWORK_URL).orEmpty(),
        durationMs = extras.getLong(KEY_DURATION_MS),
        genre = extras.getString(KEY_GENRE).orEmpty(),
        isFavorite = extras.getBoolean(KEY_IS_FAVORITE),
        source = source,
        youtubeVideoId = extras.getString(KEY_YOUTUBE_VIDEO_ID),
        isVideo = extras.getBoolean(KEY_IS_VIDEO)
    )
}

/** Extracts the "yt:"/"it:" media-id scheme documented above out of a raw id/key string. */
object MediaIdScheme {
    const val YOUTUBE_PREFIX = "yt:"
    const val ITUNES_PREFIX = "it:"

    fun youtubeVideoIdOrNull(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(YOUTUBE_PREFIX) }?.removePrefix(YOUTUBE_PREFIX)
}
