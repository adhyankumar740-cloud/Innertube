package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Lyrics
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchOutcome
import com.example.player.MusicPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel(
    private val repository: MusicRepository,
    val player: MusicPlayer
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    // Set only when a search actually FAILS (bad API key, quota, no internet),
    // as opposed to a search that succeeded but genuinely had zero matches.
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()

    // Emits the current query text when the user taps "Retry" after a failed
    // search. Needed because _searchQuery below is distinctUntilChanged'd (so
    // typing the same text again doesn't re-fire the search) - without this,
    // retrying a failed search required deleting a character and retyping it
    // just to make the query "change" again. extraBufferCapacity = 1 so
    // emit() never suspends/gets dropped if retrySearch() is called from a
    // non-suspend context (a button's onClick).
    private val _retrySearchTrigger = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val _homeTracks = MutableStateFlow<List<Track>>(emptyList())
    val homeTracks = _homeTracks.asStateFlow()

    // Recent search queries (most recent first, deduped) - shown on the
    // Search screen below the search box whenever the query box is empty.
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory = _searchHistory.asStateFlow()

    // "Keep Listening" - live, always reflects your latest plays (no manual refresh needed).
    val keepListening: StateFlow<List<Track>> = repository.getKeepListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // "Forgotten Favorites" - one-shot per Home load (shuffled pick, not a live query -
    // re-shuffling on every favorite/play would make the row jump around while browsing).
    private val _forgottenFavorites = MutableStateFlow<List<Track>>(emptyList())
    val forgottenFavorites = _forgottenFavorites.asStateFlow()

    private val _selectedTab = MutableStateFlow("home")
    val selectedTab = _selectedTab.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    // Observe Saved/Downloaded/Favorite Tracks from database
    val favoriteTracks: StateFlow<List<Track>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<Track>> = repository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryTracks: StateFlow<List<Track>> = repository.getSavedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Load initial home screen content
        fetchHomeRecommendations()
        loadSearchHistory()

        // Autoplay: YouTube's relatedToVideoId was deprecated in 2023, so this is
        // an artist/genre-based approximation rather than a true "related videos" call.
        player.autoplayProvider = { current, excludeIds, recentTracks ->
            repository.getAutoplayRecommendation(current, excludeIds, recentTracks)
        }

        // Fetch lyrics (LRCLIB, free/no-key) whenever the playing track changes.
        viewModelScope.launch {
            player.currentTrack
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track == null) {
                        _lyrics.value = null
                        return@collectLatest
                    }
                    _isLoadingLyrics.value = true
                    _lyrics.value = repository.getLyrics(track)
                    _isLoadingLyrics.value = false
                }
        }

        // Listening history: log every track that actually starts playing (own
        // launch, separate from the lyrics collector, so a slow/failed lyrics
        // fetch never delays or skips recording play history). This is what
        // powers the "listening history" half of Home/Samples/Auto-Next
        // personalization.
        viewModelScope.launch {
            player.currentTrack
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track != null) {
                        repository.recordTrackPlayed(track)
                    }
                }
        }

        // Home Search used to fire a full YouTube API call (search = 100 quota
        // units, on a free 10,000-unit/day project) on EVERY keystroke, with no
        // debounce and no cancellation of in-flight calls - so typing a single
        // 8-letter query alone could burn ~800+ units, exhausting the daily
        // quota almost instantly even without "really" using search much.
        // Fix: wait for the user to pause typing, and collectLatest cancels
        // any still-in-flight request the moment a newer query comes in.
        //
        // Merged with _retrySearchTrigger (no debounce, no distinctUntilChanged)
        // so tapping "Retry" after a failure re-runs the same query immediately,
        // instead of silently doing nothing because the text didn't change.
        viewModelScope.launch {
            merge(
                _searchQuery.debounce(400).distinctUntilChanged(),
                _retrySearchTrigger
            ).collectLatest { query ->
                if (query.trim().isEmpty()) {
                    _searchResults.value = emptyList()
                    _searchError.value = null
                    _isSearching.value = false
                    return@collectLatest
                }
                _isSearching.value = true
                // Log search history for personalization. Fire-and-forget on
                // the same scope: this must never block/slow the actual search.
                launch {
                    repository.recordSearchQuery(query)
                    loadSearchHistory()
                }
                repository.searchTracks(query).collectLatest { outcome ->
                    when (outcome) {
                        is SearchOutcome.Success -> {
                            _searchResults.value = outcome.tracks
                            _searchError.value = null
                        }
                        is SearchOutcome.Error -> {
                            _searchResults.value = emptyList()
                            _searchError.value = outcome.message
                        }
                    }
                }
                _isSearching.value = false
            }
        }
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    fun fetchHomeRecommendations() {
        viewModelScope.launch {
            repository.getHomeFeaturedTracks("chill lofi").collectLatest { tracks ->
                if (tracks.isNotEmpty()) {
                    _homeTracks.value = tracks
                } else {
                    // Fallback mock tracks if network offline
                    _homeTracks.value = getLocalFallbackTracks()
                }
            }
        }
        viewModelScope.launch {
            _forgottenFavorites.value = repository.getForgottenFavorites()
        }
    }

    /** Called on every keystroke - just updates state. The actual (debounced) API call happens in init{}. */
    fun search(query: String) {
        _searchQuery.value = query
    }

    /**
     * Re-runs the search for whatever query is currently shown, without
     * requiring the user to change the text first. Meant for a "Retry" action
     * shown alongside [searchError] - a no-op if the query box is empty.
     */
    fun retrySearch() {
        val query = _searchQuery.value
        if (query.trim().isNotEmpty()) {
            _retrySearchTrigger.tryEmit(query)
        }
    }

    /** Refreshes [searchHistory] from local storage. */
    fun loadSearchHistory() {
        viewModelScope.launch {
            _searchHistory.value = repository.getRecentSearchQueries(20)
        }
    }

    /** "Clear all" on the Search screen's recent-searches list. */
    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
            _searchHistory.value = emptyList()
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
            // Re-enrich search results and home tracks with local updates
            updateTrackStates()
        }
    }

    fun toggleDownload(track: Track) {
        viewModelScope.launch {
            repository.toggleDownload(track)
            updateTrackStates()
        }
    }

    private suspend fun updateTrackStates() {
        _homeTracks.value = _homeTracks.value.map { track ->
            track.copy(
                isFavorite = repository.isTrackFavorite(track.id),
                isDownloaded = repository.isTrackDownloaded(track.id)
            )
        }
        _searchResults.value = _searchResults.value.map { track ->
            track.copy(
                isFavorite = repository.isTrackFavorite(track.id),
                isDownloaded = repository.isTrackDownloaded(track.id)
            )
        }
        // Unfavoriting from "Forgotten Favorites" should drop it from the row
        // right away rather than leaving a stale heart icon until next Home load.
        _forgottenFavorites.value = _forgottenFavorites.value
            .filter { repository.isTrackFavorite(it.id) }
            .map { track -> track.copy(isDownloaded = repository.isTrackDownloaded(track.id)) }
    }

    fun playTrack(track: Track, tracksList: List<Track>) {
        player.setQueue(tracksList, tracksList.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    private fun getLocalFallbackTracks(): List<Track> {
        return listOf(
            Track(1, "Golden Hour", "JVKE", "Golden Hour", "", "https://picsum.photos/300/300?random=1", 180000, "Pop"),
            Track(2, "Midnight City", "M83", "Hurry Up, We're Dreaming", "", "https://picsum.photos/300/300?random=2", 240000, "Electronic"),
            Track(3, "Sweater Weather", "The Neighbourhood", "I Love You", "", "https://picsum.photos/300/300?random=3", 200000, "Alternative")
        )
    }

    class Factory(
        private val repository: MusicRepository,
        private val player: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusicViewModel(repository, player) as T
        }
    }
}
