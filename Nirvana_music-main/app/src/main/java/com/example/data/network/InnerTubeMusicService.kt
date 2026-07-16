package com.example.data.network

import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.YouTubeLocale
import java.util.Locale

/**
 * Local replacement for the old relay/render backend and the (already-unused)
 * YouTube Data API. Talks directly to YouTube Music's own internal ("InnerTube")
 * API from on-device - the same client Metrolist Music uses - so there's no
 * more per-device relay server, no server-side quota, and no separate API key
 * to manage or have go down.
 */
object InnerTubeMusicService {

    @Volatile
    private var localeInitialized = false

    /** Sets InnerTube's locale once, lazily, from the device's own locale. */
    private fun ensureLocale() {
        if (localeInitialized) return
        synchronized(this) {
            if (localeInitialized) return
            val locale = Locale.getDefault()
            YouTube.locale = YouTubeLocale(
                gl = locale.country.ifBlank { "US" },
                hl = locale.language.ifBlank { "en" }
            )
            localeInitialized = true
        }
    }

    /**
     * Searches YouTube Music for songs matching [query]. Mirrors the old
     * RelayService.search() shape so callers (MusicRepository) barely change.
     */
    suspend fun search(query: String, limit: Int = 25): List<Track> {
        ensureLocale()
        val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        return result.items
            .filterIsInstance<SongItem>()
            .take(limit)
            .map { it.toTrack() }
    }
}

/** Converts an InnerTube [SongItem] search result into the app's unified [Track] model. */
fun SongItem.toTrack(): Track = Track(
    id = id.hashCode().toLong(),
    title = title,
    artist = artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
    album = album?.name ?: "YouTube",
    previewUrl = "",
    artworkUrl = thumbnail,
    durationMs = (duration ?: 0) * 1000L,
    // InnerTube search results don't expose a genre either - same limitation
    // the old relay/Data API path had (see MusicRepository.resolveGenre()).
    genre = "Music",
    source = TrackSource.YOUTUBE,
    youtubeVideoId = id
)
