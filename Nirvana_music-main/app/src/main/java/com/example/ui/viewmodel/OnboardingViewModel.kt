package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.OnboardingPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify-style first-launch onboarding: pick a few genres, then a few
 * artists you like. The picks are saved locally and fed into
 * [com.example.data.repository.MusicRepository]'s personalization profile so
 * Home/Samples recommendations reflect them immediately - before the app has
 * any real listening/search history to learn from.
 */
class OnboardingViewModel(private val context: Context) : ViewModel() {

    companion object {
        val AVAILABLE_GENRES = listOf(
            "Pop", "Hip-Hop", "Rock", "Electronic", "Lo-Fi", "R&B",
            "Indie", "Chill", "Classical", "Jazz", "Metal", "Bollywood"
        )

        val AVAILABLE_ARTISTS = listOf(
            "Taylor Swift", "The Weeknd", "Drake", "Billie Eilish",
            "Ed Sheeran", "Dua Lipa", "Arijit Singh", "Coldplay",
            "Imagine Dragons", "Post Malone", "Ariana Grande", "BTS",
            "Kendrick Lamar", "Adele", "Bruno Mars", "Karan Aujla"
        )

        const val MIN_GENRE_SELECTIONS = 3
        const val MIN_ARTIST_SELECTIONS = 2
    }

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres = _selectedGenres.asStateFlow()

    private val _selectedArtists = MutableStateFlow<Set<String>>(emptySet())
    val selectedArtists = _selectedArtists.asStateFlow()

    // step 0 = genre picker, step 1 = artist picker
    private val _step = MutableStateFlow(0)
    val step = _step.asStateFlow()

    private val _isComplete = MutableStateFlow(OnboardingPreferences.isCompleted(context))
    val isComplete = _isComplete.asStateFlow()

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
