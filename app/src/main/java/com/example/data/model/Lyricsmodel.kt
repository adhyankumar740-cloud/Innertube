package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LrcLibResult(
    @Json(name = "id") val id: Long?,
    @Json(name = "trackName") val trackName: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "instrumental") val instrumental: Boolean?,
    @Json(name = "plainLyrics") val plainLyrics: String?,
    @Json(name = "syncedLyrics") val syncedLyrics: String?
)

/** One line of time-synced lyrics, parsed out of LRCLIB's `syncedLyrics` LRC text. */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class Lyrics(
    val plain: String?,
    val synced: List<LyricLine>
) {
    val isAvailable: Boolean get() = !plain.isNullOrBlank() || synced.isNotEmpty()
}

/** Parses LRC-format synced lyrics, e.g. "[00:27.93] Listen to the wind blow". */
fun parseSyncedLyrics(lrc: String?): List<LyricLine> {
    if (lrc.isNullOrBlank()) return emptyList()
    val lineRegex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")
    return lrc.lines().mapNotNull { line ->
        val match = lineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
        val (min, sec, ms, text) = match.destructured
        val minutes = min.toLongOrNull() ?: return@mapNotNull null
        val seconds = sec.toLongOrNull() ?: return@mapNotNull null
        val millis = if (ms.isBlank()) 0L else ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        LyricLine(timeMs = minutes * 60_000 + seconds * 1000 + millis, text = text.trim())
    }.filter { it.text.isNotBlank() }
}
