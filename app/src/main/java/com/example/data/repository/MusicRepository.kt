package com.example.data.repository

import com.example.data.database.PlayHistoryDao
import com.example.data.database.PlayHistoryEntity
import com.example.data.database.PlaylistDao
import com.example.data.database.PlaylistEntity
import com.example.data.database.PlaylistTrackEntity
import com.example.data.database.SavedTrackDao
import com.example.data.database.SavedTrackEntity
import com.example.data.database.SearchHistoryDao
import com.example.data.database.SearchHistoryEntity
import com.example.data.model.Lyrics
import com.example.data.model.Track
import com.example.data.model.parseSyncedLyrics
import com.example.data.network.ITunesService
import com.example.data.network.InnerTubeMusicService
import com.example.data.network.LrcLibService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * Result of a [MusicRepository.searchTracks] call. Kept distinct from a plain
 * `emptyList()` so the UI can tell "genuinely no matches" apart from "the
 * search itself failed" (bad/missing API key, quota exceeded, no internet,
 * etc.) instead of showing "No results found" for every failure.
 */
sealed class SearchOutcome {
    data class Success(val tracks: List<Track>) : SearchOutcome()
    data class Error(val message: String) : SearchOutcome()
}

/**
 * Snapshot of what the user seems to like, derived from their search and
 * listening history. Genres/artists/queries are ordered most-preferred first
 * (frequency, then recency). Empty when there isn't enough history yet, so
 * callers can fall back to the app's generic defaults for a brand-new user.
 */
data class PersonalizationProfile(
    val topGenres: List<String>,
    val topArtists: List<String>,
    val topSearchQueries: List<String>
) {
    val isEmpty: Boolean get() = topGenres.isEmpty() && topArtists.isEmpty() && topSearchQueries.isEmpty()
}

/** UI-facing playlist summary - [PlaylistEntity] plus its live track count. */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAt: Long,
    // Stable cross-device id (see PlaylistEntity.remoteId) - used by
    // PlaylistCloudSync to back this playlist up / recognize it on restore.
    val remoteId: String
)

/** Result of a pasted-text playlist import - lets the UI show "12/15 songs matched" instead of a silent partial import. */
data class PlaylistImportResult(
    val playlistId: Long,
    val playlistName: String,
    val matchedCount: Int,
    val totalCount: Int,
    val unmatchedLines: List<String>
)

