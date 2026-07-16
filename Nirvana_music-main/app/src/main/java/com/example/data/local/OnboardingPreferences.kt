package com.example.data.local

import android.content.Context

/**
 * Local (on-device) storage for the Spotify-style first-launch onboarding
 * flow: which genres/artists the user picked, and whether onboarding has
 * been completed at all (so it's only ever shown once, on first launch).
 *
 * Plain SharedPreferences is used here (same pattern as AuthViewModel's
 * auth_prefs) rather than DataStore, since this is a handful of small,
 * synchronously-readable values that need to be available immediately at
 * app start (before the first Compose frame) to decide whether to show the
 * onboarding screen at all.
 */
object OnboardingPreferences {
    private const val PREFS_NAME = "onboarding_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_GENRES = "onboarding_genres"
    private const val KEY_ARTISTS = "onboarding_artists"

    // Genres/artists are stored as a single delimited string (SharedPreferences
    // StringSet has unspecified iteration order across OS versions/restores,
    // which would silently reshuffle "most preferred first" ordering).
    private const val DELIMITER = "\u001F"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun getSelectedGenres(context: Context): List<String> =
        prefs(context).getString(KEY_GENRES, null)
            ?.split(DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun getSelectedArtists(context: Context): List<String> =
        prefs(context).getString(KEY_ARTISTS, null)
            ?.split(DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun saveSelections(context: Context, genres: List<String>, artists: List<String>) {
        prefs(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .putString(KEY_GENRES, genres.joinToString(DELIMITER))
            .putString(KEY_ARTISTS, artists.joinToString(DELIMITER))
            .apply()
    }

    /** Lets a user skip onboarding entirely without picking anything - still marks it as "seen". */
    fun markSkipped(context: Context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).apply()
    }
}
