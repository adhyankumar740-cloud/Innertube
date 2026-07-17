package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.OnboardingPreferences
import com.example.data.network.InnerTubeMusicService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spotify-style first-launch onboarding: pick a few genres, then a few
 * artists you like. The picks are saved locally and fed into
 * [com.example.data.repository.MusicRepository]'s personalization profile so
 * Home/Samples recommendations reflect them immediately - before the app has
 * any real listening/search history to learn from.
 *
 * GENRE/ARTIST SOURCE FIX: this used to show a fixed list of 12 hardcoded
 * genres and 16 hardcoded artists, which looked sparse/repetitive and didn't
 * reflect what's actually available on YouTube Music. Both lists are now
 * pulled live from InnerTube: genres from YT Music's own "Moods & Genres"
 * page (usually 30-40+ real entries), and artists from InnerTube search
 * seeded by whichever genres the user picked (up to 60, deduped). If the
 * network call fails (no connection, InnerTube hiccup) we fall back to a
 * small built-in list so onboarding never shows an empty screen.
 */
class OnboardingViewModel(private val context: Context) : ViewModel() {

    companion object {
        // Used ONLY as a safety net if InnerTube can't be reached - not the
        // primary source anymore (see loadGenres/loadArtistsForSelectedGenres).
        val FALLBACK_GENRES = listOf(
            "Pop", "Hip-Hop", "Rock", "Electronic", "Lo-Fi", "R&B",
            "Indie", "Chill", "Classical", "Jazz", "Metal", "Bollywood"
        )
        val FALLBACK_ARTISTS = listOf(
            "Taylor Swift", "The Weeknd", "Drake", "Billie Eilish",
            "Ed Sheeran", "Dua Lipa", "Arijit Singh", "Coldplay",
            "Imagine Dragons", "Post Malone", "Ariana Grande", "BTS",
            "Kendrick Lamar", "Adele", "Bruno Mars", "Karan Aujla"
        )

        const val MIN_GENRE_SELECTIONS = 3
        const val MIN_ARTIST_SELECTIONS = 2
    }

    private val _availableGenres = MutableStateFlow<List<String>>(emptyList())
    val availableGenres = _availableGenres.asStateFlow()

    private val _isLoadingGenres = MutableStateFlow(true)
    val isLoadingGenres = _isLoadingGenres.asStateFlow()

    private val _availableArtists = MutableStateFlow<List<String>>(emptyList())
    val availableArtists = _availableArtists.asStateFlow()

    private val _isLoadingArtists = MutableStateFlow(false)
    val isLoadingArtists = _isLoadingArtists.asStateFlow()

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres = _selectedGenres.asStateFlow()

    private val _selectedArtists = MutableStateFlow<Set<String>>(emptySet())
    val selectedArtists = _selectedArtists.asStateFlow()

    // step 0 = genre picker, step 1 = artist picker
    private val _step = MutableStateFlow(0)
    val step = _step.asStateFlow()

    private val _isComplete = MutableStateFlow(OnboardingPreferences.isCompleted(context))
    val isComplete = _isComplete.asStateFlow()

    init {
        loadGenres()
    }

    private fun loadGenres() {
        viewModelScope.launch {
            _isLoadingGenres.value = true
            val genres = runCatching { InnerTubeMusicService.getGenres() }.getOrNull()
            _availableGenres.value = if (genres.isNullOrEmpty()) FALLBACK_GENRES else genres
            _isLoadingGenres.value = false
        }
    }

    private fun loadArtistsForSelectedGenres() {
        viewModelScope.launch {
            _isLoadingArtists.value = true
            val artists = runCatching {
                InnerTubeMusicService.getArtistsForGenres(_selectedGenres.value.toList())
            }.getOrNull()
            _availableArtists.value = if (artists.isNullOrEmpty()) FALLBACK_ARTISTS else artists
            _isLoadingArtists.value = false
        }
    }

    fun retryLoadGenres() = loadGenres()
    fun retryLoadArtists() = loadArtistsForSelectedGenres()

    fun toggleGenre(genre: String) {
        _selectedGenres.value = _selectedGenres.value.toMutableSet().apply {
            if (!add(genre)) remove(genre)
        }
    }

    fun toggleArtist(artist: String) {
        _selectedArtists.value = _selectedArtists.value.toMutableSet().apply {
            if (!add(artist)) remove(artist)
        }
    }

    fun goToArtistStep() {
        _step.value = 1
        if (_availableArtists.value.isEmpty()) loadArtistsForSelectedGenres()
    }

    fun goBackToGenreStep() {
        _step.value = 0
    }

    fun finishOnboarding() {
        OnboardingPreferences.saveSelections(
            context = context,
            genres = _selectedGenres.value.toList(),
            artists = _selectedArtists.value.toList()
        )
        _isComplete.value = true
    }

    fun skipOnboarding() {
        OnboardingPreferences.markSkipped(context)
        _isComplete.value = true
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OnboardingViewModel(context.applicationContext) as T
        }
    }
}
