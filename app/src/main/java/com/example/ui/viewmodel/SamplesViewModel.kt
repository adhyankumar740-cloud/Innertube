package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.player.MusicPlayer
import com.example.player.SamplesPlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SamplesViewModel(
    private val repository: MusicRepository,
    val playerManager: SamplesPlayerManager,
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    private val _samples = MutableStateFlow<List<Track>>(emptyList())
    val samples = _samples.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    // True while we're looking up the matching YouTube video for "Play Full Song".
    private val _isResolvingFullSong = MutableStateFlow(false)
    val isResolvingFullSong = _isResolvingFullSong.asStateFlow()

    init {
        loadSamples()
    }

    fun loadSamples() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getSamplesFeed("trending hit").collectLatest { tracks ->
                _samples.value = tracks
                _isLoading.value = false
            }
        }
    }

    fun onSwipe(newIndex: Int) {
        if (newIndex in _samples.value.indices) {
            _currentIndex.value = newIndex
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
            _samples.value = _samples.value.map {
                if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it
            }
        }
    }

    /**
     * "Play Full Song" button - finds the best-matching YouTube video for this
     * sample's title/artist and streams its audio directly through the app's
     * own player (relay-resolved, via MusicPlayer/PlaybackService). No longer
     * launches the external YouTube app.
     */
    fun playFullSong(track: Track) {
        viewModelScope.launch {
            _isResolvingFullSong.value = true
            playerManager.pause()
            val match = repository.findBestYouTubeMatch(track.title, track.artist)
            _isResolvingFullSong.value = false
            if (match != null) {
                musicPlayer.play(match)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.pause()
    }

    class Factory(
        private val repository: MusicRepository,
        private val playerManager: SamplesPlayerManager,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SamplesViewModel(repository, playerManager, musicPlayer) as T
        }
    }
}
