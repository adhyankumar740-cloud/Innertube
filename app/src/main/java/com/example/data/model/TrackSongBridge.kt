package com.example.data.model

/**
 * Converts between [Track] (the app's playback model - relay/YouTube-sourced
 * for real songs today, iTunes previews still used for Samples) and [Song]
 * (the source-agnostic model JamManager syncs over Supabase).
 *
 * Kept as a single small bridge so source changes only need updating here -
 * JamManager and JamChatManager stay untouched.
 */
object TrackSongBridge {
    // BUG FIX: this always hardcoded source = "itunes" and streamUrl =
    // track.previewUrl, no matter what the track actually was. That was fine
    // back when every song WAS an iTunes preview - but now (relay API only,
    // direct YouTube path removed) real songs are TrackSource.YOUTUBE with
    // previewUrl = "" (relay tracks carry a youtubeVideoId instead, resolved
    // to real audio per-device via RelayService). So every Jam broadcast was
    // silently sending an EMPTY streamUrl + the wrong source tag. The
    // receiving device(s) then reconstructed a Track with no youtubeVideoId,
    // defaulted back to TrackSource.ITUNES, and tried to play an empty URI -
    // which is exactly why the song only ever played on the device that
    // started it (the only one with the real track data already loaded
    // locally) and buffered forever on every other device (empty/invalid URI
    // never resolves to real audio, so ExoPlayer just sits in
    // STATE_BUFFERING). Now both source and youtubeVideoId are carried
    // through properly, so every device does its own fresh relay resolve.
    fun toSong(track: Track): Song = Song(
        id = track.id.toString(),
        title = track.title,
        artist = track.artist,
        duration = track.durationMs,
        source = when (track.source) {
            TrackSource.YOUTUBE -> "youtube"
            TrackSource.ITUNES -> "itunes"
        },
        // Only meaningful for iTunes tracks. YouTube/relay tracks are re-resolved
        // fresh on each device from youtubeVideoId - a resolved relay stream URL
        // can be short-lived/signed, so we deliberately don't rely on sharing it.
        streamUrl = if (track.source == TrackSource.YOUTUBE) "" else track.previewUrl,
        genre = track.genre,
        artwork = track.artworkUrl,
        youtubeVideoId = track.youtubeVideoId
    )

    fun toTrack(song: Song): Track {
        val isYoutube = song.source == "youtube" && !song.youtubeVideoId.isNullOrBlank()
        return Track(
            id = song.id.toLongOrNull() ?: song.id.hashCode().toLong(),
            title = song.title,
            artist = song.artist,
            album = "",
            previewUrl = if (isYoutube) "" else song.streamUrl,
            artworkUrl = song.artwork,
            durationMs = song.duration,
            genre = song.genre,
            source = if (isYoutube) TrackSource.YOUTUBE else TrackSource.ITUNES,
            youtubeVideoId = if (isYoutube) song.youtubeVideoId else null
        )
    }
}
