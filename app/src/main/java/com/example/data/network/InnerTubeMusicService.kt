package com.example.data.network

import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
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

    /**
     * Pulls YouTube Music's real "Trending" chart (FEmusic_charts) and returns
     * the current #1 track, so the Home banner reflects what's actually
     * trending right now instead of a static/hardcoded pick. Falls back to the
     * "Top" chart section if a dedicated Trending section isn't present for
     * the user's locale that day.
     */
    suspend fun getTrendingTrack(): Track? {
        ensureLocale()
        val charts = YouTube.getChartsPage().getOrNull() ?: return null
        val section = charts.sections.firstOrNull { it.chartType == com.metrolist.innertube.pages.ChartsPage.ChartType.TRENDING }
            ?: charts.sections.firstOrNull { it.chartType == com.metrolist.innertube.pages.ChartsPage.ChartType.TOP }
            ?: return null
        val top = section.items
            .filterIsInstance<SongItem>()
            .minByOrNull { it.chartPosition ?: Int.MAX_VALUE }
            ?: return null
        return top.toTrack()
    }

    /**
     * Real genre/mood list from YouTube Music's own "Moods & Genres" page
     * (FEmusic_moods_and_genres), instead of a dozen hardcoded strings. Used
     * to populate the onboarding genre picker with whatever YT Music itself
     * is currently organized around (usually 30-40+ entries: Pop, Hip-Hop,
     * Chill, Workout, Sleep, Bollywood, Afrobeats, K-Pop, etc., grouped by
     * the "For you" / "Energy" / "Mood" / "Genre" sections it returns).
     */
    suspend fun getGenres(): List<String> {
        ensureLocale()
        val sections = YouTube.moodAndGenres().getOrThrow()
        return sections
            .flatMap { it.items }
            .map { it.title }
            .distinct()
    }

    /**
     * YouTube Music's real "radio"/Up-Next queue for a song (the same feature behind the
     * app's own Autoplay/Radio button, and behind YT Music's "Start radio") - a
     * server-curated continuous queue, not a keyword search. This is what
     * MusicRepository.getAutoplayRecommendation() now tries FIRST for auto-next, because a
     * generic "$genre songs" search keeps returning the same ~15 videos for the same query
     * every time, which runs out fast once a few get excluded as already-played. The radio
     * queue is both bigger per call (it already resolves YouTube's own "automix"/continuation
     * chaining server-side) and, unlike a keyword search, actually meant to be endless.
     * Returns the queue items plus a continuation token so a caller can pull further pages
     * from the same radio if every item in this page is filtered out as already played.
     */
    suspend fun getRadio(videoId: String, continuation: String? = null): Pair<List<Track>, String?> {
        ensureLocale()
        val endpoint = WatchEndpoint(videoId = videoId, playlistId = "RD$videoId")
        val result = YouTube.next(endpoint, continuation).getOrThrow()
        return result.items.map { it.toTrack() } to result.continuation
    }

    /**
     * Real artist suggestions from YouTube Music search (FILTER_ARTIST),
     * seeded by the genres the user picked in onboarding - instead of a
     * fixed list of 16 artists. Searches each selected genre name as a query
     * (e.g. "Hip-Hop", "Bollywood") and collects the top artist results for
     * each, deduped, up to [maxTotal]. This mirrors real YT Music results
     * rather than a curated static list, so it scales to any genre/locale.
     */
    suspend fun getArtistsForGenres(
        genres: List<String>,
        perGenre: Int = 10,
        maxTotal: Int = 60
    ): List<String> {
        ensureLocale()
        if (genres.isEmpty()) return emptyList()
        val names = LinkedHashSet<String>()
        for (genre in genres) {
            if (names.size >= maxTotal) break
            val result = YouTube.search(genre, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                ?: continue
            result.items
                .filterIsInstance<ArtistItem>()
                .take(perGenre)
                .forEach { names.add(it.title) }
        }
        return names.toList()
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
