package com.example.data.sync

import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.example.data.repository.MusicRepository
import com.example.data.repository.Playlist
import com.example.jam.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Backs playlists up to **Supabase** (table `playlist_backups` - see
 * `supabase/schema.sql`), keyed by the user's email (from Google Sign-In -
 * see AuthViewModel), and restores them on a fresh install / a different
 * device. This is what makes "delete the app -> log back in" bring
 * playlists back instead of losing them: deleting/uninstalling the app
 * wipes the local Room database, but the cloud copy survives and is merged
 * back in the next time the same Google account logs in.
 *
 * Deliberately NOT wired up for Guest sessions - a guest has no stable
 * identity to key a backup by, so guest playlists stay local-only, exactly
 * like every other kind of data in this app already behaves for guests.
 *
 * This app has no real Supabase Auth session (login is native Google
 * Sign-In only, see AuthViewModel.kt), so requests hit `playlist_backups`
 * as the `anon` role under permissive RLS - the same trust model already
 * used for the Jam and announcements tables. The email column is what
 * actually scopes a user to their own rows; tighten this later if you add
 * real Supabase Auth.
 *
 * Data shape in Supabase (one row per playlist):
 *   playlist_backups.email       - owning account's email
 *   playlist_backups.remote_id   - stable cross-device playlist id
 *   playlist_backups.name
 *   playlist_backups.created_at
 *   playlist_backups.tracks      - jsonb array, the playlist's full track list
 */
class PlaylistCloudSync {

    // Guards against re-running the restore merge more than once per login
    // per process - e.g. if the composable driving it recomposes a few times
    // right after sign-in. Not persisted on purpose: a fresh merge on every
    // cold app start is cheap and self-healing if a previous sync failed.
    private val restoredForEmail = mutableSetOf<String>()

    @Serializable
    private data class TrackRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @SerialName("preview_url") val previewUrl: String,
        @SerialName("artwork_url") val artworkUrl: String,
        @SerialName("duration_ms") val durationMs: Long,
        val genre: String,
        val source: String,
        @SerialName("youtube_video_id") val youtubeVideoId: String? = null,
        @SerialName("is_video") val isVideo: Boolean = false
    )

    @Serializable
    private data class PlaylistBackupRow(
        val email: String,
        @SerialName("remote_id") val remoteId: String,
        val name: String,
        @SerialName("created_at") val createdAt: Long,
        val tracks: List<TrackRow> = emptyList()
    )

    private fun Track.toRow(): TrackRow = TrackRow(
        id = id,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        source = source.name,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    private fun TrackRow.toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        source = if (source == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    private val table get() = SupabaseClient.client.postgrest["playlist_backups"]

    /**
     * Call once right after login (and again whenever the app cold-starts
     * already logged in) with the account's email. Does a two-way merge with
     * no conflict resolution needed, since it only ever fills in what's
     * *missing* on either side:
     *  - Cloud has a playlist (by remoteId) this device doesn't -> pulled down.
     *  - This device has a playlist the cloud doesn't -> pushed up (covers
     *    playlists made while offline, or made before this feature existed).
     *
     * Best-effort: on failure (no internet, etc.) local playback/library is
     * completely unaffected - the merge is just retried on the next call.
     */
    suspend fun restoreIfNeeded(email: String, repository: MusicRepository) {
        if (email.isBlank()) return
        if (!restoredForEmail.add(email)) return

        try {
            val cloudRows = table
                .select(columns = Columns.ALL) {
                    filter { eq("email", email) }
                }
                .decodeList<PlaylistBackupRow>()

            val localSnapshot = repository.getPlaylistsSnapshot()
            val localRemoteIds = localSnapshot.map { it.first.remoteId }.toSet()

            // Pull down whatever the cloud has that this install doesn't.
            for (row in cloudRows) {
                if (row.remoteId !in localRemoteIds) {
                    repository.restorePlaylistFromCloud(
                        remoteId = row.remoteId,
                        name = row.name,
                        createdAt = row.createdAt,
                        tracks = row.tracks.map { it.toTrack() }
                    )
                }
            }

            // Push up whatever this install has that the cloud doesn't yet.
            val cloudRemoteIds = cloudRows.map { it.remoteId }.toSet()
            for ((playlist, tracks) in localSnapshot) {
                if (playlist.remoteId !in cloudRemoteIds) {
                    upsertPlaylist(email, playlist, tracks)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            restoredForEmail.remove(email) // allow a retry later
        }
    }

    /** Inserts or fully overwrites one playlist's metadata + track list in one shot. */
    private suspend fun upsertPlaylist(email: String, playlist: Playlist, tracks: List<Track>) {
        table.upsert(
            PlaylistBackupRow(
                email = email,
                remoteId = playlist.remoteId,
                name = playlist.name,
                createdAt = playlist.createdAt,
                tracks = tracks.map { it.toRow() }
            )
        ) {
            onConflict = "email,remote_id"
        }
    }

    /** Fire-and-forget upload - call after creating a playlist or finishing an import. */
    fun uploadPlaylist(email: String, playlist: Playlist, tracks: List<Track>) {
        if (email.isBlank()) return
        pushAsync { upsertPlaylist(email, playlist, tracks) }
    }

    /** Fire-and-forget rename - call after [MusicRepository.renamePlaylist]. */
    fun renamePlaylist(email: String, remoteId: String, newName: String) {
        if (email.isBlank()) return
        pushAsync {
            table.update(mapOf("name" to newName)) {
                filter {
                    eq("email", email)
                    eq("remote_id", remoteId)
                }
            }
        }
    }

    /** Fire-and-forget delete - call after [MusicRepository.deletePlaylist]. */
    fun deletePlaylist(email: String, remoteId: String) {
        if (email.isBlank()) return
        pushAsync {
            table.delete {
                filter {
                    eq("email", email)
                    eq("remote_id", remoteId)
                }
            }
        }
    }

    /** Fire-and-forget track add - call after [MusicRepository.addTrackToPlaylist]. */
    fun addTrack(email: String, remoteId: String, track: Track) {
        if (email.isBlank()) return
        pushAsync { mergeTracks(email, remoteId) { current -> current + track.toRow() } }
    }

    /** Fire-and-forget track remove - call after [MusicRepository.removeTrackFromPlaylist]. */
    fun removeTrack(email: String, remoteId: String, trackId: Long) {
        if (email.isBlank()) return
        pushAsync { mergeTracks(email, remoteId) { current -> current.filterNot { it.id == trackId } } }
    }

    /**
     * Reads the row's current `tracks` array, applies [transform], and writes
     * the result back - the jsonb-array equivalent of Firebase's old
     * per-child add/remove, since Postgrest updates a jsonb column as a whole
     * rather than one array element at a time.
     */
    private suspend fun mergeTracks(email: String, remoteId: String, transform: (List<TrackRow>) -> List<TrackRow>) {
        val row = table
            .select(columns = Columns.list("tracks")) {
                filter {
                    eq("email", email)
                    eq("remote_id", remoteId)
                }
            }
            .decodeSingleOrNull<Map<String, List<TrackRow>>>() ?: return

        val updatedTracks = transform(row["tracks"] ?: emptyList())
        table.update(mapOf("tracks" to updatedTracks)) {
            filter {
                eq("email", email)
                eq("remote_id", remoteId)
            }
        }
    }

    // Sync writes are side effects that shouldn't block the UI or the
    // caller's own (local-first, already-completed) Room operation. A
    // failure here just means this one change won't show up on another
    // device until the next successful sync - local library state is
    // unaffected either way.
    private fun pushAsync(block: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
