package com.example.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class RepeatMode { OFF, ONE, ALL }

/**
 * App-process facade around a [MediaController] connected to [MusicService]. Public API (every
 * field/function below) is unchanged from the previous single-item implementation - only the
 * internals changed, so MusicViewModel/JamViewModel/PlaylistViewModel/SamplesViewModel and every
 * screen that reads these StateFlows keep working with zero changes.
 *
 * The key architectural change vs. the old MusicPlayer: `queue`/`queueIndex`/`currentTrack`/
 * `isShuffleEnabled`/`repeatMode` are no longer app-managed state that's manually kept in sync
 * with a single-item ExoPlayer - they're now derived directly from the connected
 * MediaController's real, multi-item timeline (see TrackMediaItem.kt for how a [Track] becomes a
 * full [androidx.media3.common.MediaItem] and back). Next/Previous/seek/shuffle/repeat are now
 * literally MediaController calls against that real timeline, exactly like MetroList's
 * PlayerConnection - not a reimplementation on top of a fake one.
 */
class MusicPlayer(
    private val context: Context
) {

    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null
    var onLocalStop: ((positionMs: Long) -> Unit)? = null

    var autoplayProvider: (suspend (currentTrack: Track, excludeIds: Set<Long>, recentTracks: List<Track>) -> Track?)? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isResolvingAutoplay = MutableStateFlow(false)
    val isResolvingAutoplay: StateFlow<Boolean> = _isResolvingAutoplay.asStateFlow()

    private val _sleepTimerEndsAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndsAt: StateFlow<Long?> = _sleepTimerEndsAt.asStateFlow()
    private var sleepTimerJob: Job? = null

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _lastStreamErrorDebug = MutableStateFlow<String?>(null)
    val lastStreamErrorDebug: StateFlow<String?> = _lastStreamErrorDebug.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- Jam (remote sync) echo-prevention - unchanged from the previous implementation ---
    private var isApplyingRemote = false
    private var suppressNextPlayPauseBroadcast = false
    private var suppressPlayPauseArmedAt = 0L
    private var suppressNextSeekBroadcast = false
    private var suppressSeekArmedAt = 0L
    private val suppressExpiryMs = 60_000L

    private data class RemoteCatchUp(val positionMs: Long, val isPlaying: Boolean, val updatedAtServerMs: Long)
    private var pendingCatchUp: RemoteCatchUp? = null
    private var pendingCatchUpMediaId: String? = null

    private fun applyCatchUpSeek(positionMs: Long) {
        isApplyingRemote = true
        suppressNextSeekBroadcast = true
        suppressSeekArmedAt = System.currentTimeMillis()
        try {
            seekTo(positionMs)
        } finally {
            isApplyingRemote = false
        }
    }

    private fun resolveAndApplyCatchUp(catchUp: RemoteCatchUp, trackDurationMs: Long) {
        val target = if (catchUp.isPlaying) {
            catchUp.positionMs + (System.currentTimeMillis() - catchUp.updatedAtServerMs).coerceAtLeast(0L)
        } else {
            catchUp.positionMs.coerceAtLeast(0L)
        }
        val clamped = if (trackDurationMs > 0) target.coerceIn(0L, (trackDurationMs - 500L).coerceAtLeast(0L)) else target
        if (clamped > 700L) applyCatchUpSeek(clamped)
    }

    private val recentlyPlayedIds = ArrayDeque<Long>()
    private val recentlyPlayedTracks = ArrayDeque<Track>()
    private val recentlyPlayedCap = 40

    private fun trackRecentlyPlayed(track: Track) {
        if (recentlyPlayedIds.lastOrNull() != track.id) {
            recentlyPlayedIds.addLast(track.id)
            recentlyPlayedTracks.addLast(track)
            while (recentlyPlayedIds.size > recentlyPlayedCap) recentlyPlayedIds.removeFirst()
            while (recentlyPlayedTracks.size > recentlyPlayedCap) recentlyPlayedTracks.removeFirst()
        }
    }

    private var consecutivePlaybackFailures = 0
    private val maxConsecutivePlaybackFailures = 3

    // Guards against re-requesting an autoplay continuation for the same "now on the last
    // queue item" moment more than once.
    private var autoplayRequestedForMediaId: String? = null
    private var autoplayJob: Job? = null

    private var progressJob: Job? = null

    init {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                mediaController = controller
                controller.addListener(playerListener)
                syncAllFromController(controller)
                pendingActions.forEach { it() }
                pendingActions.clear()
            } catch (e: Exception) {
                android.util.Log.e("MusicPlayer", "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun runOnController(action: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller != null) action(controller) else pendingActions.add { mediaController?.let(action) }
    }

    private fun syncAllFromController(controller: MediaController) {
        syncQueueFromController(controller)
        syncCurrentTrackFromController(controller)
        _isPlaying.value = controller.isPlaying
        _isShuffleEnabled.value = controller.shuffleModeEnabled
        _repeatMode.value = controller.repeatMode.toAppRepeatMode()
        _duration.value = controller.duration.coerceAtLeast(0L)
        if (controller.isPlaying) startProgressTracker()
    }

    private fun syncQueueFromController(controller: MediaController) {
        val items = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it) }
        _queue.value = items.mapNotNull { it.toTrackOrNull() }
        _queueIndex.value = controller.currentMediaItemIndex
    }

    private fun syncCurrentTrackFromController(controller: MediaController) {
        val track = controller.currentMediaItem?.toTrackOrNull()
        _currentTrack.value = track
        if (track != null) {
            trackRecentlyPlayed(track)
            if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        }
    }

    private fun Int.toAppRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val controller = mediaController ?: return
            syncQueueFromController(controller)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            consecutivePlaybackFailures = 0
            _playbackError.value = null
            _isBuffering.value = true
            syncQueueFromController(controller)
            syncCurrentTrackFromController(controller)
            _duration.value = (mediaItem?.toTrackOrNull()?.durationMs ?: 0L).coerceAtLeast(0L)

            // A remote (Jam) song change was applied for exactly this media item - now that
            // we're actually on it, catch up to wherever the room currently is.
            val expectedId = mediaItem?.mediaId
            val catchUp = pendingCatchUp
            if (catchUp != null && expectedId != null && expectedId == pendingCatchUpMediaId) {
                pendingCatchUp = null
                pendingCatchUpMediaId = null
                resolveAndApplyCatchUp(catchUp, mediaItem.toTrackOrNull()?.durationMs ?: 0L)
            }

            maybeRequestAutoplayContinuation(controller)
        }

        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            _isPlaying.value = isPlayingNow
            if (isPlayingNow) startProgressTracker() else stopProgressTracker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val stillArmed = suppressNextPlayPauseBroadcast &&
                (System.currentTimeMillis() - suppressPlayPauseArmedAt) < suppressExpiryMs
            suppressNextPlayPauseBroadcast = false
            if (!stillArmed) {
                onLocalPlayPause?.invoke(playWhenReady, _playbackPosition.value)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    _duration.value = (mediaController?.duration ?: 0L).coerceAtLeast(0L)
                }
                Player.STATE_ENDED -> {
                    // Real queue ran out with nothing more appended (autoplay provider had
                    // nothing to offer, or failed) - nothing left to do automatically.
                    _isBuffering.value = false
                }
                else -> {}
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _isShuffleEnabled.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode.toAppRepeatMode()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val targetSeekMs = newPosition.positionMs
                _playbackPosition.value = targetSeekMs
                val stillArmed = suppressNextSeekBroadcast &&
                    (System.currentTimeMillis() - suppressSeekArmedAt) < suppressExpiryMs
                suppressNextSeekBroadcast = false
                if (!stillArmed && !isApplyingRemote) {
                    onLocalSeek?.invoke(targetSeekMs)
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val track = _currentTrack.value
            _lastStreamErrorDebug.value =
                "[MediaController.onPlayerError] ${error.errorCodeName}: ${error.message} | cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message} (track=${track?.title})"
            android.util.Log.e("MusicPlayer", "Playback error", error)

            consecutivePlaybackFailures++
            if (consecutivePlaybackFailures >= maxConsecutivePlaybackFailures) {
                _playbackError.value = "Yeh gaana stream nahi ho paya."
                return
            }

            // One-shot auto-skip past a broken item, same spirit as the old relay-retry-once
            // behaviour - if there's nowhere to skip to, just surface the error.
            val controller = mediaController
            if (controller != null && controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
                controller.prepare()
                controller.play()
            } else {
                _playbackError.value = "Yeh gaana stream nahi ho paya."
            }
        }
    }

    /** Speculatively appends an autoplay recommendation once playback reaches the last item in
     *  the real queue, so ExoPlayer's own native auto-advance (STATE_ENDED -> next item) carries
     *  straight into it with no gap - mirrors MetroList's continuation-loading queues
     *  (YouTubeQueue/YouTubeAlbumRadio), just driven from here since this app's recommendation
     *  logic lives in MusicRepository (via the autoplayProvider callback), not the player. */
    private fun maybeRequestAutoplayContinuation(controller: MediaController) {
        if (_repeatMode.value != RepeatMode.OFF) return
        if (controller.mediaItemCount == 0) return
        if (controller.currentMediaItemIndex != controller.mediaItemCount - 1) return

        val currentItem = controller.currentMediaItem ?: return
        if (autoplayRequestedForMediaId == currentItem.mediaId) return
        autoplayRequestedForMediaId = currentItem.mediaId

        val current = currentItem.toTrackOrNull() ?: return
        val provider = autoplayProvider ?: return

        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            _isResolvingAutoplay.value = true
            try {
                val excludeIds = (_queue.value.map { it.id } + recentlyPlayedIds).toSet()
                val recentTracks = _queue.value + recentlyPlayedTracks
                val next = withTimeoutOrNull(20_000L) { provider(current, excludeIds, recentTracks) }
                // Stale if the user has since moved off this item (skipped/changed queue).
                if (mediaController?.currentMediaItem?.mediaId != currentItem.mediaId) return@launch
                if (next != null) {
                    runOnController { it.addMediaItem(next.toMediaItem()) }
                }
            } finally {
                _isResolvingAutoplay.value = false
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimerEndsAt.value = endsAt
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _sleepTimerEndsAt.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerEndsAt.value = null
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        runOnController { it.setPlaybackSpeed(speed) }
    }

    /** Replaces the whole queue with [tracks] (a real, multi-item Media3 playlist) and starts
     *  playback at [startIndex] - Next/Previous/auto-advance for this queue are then native
     *  Player/MediaSession behaviour, exactly like MetroList's ListQueue. */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        autoplayRequestedForMediaId = null
        val safeIndex = startIndex.coerceIn(0, tracks.size - 1)
        val mediaItems = tracks.map { it.toMediaItem() }
        runOnController { controller ->
            controller.setMediaItems(mediaItems, safeIndex, 0L)
            controller.prepare()
            controller.play()
        }
    }

    fun addToQueue(track: Track) {
        runOnController { it.addMediaItem(track.toMediaItem()) }
    }

    fun removeFromQueue(index: Int) {
        runOnController { it.removeMediaItem(index) }
    }

    fun playQueueItem(index: Int) {
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        runOnController { controller ->
            controller.seekTo(index, 0L)
            controller.play()
        }
    }

    fun toggleShuffle() {
        runOnController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        runOnController { it.repeatMode = next.toPlayerRepeatMode() }
    }

    /** UI-driven Next/Previous. Mirrors the previous app's explicit "repeat-one replays the
     *  current track instead of advancing" behaviour; the system notification/lock-screen/
     *  Bluetooth buttons go through MediaSession's own default callback straight to the real
     *  Player, exactly like MetroList. */
    fun skipNext() {
        if (_repeatMode.value == RepeatMode.ONE) {
            runOnController { it.seekTo(0L); it.play() }
            return
        }
        runOnController { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); it.play() } }
    }

    fun skipPrevious() {
        if (_repeatMode.value == RepeatMode.ONE) {
            runOnController { it.seekTo(0L); it.play() }
            return
        }
        runOnController { if (it.hasPreviousMediaItem()) { it.seekToPreviousMediaItem(); it.play() } }
    }

    /** Ad-hoc "play this one track now" - used for standalone plays (Samples "Play Full Song",
     *  Jam remote song sync) that aren't part of building/browsing a queue. Replaces the whole
     *  real queue with just this track, same as the single-item behaviour the previous
     *  implementation always had. */
    fun play(track: Track, autoPlay: Boolean = true) {
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        autoplayRequestedForMediaId = null
        runOnController { controller ->
            controller.setMediaItem(track.toMediaItem())
            controller.prepare()
            if (autoPlay) controller.play() else controller.pause()
        }
    }

    fun pause() {
        runOnController { it.pause() }
    }

    fun resume() {
        runOnController { it.play() }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun stop() {
        stopProgressTracker()
        runOnController { it.stop() }
        _isPlaying.value = false
        _playbackPosition.value = 0L
        if (!isApplyingRemote) onLocalStop?.invoke(0L)
    }

    fun stopAndDismiss() {
        stopProgressTracker()
        runOnController { it.clearMediaItems() }
        _isPlaying.value = false
        _playbackPosition.value = 0L
        if (!isApplyingRemote) onLocalStop?.invoke(0L)
    }

    fun seekTo(position: Long) {
        if (isApplyingRemote) {
            suppressNextSeekBroadcast = true
            suppressSeekArmedAt = System.currentTimeMillis()
        }
        runOnController { it.seekTo(position) }
        _playbackPosition.value = position
        if (!isApplyingRemote) onLocalSeek?.invoke(position)
    }

    fun applyRemoteSongChange(song: Song, positionMs: Long, isPlaying: Boolean, updatedAtServerMs: Long) {
        isApplyingRemote = true
        val willChangePlayState = mediaController?.playWhenReady != isPlaying
        if (willChangePlayState) {
            suppressNextPlayPauseBroadcast = true
            suppressPlayPauseArmedAt = System.currentTimeMillis()
        }
        val track = TrackSongBridge.toTrack(song)
        pendingCatchUp = RemoteCatchUp(positionMs, isPlaying, updatedAtServerMs)
        pendingCatchUpMediaId = track.toMediaId()
        try {
            play(track, autoPlay = isPlaying)
        } finally {
            isApplyingRemote = false
        }
    }

    fun applyRemotePlayPause(isPlaying: Boolean, positionMs: Long) {
        isApplyingRemote = true
        val willChangePlayState = mediaController?.playWhenReady != isPlaying
        if (willChangePlayState) {
            suppressNextPlayPauseBroadcast = true
            suppressPlayPauseArmedAt = System.currentTimeMillis()
        }
        try {
            seekTo(positionMs)
            if (isPlaying) resume() else pause()
        } finally {
            isApplyingRemote = false
        }
    }

    fun applyRemoteSeek(positionMs: Long) {
        isApplyingRemote = true
        try {
            val currentPos = mediaController?.currentPosition ?: _playbackPosition.value
            val driftMs = kotlin.math.abs(currentPos - positionMs)
            if (driftMs > 700) seekTo(positionMs)
        } finally {
            isApplyingRemote = false
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                mediaController?.let {
                    if (it.isPlaying) _playbackPosition.value = it.currentPosition.coerceAtLeast(0L)
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracker()
        sleepTimerJob?.cancel()
        autoplayJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }
}

class SamplesPlayerManager {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    var activePlayer: androidx.media3.exoplayer.ExoPlayer? = null

    fun reportActiveState(isPlaying: Boolean, isBuffering: Boolean, positionMs: Long, durationMs: Long) {
        _isPlaying.value = isPlaying
        _isBuffering.value = isBuffering
        _playbackPosition.value = positionMs
        if (durationMs > 0) _duration.value = durationMs
    }

    fun togglePlayPause() {
        val player = activePlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        activePlayer?.pause()
    }
}
