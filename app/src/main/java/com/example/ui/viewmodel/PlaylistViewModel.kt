package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.data.repository.Playlist
import com.example.data.repository.PlaylistImportResult
import com.example.data.sync.PlaylistCloudSync
import com.example.player.MusicPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Library "Playlists" tab: create/rename/delete a playlist, add or
 * remove tracks, play a whole playlist through the app's shared [MusicPlayer]
 * queue, and import a playlist from a pasted song list (or M3U-style text).
 *
 * Playlists are also backed up to the cloud (see [PlaylistCloudSync]) keyed
 * by the logged-in account's [userEmail], so deleting/reinstalling the app -
 * or signing into the same account on a different device - brings them all
 * back instead of losing them. This is skipped entirely for Guest sessions
 * (empty email), same as the rest of the app's per-account features.
 */
class PlaylistViewModel(
    private val repository: MusicRepository,
    private val musicPlayer: MusicPlayer,
    private val cloudSync: PlaylistCloudSync,
    private val userEmail: StateFlow<String>
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Which playlist's detail view is open, if any (null = showing the list).
    private val _openPlaylistId = MutableStateFlow<Long?>(null)
    val openPlaylistId = _openPlaylistId.asStateFlow()

    // Tracks of whichever playlist is currently open - switches automatically
    // when openPlaylistId changes, and is empty while nothing is open.
    private val _openPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val openPlaylistTracks = _openPlaylistTracks.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importResult = MutableStateFlow<PlaylistImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    init {
        viewModelScope.launch {
            _openPlaylistId.collectLatest { id ->
                if (id == null) {
                    _openPlaylistTracks.value = emptyList()
                } else {
                    repository.getPlaylistTracks(id).collectLatest { tracks ->
                        _openPlaylistTracks.value = tracks
                    }
                }
            }
        }

        // Pull down (and push up) cloud playlist backups as soon as a real
        // account email is available - covers both a fresh login and simply
        // reopening the app while already logged in. No-ops for Guest
        // (blank email) and is safe to call more than once (restoreIfNeeded
        // only actually runs the merge once per email per process).
        viewModelScope.launch {
            userEmail.collectLatest { email ->
                if (email.isNotBlank()) cloudSync.restoreIfNeeded(email, repository)
            }
        }
    }

    fun openPlaylist(playlistId: Long) {
        _openPlaylistId.value = playlistId
    }

    fun closePlaylist() {
        _openPlaylistId.value = null
    }

    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.createPlaylist(name)
            onCreated(id)
            val email = userEmail.value
            if (email.isNotBlank()) {
                val remoteId = repository.getRemoteId(id)
                if (remoteId != null) {
                    cloudSync.uploadPlaylist(
                        email,
                        Playlist(id = id, name = name.trim().ifBlank { "New Playlist" }, trackCount = 0, createdAt = System.currentTimeMillis(), remoteId = remoteId),
                        emptyList()
                    )
                }
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            val email = userEmail.value
            if (email.isNotBlank()) {
                val remoteId = repository.getRemoteId(playlistId)
                if (remoteId != null) cloudSync.renamePlaylist(email, remoteId, newName.trim())
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            // Look up the remoteId BEFORE deleting locally - once the row is
            // gone we have no way left to find it.
            val email = userEmail.value
            val remoteId = if (email.isNotBlank()) repository.getRemoteId(playlistId) else null

            repository.deletePlaylist(playlistId)
            if (_openPlaylistId.value == playlistId) _openPlaylistId.value = null

            if (email.isNotBlank() && remoteId != null) cloudSync.deletePlaylist(email, remoteId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, track)
            val email = userEmail.value
            if (email.isNotBlank()) {
                val remoteId = repository.getRemoteId(playlistId)
                if (remoteId != null) cloudSync.addTrack(email, remoteId, track)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, track.id)
            val email = userEmail.value
            if (email.isNotBlank()) {
                val remoteId = repository.getRemoteId(playlistId)
                if (remoteId != null) cloudSync.removeTrack(email, remoteId, track.id)
            }
        }
    }

    /** Starts playback of the whole playlist, from [startTrack] if given, else from the top. */
    fun playPlaylist(tracks: List<Track>, startTrack: Track? = null) {
        if (tracks.isEmpty()) return
        val startIndex = startTrack?.let { t -> tracks.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
        musicPlayer.setQueue(tracks, startIndex)
    }

    /**
     * Starts playback of the whole playlist in shuffled order: turns the
     * player's global shuffle mode on (so Next/Previous keep shuffling
     * afterwards too, same as everywhere else in the app) and picks a random
     * starting track rather than always track 0.
     */
    fun shufflePlayPlaylist(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (!musicPlayer.isShuffleEnabled.value) {
            musicPlayer.toggleShuffle()
        }
        musicPlayer.setQueue(tracks, tracks.indices.random())
    }

    /**
     * Imports a playlist from pasted text (one song per line: "Title - Artist"
     * works best). Each line is resolved against the iTunes catalog; lines
     * that don't match anything are reported in the result rather than
     * silently dropped.
     */
    fun importPlaylist(name: String, rawText: String) {
        viewModelScope.launch {
            _isImporting.value = true
            _importResult.value = null
            val result = repository.importPlaylist(name, rawText)
            _importResult.value = result
            _isImporting.value = false

            val email = userEmail.value
            if (email.isNotBlank() && result.matchedCount > 0) {
                val remoteId = repository.getRemoteId(result.playlistId)
                if (remoteId != null) {
                    // Single current snapshot right after the import finished
                    // (getPlaylistTracks is a live Flow; first() is enough here).
                    val tracks = repository.getPlaylistTracks(result.playlistId).first()
                    cloudSync.uploadPlaylist(
                        email,
                        Playlist(id = result.playlistId, name = result.playlistName, trackCount = tracks.size, createdAt = System.currentTimeMillis(), remoteId = remoteId),
                        tracks
                    )
                }
            }
        }
    }

    fun dismissImportResult() {
        _importResult.value = null
    }

    class Factory(
        private val repository: MusicRepository,
        private val musicPlayer: MusicPlayer,
        private val cloudSync: PlaylistCloudSync,
        private val userEmail: StateFlow<String>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistViewModel(repository, musicPlayer, cloudSync, userEmail) as T
        }
    }
}
