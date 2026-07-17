package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ITunesSearchResponse(
    @Json(name = "resultCount") val resultCount: Int,
    @Json(name = "results") val results: List<ITunesTrack>
)

@JsonClass(generateAdapter = true)
data class ITunesTrack(
    @Json(name = "trackId") val trackId: Long?,
    @Json(name = "trackName") val trackName: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "collectionName") val collectionName: String?,
    @Json(name = "previewUrl") val previewUrl: String?,
    @Json(name = "artworkUrl100") val artworkUrl100: String?,
    @Json(name = "trackTimeMillis") val trackTimeMillis: Long?,
    @Json(name = "primaryGenreName") val primaryGenreName: String?
) {
    fun toTrack(isVideo: Boolean = false): Track {
        return Track(
            id = trackId ?: 0L,
            title = trackName ?: "Unknown Title",
            artist = artistName ?: "Unknown Artist",
            album = collectionName ?: "Unknown Album",
            previewUrl = previewUrl ?: "",
            artworkUrl = artworkUrl100 ?: "",
            durationMs = trackTimeMillis ?: 30000L,
            genre = primaryGenreName ?: "Music",
            source = TrackSource.ITUNES,
            isVideo = isVideo
        )
    }
}

enum class TrackSource { ITUNES, YOUTUBE }

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val previewUrl: String,
    val artworkUrl: String,
    val durationMs: Long,
    val genre: String,
    val isFavorite: Boolean = false,
    // Source-awareness added for YouTube full-song playback + iTunes video Samples:
    val source: TrackSource = TrackSource.ITUNES,
    // Populated for YOUTUBE-source tracks; MusicPlayer loads this id into the mini video player.
    val youtubeVideoId: String? = null,
    // True for iTunes musicVideo preview results shown in Samples (previewUrl is a video, not audio).
    val isVideo: Boolean = false
)
