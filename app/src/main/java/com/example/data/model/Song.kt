package com.example.data.model

/**
 * Source-agnostic song representation used by the Jam sync layer (JamManager)
 * so that playback state can be broadcast/received over Supabase without
 * depending on any one music source's model. See TrackSongBridge for the
 * conversion to/from [Track].
 *
 * FIX: the app's real songs now come from the relay API (TrackSource.YOUTUBE -
 * the direct YouTube path is gone), which plays via a per-device relay
 * `/resolve` call keyed on `youtubeVideoId`, NOT a shared `previewUrl`. This
 * field carries that id across Supabase so every device in a Jam room does
 * its OWN fresh relay resolve for the song instead of reusing another
 * device's (often empty/stale) stream URL - see TrackSongBridge for why this
 * matters.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val source: String,
    val streamUrl: String,
    val genre: String,
    val artwork: String,
    val youtubeVideoId: String? = null
)
