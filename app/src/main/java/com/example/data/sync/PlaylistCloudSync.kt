package com.example.data.sync

import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.example.data.repository.MusicRepository
import com.example.data.repository.Playlist
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Backs playlists up to Firebase Realtime Database and restores them on a
 * fresh install / a different device, keyed by the user's email (from Google
 * Sign-In - see AuthViewModel). This is what makes "delete the app -> log
 * back in" bring playlists back instead of losing them: deleting/uninstalling
 * the app wipes the local Room database, but the cloud copy survives and is
 * merged back in the next time the same Google account logs in.
 *
 * Deliberately NOT wired up for Guest sessions - a guest has no stable
 * identity to key a backup by, so guest playlists stay local-only, exactly
 * like every other kind of data in this app already behaves for guests.
 *
 * Uses the same silent Firebase Anonymous Auth as JamManager, purely to
 * satisfy the Realtime Database's "auth != null" security rule - this has
 * nothing to do with *which* Google account is logged into the app itself.
 * Actual per-user separation of data comes from keying every path below by
 * the user's (sanitized) email, the same "shared node, path is the access
 * control" pattern this app already uses for Jam room codes.
 *
 * Data shape in Firebase:
 *   playlist_backups/{sanitizedEmail}/{remoteId}/name
 *   playlist_backups/{sanitizedEmail}/{remoteId}/createdAt
 *   playlist_backups/{sanitizedEmail}/{remoteId}/tracks/{trackId}/...fields...
 */
class PlaylistCloudSync {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance().reference.child("playlist_backups")

    // Guards against re-running the restore merge more than once per login
    // per process - e.g. if the composable driving it recomposes a few times
    // right after sign-in. Not persisted on purpose: a fresh merge on every
    // cold app start is cheap and self-healing if a previous sync failed.
    private val restoredForEmail = mutableSetOf<String>()

    private suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    /** Firebase Realtime Database keys can't contain '.', '#', '$', '[', ']'. */
    private fun sanitize(email: String): String = email.replace(Regex("[.#$\\[\\]]"), "_")

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
            ensureSignedIn()
            val userRef = db.child(sanitize(email))
            val snapshot = userRef.get().await()

            val cloudPlaylists = snapshot.children.mapNotNull { child ->
                val remoteId = child.key ?: return@mapNotNull null
                val name = child.child("name").getValue(String::class.java) ?: return@mapNotNull null
                val createdAt = child.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                val tracks = child.child("tracks").children.mapNotNull { it.toTrackOrNull() }
                CloudPlaylist(remoteId, name, createdAt, tracks)
            }

            val localSnapshot = repository.getPlaylistsSnapshot()
            val localRemoteIds = localSnapshot.map { it.first.remoteId }.toSet()

            // Pull down whatever the cloud has that this install doesn't.
            for (cp in cloudPlaylists) {
                if (cp.remoteId !in localRemoteIds) {
                    repository.restorePlaylistFromCloud(cp.remoteId, cp.name, cp.createdAt, cp.tracks)
                }
            }

            // Push up whatever this install has that the cloud doesn't yet.
            val cloudRemoteIds = cloudPlaylists.map { it.remoteId }.toSet()
            for ((playlist, tracks) in localSnapshot) {
                if (playlist.remoteId !in cloudRemoteIds) {
                    uploadPlaylistBlocking(email, playlist, tracks)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            restoredForEmail.remove(email) // allow a retry later
        }
    }

    /** Uploads (or overwrites) one playlist's full metadata + track list in one shot. */
    private suspend fun uploadPlaylistBlocking(email: String, playlist: Playlist, tracks: List<Track>) {
        val playlistRef = db.child(sanitize(email)).child(playlist.remoteId)
        val tracksMap = tracks.associate { it.id.toString() to it.toFieldMap() }
        playlistRef.updateChildren(
            mapOf(
                "name" to playlist.name,
                "createdAt" to playlist.createdAt,
                "tracks" to tracksMap
            )
        ).await()
    }

    /** Fire-and-forget upload - call after creating a playlist or finishing an import. */
    fun uploadPlaylist(email: String, playlist: Playlist, tracks: List<Track>) {
        if (email.isBlank()) return
        pushAsync {
            ensureSignedIn()
            uploadPlaylistBlocking(email, playlist, tracks)
        }
    }

    /** Fire-and-forget rename - call after [MusicRepository.renamePlaylist]. */
    fun renamePlaylist(email: String, remoteId: String, newName: String) {
        if (email.isBlank()) return
        pushAsync {
            ensureSignedIn()
            db.child(sanitize(email)).child(remoteId).child("name").setValue(newName).await()
        }
    }

    /** Fire-and-forget delete - call after [MusicRepository.deletePlaylist]. */
    fun deletePlaylist(email: String, remoteId: String) {
        if (email.isBlank()) return
        pushAsync {
            ensureSignedIn()
            db.child(sanitize(email)).child(remoteId).removeValue().await()
        }
    }

    /** Fire-and-forget track add - call after [MusicRepository.addTrackToPlaylist]. */
    fun addTrack(email: String, remoteId: String, track: Track) {
        if (email.isBlank()) return
        pushAsync {
            ensureSignedIn()
            db.child(sanitize(email)).child(remoteId).child("tracks").child(track.id.toString())
                .setValue(track.toFieldMap()).await()
        }
    }

    /** Fire-and-forget track remove - call after [MusicRepository.removeTrackFromPlaylist]. */
    fun removeTrack(email: String, remoteId: String, trackId: Long) {
        if (email.isBlank()) return
        pushAsync {
            ensureSignedIn()
            db.child(sanitize(email)).child(remoteId).child("tracks").child(trackId.toString())
                .removeValue().await()
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

    private data class CloudPlaylist(
        val remoteId: String,
        val name: String,
        val createdAt: Long,
        val tracks: List<Track>
    )

    private fun DataSnapshot.toTrackOrNull(): Track? {
        val trackId = key?.toLongOrNull() ?: return null
        val title = child("title").getValue(String::class.java) ?: return null
        return Track(
            id = trackId,
            title = title,
            artist = child("artist").getValue(String::class.java) ?: "",
            album = child("album").getValue(String::class.java) ?: "",
            previewUrl = child("previewUrl").getValue(String::class.java) ?: "",
            artworkUrl = child("artworkUrl").getValue(String::class.java) ?: "",
            durationMs = child("durationMs").getValue(Long::class.java) ?: 0L,
            genre = child("genre").getValue(String::class.java) ?: "",
            source = if (child("source").getValue(String::class.java) == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
            youtubeVideoId = child("youtubeVideoId").getValue(String::class.java),
            isVideo = child("isVideo").getValue(Boolean::class.java) ?: false
        )
    }

    private fun Track.toFieldMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "artist" to artist,
        "album" to album,
        "previewUrl" to previewUrl,
        "artworkUrl" to artworkUrl,
        "durationMs" to durationMs,
        "genre" to genre,
        "source" to source.name,
        "youtubeVideoId" to youtubeVideoId,
        "isVideo" to isVideo
    )
}