class MusicRepository(
    private val apiService: ITunesService,
    private val lrcLibService: LrcLibService,
    private val savedTrackDao: SavedTrackDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val playHistoryDao: PlayHistoryDao,
    private val playlistDao: PlaylistDao,
    // Genres/artists picked during first-launch onboarding (see
    // OnboardingScreen/OnboardingPreferences). Read once at container
    // creation time - onboarding only ever runs once, before this
    // repository is typically first used.
    private val onboardingGenres: List<String> = emptyList(),
    private val onboardingArtists: List<String> = emptyList()
) {
    // Cache for Samples feed to ensure instant load times
    private val samplesCache = mutableListOf<Track>()

    // Cache of successful Home Search results, keyed by normalized query. YouTube's
    // search.list costs 100 quota units per call out of a 10,000/day budget - so
    // re-searching something you (or another screen) already searched for in this
    // session (retyping, backspacing back to an earlier query, returning to the tab)
    // would otherwise silently burn another 100 units for zero new information.
    private val searchResultsCache = mutableMapOf<String, List<Track>>()

    // YouTube's Data API has no genre field, so every YOUTUBE-sourced Track
    // reports genre = "Music" (see YouTubeModels.toTrack()). Cache one cheap
    // iTunes title+artist lookup per track id so "same genre" autoplay has a
    // real genre to work with instead of silently degrading to "same artist".
    private val resolvedGenreCache = mutableMapOf<Long, String>()

    /**
     * Builds a [PersonalizationProfile] from the user's own search + listening
     * history (never anyone else's - this is purely local/on-device Room data).
     * Cheap enough to call on every Home/Samples load: a handful of indexed
     * GROUP BY queries over a capped window of recent rows.
     */
    private suspend fun getPersonalizationProfile(): PersonalizationProfile = withContext(Dispatchers.IO) {
        val historyGenres = playHistoryDao.getTopGenres(3).map { it.value }
        val historyArtists = playHistoryDao.getTopArtists(3).map { it.value }
        val queries = searchHistoryDao.getTopQueries(3).map { it.value }

        // Blend the user's onboarding picks in behind their real listening
        // history (deduped, history wins ties) so actual behaviour always
        // takes priority once it exists, but a brand-new user with zero
        // history still gets personalized results from their declared taste
        // instead of falling back to the generic "chill lofi" default.
        val genres = (historyGenres + onboardingGenres).distinct().take(5)
        val artists = (historyArtists + onboardingArtists).distinct().take(5)
        PersonalizationProfile(genres, artists, queries)
    }

    /** Call whenever the user runs a real (debounced, non-blank) search. */
    suspend fun recordSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext
        searchHistoryDao.insertQuery(SearchHistoryEntity(query = trimmed))
    }

    /**
     * Recent search queries, most recent first, deduped by text (a query
     * searched multiple times only shows up once, at its most recent
     * position) - powers the "recent searches" list on the Search screen.
     */
    suspend fun getRecentSearchQueries(limit: Int = 20): List<String> = withContext(Dispatchers.IO) {
        searchHistoryDao.getRecentQueries(limit * 3) // over-fetch since dedup can drop rows
            .map { it.query }
            .distinct()
            .take(limit)
    }

    /** Wipes all locally stored search history (Search screen "Clear all"). */
    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearSearchHistory()
    }

    /** Call whenever a track actually starts playing, to feed listening-history-based recommendations. */
    suspend fun recordTrackPlayed(track: Track) = withContext(Dispatchers.IO) {
        val genre = resolveGenre(track)
        playHistoryDao.insertPlay(PlayHistoryEntity.fromTrack(track, genre))
    }

    /**
     * "Keep Listening" row (Home screen) - your most recently played tracks,
     * one entry per track, newest first. Purely local, no network call.
     */
    fun getKeepListening(): Flow<List<Track>> =
        playHistoryDao.getKeepListening(15).map { rows ->
            rows.map { row ->
                val track = row.toTrack()
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
        }.flowOn(Dispatchers.IO)

    /**
     * "Forgotten Favorites" row (Home screen) - tracks you favorited but
     * haven't played in a while, so old favorites resurface instead of
     * staying buried once your listening habits move on. A favorite counts
     * as "forgotten" once it hasn't been played in [staleDays] days.
     */
    suspend fun getForgottenFavorites(staleDays: Int = 14, limit: Int = 15): List<Track> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - staleDays * 24L * 60L * 60L * 1000L
        val recentlyPlayedIds = playHistoryDao.getRecentlyPlayedTrackIds(since).toSet()
        savedTrackDao.getFavoriteTracks().first()
            .filter { it.id !in recentlyPlayedIds }
            .shuffled()
            .take(limit)
            .map { it.toTrack() }
    }

    /**
     * Samples feed - iTunes `musicVideo` entity, which (unlike the plain "song" entity)
     * provides a real ~30s *video* preview URL, not just audio. Used by the vertical
     * Samples swipe feed so each card can show a short music video instead of a static
     * image + audio-only preview.
     */
    fun getSamplesFeed(term: String = "top hit"): Flow<List<Track>> = flow {
        if (samplesCache.isNotEmpty() && term == "top hit") {
            emit(samplesCache)
        }

        // A single fixed search term against the `musicVideo` entity very often
        // resolves to just one (or zero) results that actually have a non-null
        // preview URL - iTunes' musicVideo catalog is sparse and most entries
        // lack a preview clip. That produced a Samples feed with only one page,
        // which looked like "swiping/scrolling doesn't work" (there was nothing
        // to scroll to) even though tap-to-toggle-play still worked fine on that
        // single item. Fetching several varied terms in parallel and merging the
        // results gives the pager a real multi-page feed to swipe through.
        val searchTerms = if (term == "top hit" || term == "trending hit") {
            // Personalization: put the user's own top genres/artists (from
            // listening + search history) at the front of the term pool, ahead
            // of the generic defaults, so their swipe feed skews toward what
            // they actually listen to rather than being purely generic charts.
            // Defaults are always kept too (both as a fallback for brand-new
            // users with no history yet, and to preserve variety/discovery).
            val profile = getPersonalizationProfile()
            val personalizedTerms = (
                profile.topGenres.map { "$it music video" } +
                profile.topArtists.map { "$it" } +
                profile.topSearchQueries
            ).distinct().take(5)
            val defaultTerms = listOf("pop hits", "hip hop", "dance music", "top 40", "rock hits", "trending music", "viral songs", "rnb hits")
            (personalizedTerms + defaultTerms).distinct()
        } else {
            listOf(term)
        }

        try {
            val results = coroutineScope {
                searchTerms.map { t ->
                    async {
                        try {
                            apiService.search(term = t, media = "musicVideo", entity = "musicVideo", limit = 40).results
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }.flatten()

            val tracks = results
                .filter { !it.previewUrl.isNullOrEmpty() }
                .map { it.toTrack(isVideo = true) }
                .distinctBy { it.id }
                .shuffled()

            val enrichedTracks = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }

            if (term == "top hit" || term == "trending hit") {
                samplesCache.clear()
                samplesCache.addAll(enrichedTracks)
            }
            emit(enrichedTracks)
        } catch (e: Exception) {
            e.printStackTrace()
            if (samplesCache.isNotEmpty()) {
                emit(samplesCache)
            } else {
                emit(emptyList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Home screen's default (pre-search) recommendation row. When [personalize]
     * is true (the normal Home-screen call) and the user has enough search/
     * listening history, this fans out across their top genres, top artists,
     * and top search queries instead of one fixed "chill lofi" search - so the
     * row reflects what they actually search for and listen to. Brand-new
     * users with no history yet (or an explicit [term] override) still get the
     * original single-query behaviour.
     */
    fun getHomeFeaturedTracks(term: String = "chill lofi", personalize: Boolean = true): Flow<List<Track>> = flow {
        try {
            val profile = if (personalize) getPersonalizationProfile() else PersonalizationProfile(emptyList(), emptyList(), emptyList())
            val terms = if (!profile.isEmpty) {
                (
                    profile.topGenres.map { "$it hits" } +
                    profile.topArtists.map { "$it" } +
                    profile.topSearchQueries
                ).distinct().take(5)
            } else {
                listOf(term)
            }

            val results = coroutineScope {
                terms.map { t ->
                    async {
                        try {
                            apiService.search(term = t, media = "music", entity = "song", limit = 20).results
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }.flatten()

            val tracks = results
                .filter { !it.previewUrl.isNullOrEmpty() }
                .map { it.toTrack() }
                .distinctBy { it.id }
                .let { if (terms.size > 1) it.shuffled() else it }

            val enriched = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            emit(enriched)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Home Search - queries YouTube Music's InnerTube API directly on-device
     * (see [InnerTubeMusicService]). No relay/proxy server and no API key
     * involved anymore.
     */
    fun searchTracks(query: String): Flow<SearchOutcome> = flow {
        val cacheKey = query.trim().lowercase()

        // Serve repeats from cache instead of spending another proxy call on
        // a search we already have the answer to.
        searchResultsCache[cacheKey]?.let { cached ->
            val reenriched = cached.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            emit(SearchOutcome.Success(reenriched))
            return@flow
        }

        try {
            val tracks = InnerTubeMusicService.search(query = query, limit = 25)
            val enriched = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            searchResultsCache[cacheKey] = tracks
            emit(SearchOutcome.Success(enriched))
        } catch (e: Exception) {
            e.printStackTrace()
            emit(SearchOutcome.Error(searchErrorMessage(e)))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Turns a search failure into a message that actually tells you what to do,
     * instead of silently showing "No results found" for every failure.
     */
    private fun searchErrorMessage(e: Exception): String = when (e) {
        is HttpException -> "Search failed (server error ${e.code()}). Please try again."
        is IOException -> "Search failed: check your internet connection and try again."
        else -> "Search failed. Please try again."
    }

    /** Finds the single best-matching video for a title+artist (used by Samples' "Play Full Song" button). */
    suspend fun findBestYouTubeMatch(title: String, artist: String): Track? = withContext(Dispatchers.IO) {
        try {
            // limit=1 used to mean "whatever the top hit is" - including 1hr+ mixes,
            // compilations, or full albums that share the song's title/artist. Those
            // take forever for the stream resolver to find a playable format on first
            // resolve, which looked like endless buffering client-side. Pulling more
            // candidates and filtering to a normal single-song duration avoids that.
            val candidates = InnerTubeMusicService.search(query = "$title $artist", limit = 10)
            candidates.firstOrNull { isNormalSongDuration(it) } ?: candidates.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * True if [track]'s duration looks like a single normal song rather than a
     * multi-hour mix/compilation/full-album upload (or a near-zero-length
     * junk result). Used to filter search results for both
     * [findBestYouTubeMatch] and [getAutoplayRecommendation].
     */
    private fun isNormalSongDuration(track: Track): Boolean {
        val seconds = track.durationMs / 1000L
        return seconds in 40..600
    }

    /**
     * Resolves a real genre for [track]. YouTube-sourced tracks always come in
     * with genre = "Music" (the Data API doesn't expose one), so without this,
     * "same genre" autoplay had nothing to actually match on. One cheap iTunes
     * title+artist lookup, cached per track id.
     */
    private suspend fun resolveGenre(track: Track): String {
        if (track.genre.isNotBlank() && track.genre != "Music") return track.genre
        resolvedGenreCache[track.id]?.let { return it }
        val resolved = try {
            val response = apiService.search(term = "${track.title} ${track.artist}", media = "music", entity = "song", limit = 1)
            response.results.firstOrNull()?.primaryGenreName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } ?: "Music"
        resolvedGenreCache[track.id] = resolved
        return resolved
    }

    /**
     * Strips common re-upload noise ("Official Video", "(Lyrics)", "ft. X", "4K", ...)
     * so two different uploads of the *same song* are recognised as the same song
     * rather than as two different "next" candidates.
     */
    private fun normalizedSongKey(track: Track): String {
        val noise = Regex(
            """(?i)\(.*?\)|\[.*?\]|official\s*(music\s*)?(video|audio)?|lyrics?(\s*video)?|hd|4k|full\s*(song|video)|audio|video|ft\..*|feat\..*|remaster(ed)?.*"""
        )
        fun clean(s: String) = s.replace(noise, " ")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "${clean(track.title)}|${clean(track.artist)}"
    }

    /**
     * Autoplay recommendation for when the queue naturally ends. YouTube deprecated
     * the search.list `relatedToVideoId` parameter in 2023 (no longer supported), so
     * there's no public API for "true" related-video recommendations. This searches
     * genre-first, so "next" is a genuinely different song in the same genre rather
     * than just more of the same artist/channel (the old artist-only behaviour).
     * [excludeIds] (current queue + recent play history) and [recentTracks] (the
     * same, as full Track objects) are both used - ids to skip exact repeats, and
     * the normalized title+artist of [recentTracks] to skip re-uploads of a song
     * that was already just played under a different video id.
     */
    suspend fun getAutoplayRecommendation(
        currentTrack: Track,
        excludeIds: Set<Long>,
        recentTracks: List<Track> = emptyList()
    ): Track? = withContext(Dispatchers.IO) {
        try {
            val genre = resolveGenre(currentTrack)
            val excludeKeys = (recentTracks + currentTrack).map { normalizedSongKey(it) }.toSet()

            // Genre is a hard requirement for Auto-Next, so every query below is
            // genre-qualified - "style" match is never traded away for personalization.
            // What history *does* influence is which genre-matching song gets picked:
            // if the user has a favorite artist (from search/listening history) who
            // also fits this genre, that artist's genre-matching songs are tried first,
            // ahead of the generic "$genre songs" search.
            val profile = getPersonalizationProfile()
            val favoriteArtistInGenre = profile.topArtists
                .firstOrNull { it.isNotBlank() && !it.equals(currentTrack.artist, ignoreCase = true) }

            val queries = listOfNotNull(
                favoriteArtistInGenre?.let { "$it $genre".trim() },
                "$genre songs".trim(),
                "${currentTrack.artist} $genre".trim(),
                currentTrack.artist
            ).filter { it.isNotBlank() }.distinct()

            for (query in queries) {
                val candidateTracks: List<Track> = try {
                    InnerTubeMusicService.search(query = query, limit = 15)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }

                val candidate = candidateTracks
                    .filter { it.id !in excludeIds && isNormalSongDuration(it) }
                    .firstOrNull { normalizedSongKey(it) !in excludeKeys }
                // Carry the resolved genre forward so the next hop in an autoplay
                // chain doesn't have to re-look it up and doesn't drift genre.
                if (candidate != null) return@withContext candidate.copy(genre = genre)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Fetches lyrics from LRCLIB (free, no key). Returns null if the track isn't found - no placeholder text. */
    suspend fun getLyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val results = lrcLibService.search(trackName = track.title, artistName = track.artist)
            val best = results.firstOrNull { !it.instrumental.let { inst -> inst == true } } ?: return@withContext null
            val synced = parseSyncedLyrics(best.syncedLyrics)
            val lyrics = Lyrics(plain = best.plainLyrics, synced = synced)
            if (lyrics.isAvailable) lyrics else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSavedTracks(): Flow<List<Track>> {
        return savedTrackDao.getAllSavedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getDownloadedTracks(): Flow<List<Track>> {
        return savedTrackDao.getDownloadedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getFavoriteTracks(): Flow<List<Track>> {
        return savedTrackDao.getFavoriteTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    suspend fun toggleFavorite(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isFavorite = !existing.isFavorite)
            if (!updated.isFavorite && !updated.isDownloaded) {
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isFavorite = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun toggleDownload(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isDownloaded = !existing.isDownloaded)
            if (!updated.isFavorite && !updated.isDownloaded) {
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isDownloaded = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun isTrackFavorite(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isFavorite ?: false
    }

    suspend fun isTrackDownloaded(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isDownloaded ?: false
    }

    // ---- Playlists: create, import, play ----

    /** Live list of the user's playlists with track counts, newest first. */
    fun getPlaylists(): Flow<List<Playlist>> =
        combine(playlistDao.getAllPlaylists(), playlistDao.getTrackCounts()) { playlists, counts ->
            val countMap = counts.associate { it.playlistId to it.count }
            playlists.map { p ->
                Playlist(id = p.id, name = p.name, trackCount = countMap[p.id] ?: 0, createdAt = p.createdAt, remoteId = p.remoteId)
            }
        }

    /** Tracks in a playlist, in the order they were added. */
    fun getPlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.getTracksForPlaylist(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim().ifBlank { "New Playlist" }
        playlistDao.insertPlaylist(PlaylistEntity(name = trimmed))
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) playlistDao.renamePlaylist(playlistId, trimmed)
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylistTrack(PlaylistTrackEntity.fromTrack(playlistId, track))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistTrack(playlistId, trackId)
    }

    suspend fun isTrackInPlaylist(playlistId: Long, trackId: Long): Boolean = withContext(Dispatchers.IO) {
        playlistDao.isTrackInPlaylist(playlistId, trackId)
    }

    // ---- One-shot snapshots + restore-insert, used only by PlaylistCloudSync ----
    // (kept separate from the live Flow-based methods above: cloud sync just
    // needs a point-in-time read/write, not a running subscription).

    /** Every local playlist right now, with its tracks already loaded - used to upload backups and to diff against the cloud on login. */
    suspend fun getPlaylistsSnapshot(): List<Pair<Playlist, List<Track>>> = withContext(Dispatchers.IO) {
        val entities = playlistDao.getAllPlaylistsOnce()
        entities.map { p ->
            val tracks = playlistDao.getTracksForPlaylistOnce(p.id).map { it.toTrack() }
            Playlist(id = p.id, name = p.name, trackCount = tracks.size, createdAt = p.createdAt, remoteId = p.remoteId) to tracks
        }
    }

    /** True if a playlist with this [remoteId] already exists locally (used to skip re-importing something already restored/created on this device). */
    suspend fun hasPlaylistWithRemoteId(remoteId: String): Boolean = withContext(Dispatchers.IO) {
        playlistDao.getPlaylistByRemoteId(remoteId) != null
    }

    /** The stable cloud [PlaylistEntity.remoteId] for a given local playlist id - used to key cloud-sync calls. */
    suspend fun getRemoteId(playlistId: Long): String? = withContext(Dispatchers.IO) {
        playlistDao.getPlaylistById(playlistId)?.remoteId
    }

    /**
     * Inserts a playlist coming FROM the cloud (during restore), preserving its
     * original [remoteId] and [createdAt] so it doesn't look like a
     * brand-new/duplicate playlist and re-upload itself right back to the
     * cloud on the next sync pass.
     */
    suspend fun restorePlaylistFromCloud(
        remoteId: String,
        name: String,
        createdAt: Long,
        tracks: List<Track>
    ): Long = withContext(Dispatchers.IO) {
        val existing = playlistDao.getPlaylistByRemoteId(remoteId)
        val playlistId = existing?.id ?: playlistDao.insertPlaylist(
            PlaylistEntity(name = name, createdAt = createdAt, remoteId = remoteId)
        )
        tracks.forEach { track ->
            playlistDao.insertPlaylistTrack(PlaylistTrackEntity.fromTrack(playlistId, track))
        }
        playlistId
    }

    /**
     * Imports a playlist from pasted text - one song per line, e.g.
     * "Blinding Lights - The Weeknd" (also tolerates plain M3U files: '#'
     * comment lines and blank lines are skipped). Each line is resolved
     * against the iTunes song catalog with a single best-match lookup, same
     * approach as [findBestYouTubeMatch]/[resolveGenre]. Lines that don't
     * match anything are reported back instead of silently dropped, so the
     * user knows their import was partial rather than assuming it was complete.
     */
    suspend fun importPlaylist(name: String, rawText: String): PlaylistImportResult = withContext(Dispatchers.IO) {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()

        val playlistName = name.trim().ifBlank { "Imported Playlist" }
        val playlistId = playlistDao.insertPlaylist(PlaylistEntity(name = playlistName))

        val unmatched = mutableListOf<String>()
        var matched = 0

        for (line in lines) {
            try {
                val response = apiService.search(term = line, media = "music", entity = "song", limit = 1)
                val track = response.results.firstOrNull()?.toTrack()
                if (track != null && !track.previewUrl.isNullOrEmpty()) {
                    playlistDao.insertPlaylistTrack(PlaylistTrackEntity.fromTrack(playlistId, track))
                    matched++
                } else {
                    unmatched.add(line)
                }
            } catch (e: Exception) {
                unmatched.add(line)
            }
        }

        PlaylistImportResult(
            playlistId = playlistId,
            playlistName = playlistName,
            matchedCount = matched,
            totalCount = lines.size,
            unmatchedLines = unmatched
        )
    }
}
