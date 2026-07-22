package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * App-level playback facade used by every ViewModel (MusicViewModel, PlaylistViewModel,
 * JamViewModel, SamplesViewModel) and by MainActivity's mini player / Now Playing sheet.
 *
 * This does NOT hold an ExoPlayer itself. It connects a Media3 [MediaController] to the real
 * player living in [MusicService] (a MediaSessionService), and mirrors that controller's state
 * into StateFlows the UI can collect. Going through the session/controller (instead of a plain
 * local ExoPlayer) is what makes background playback + the system notification/lock-screen
 * controls work - see MusicService's doc comment for the full architecture rationale.
 *
 * Responsibilities beyond simple transport control:
 *  - Converts between the app's [Track] model and Media3 [MediaItem]s (see TrackMediaItem.kt),
 *    so `queue`/`currentTrack` can always be rebuilt straight from the controller's own timeline.
 *  - Autoplay: once the queue is down to its last item, asks [autoplayProvider] (wired up by
 *    MusicViewModel to MusicRepository.getAutoplayRecommendation) for a follow-up track and
 *    appends it to the same real timeline, so playback advances into it seamlessly instead of
 *    just stopping.
 *  - Error handling: a resolve/network failure surfaces as a Player error on the controller;
 *    this retries transient failures a couple of times, waits out real connectivity loss instead
 *    of burning through the queue, and otherwise skips the bad track and surfaces [playbackError].
 *  - Jam hooks: `onLocal*` callbacks fire for locally-initiated song/play-pause/seek/stop events
 *    (JamViewModel wires these to broadcast them), and `applyRemote*` applies an incoming Jam
 *    update without re-firing those same local callbacks (which would otherwise echo it right
 *    back out to the room).
 */
class MusicPlayer(private val context: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled error", throwable)
        _playbackError.value = throwable.message ?: "Playback error"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    private var controller: MediaController? = null
    private val controllerReady = CompletableDeferred<MediaController>()
    private var stallWatchdogJob: Job? = null

    // ---------------------------------------------------------------------
    // Public state
    // ---------------------------------------------------------------------

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

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

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** Wired up by MusicViewModel to MusicRepository.getAutoplayRecommendation. */
    var autoplayProvider: (suspend (current: Track, excludeIds: Set<Long>, recentTracks: List<Track>) -> Track?)? = null

    // Jam hooks - fired only for LOCALLY-initiated changes (see applyRemote* below for the
    // receiving side, which deliberately does NOT go through these).
    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null
    var onLocalStop: ((positionMs: Long) -> Unit)? = null

    // ---------------------------------------------------------------------
    // Internal bookkeeping
    // ---------------------------------------------------------------------

    private var errorRetryCount = 0
    private var networkWaitJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isFetchingAutoplay = false
    private var suppressNextLocalSongCallback = false
    private val recentlyPlayed = ArrayDeque<Track>()

    init {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                syncQueueFromController()
                _isPlaying.value = c.isPlaying
                _isShuffleEnabled.value = c.shuffleModeEnabled
                controllerReady.complete(c)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to MusicService", e)
                controllerReady.completeExceptionally(e)
            }
        }, ContextCompat.getMainExecutor(context))

        // Drives playbackPosition/duration (Media3 has no continuous position callback) and
        // proactively triggers autoplay shortly before the last queued item finishes, so the
        // next track is already appended by the time ExoPlayer would otherwise run out.
        scope.launch {
            while (true) {
                controller?.let { c ->
                    _playbackPosition.value = c.currentPosition.coerceAtLeast(0L)
                    _duration.value = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    val dur = _duration.value
                    if (dur > 0 &&
                        c.mediaItemCount > 0 &&
                        c.currentMediaItemIndex == c.mediaItemCount - 1 &&
                        dur - c.currentPosition < AUTOPLAY_LOOKAHEAD_MS
                    ) {
                        maybeAppendAutoplayTrack()
                    }
                }
                delay(500)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            val c = controllerReady.await()
            block(c)
        }
    }

    // ---------------------------------------------------------------------
    // Queue / transport controls
    // ---------------------------------------------------------------------

    /** Replaces the whole queue and starts playback at [startIndex]. */
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, tracks.size - 1)
        errorRetryCount = 0
        withController { c ->
            c.setMediaItems(tracks.map { it.toMediaItem() }, clampedIndex, 0L)
            c.playWhenReady = true
            c.prepare()
            c.play()
        }
    }

    /** Appends a single track to the end of the current queue without interrupting playback. */
    fun addToQueue(track: Track) {
        withController { c -> c.addMediaItem(track.toMediaItem()) }
    }

    /** Starts playback of exactly this one track, replacing whatever was queued before -
     *  used by "Play Full Song" from the Samples feed. */
    fun play(track: Track) {
        setQueue(listOf(track), 0)
    }

    fun pause() {
        val pos = controller?.currentPosition ?: _playbackPosition.value
        _isPlaying.value = false
        withController { c -> c.pause() }
        onLocalPlayPause?.invoke(false, pos)
    }

    fun resume() {
        val pos = controller?.currentPosition ?: _playbackPosition.value
        _isPlaying.value = true
        withController { c -> c.play() }
        onLocalPlayPause?.invoke(true, pos)
    }

    fun seekTo(positionMs: Long) {
        _playbackPosition.value = positionMs
        withController { c -> c.seekTo(positionMs) }
        onLocalSeek?.invoke(positionMs)
    }

    fun skipNext() {
        withController { c -> if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekToNext() }
    }

    /** Restarts the current track if more than a few seconds in (standard media-player
     *  convention), otherwise jumps to the previous queue item. */
    fun skipPrevious() {
        withController { c ->
            if (c.currentPosition > 3000L || !c.hasPreviousMediaItem()) {
                c.seekTo(0L)
            } else {
                c.seekToPreviousMediaItem()
            }
        }
    }

    /** Fully stops playback and clears the queue - the mini player's "X" button. */
    fun stopAndDismiss() {
        val pos = controller?.currentPosition ?: _playbackPosition.value
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
        _currentTrack.value = null
        _queue.value = emptyList()
        _queueIndex.value = -1
        _isPlaying.value = false
        _duration.value = 0L
        onLocalStop?.invoke(pos)
    }

    fun toggleShuffle() {
        withController { c ->
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            _isShuffleEnabled.value = c.shuffleModeEnabled
        }
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeatMode.value = next
        withController { c ->
            c.repeatMode = when (next) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    fun playQueueItem(index: Int) {
        withController { c ->
            if (index in 0 until c.mediaItemCount) {
                c.seekTo(index, 0L)
                c.play()
            }
        }
    }

    fun removeFromQueue(index: Int) {
        withController { c ->
            if (index in 0 until c.mediaItemCount) {
                c.removeMediaItem(index)
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val durationMs = minutes * 60_000L
        _sleepTimerEndsAt.value = System.currentTimeMillis() + durationMs
        sleepTimerJob = scope.launch {
            delay(durationMs)
            controller?.pause()
            _isPlaying.value = false
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
        withController { c -> c.setPlaybackSpeed(speed) }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    // ---------------------------------------------------------------------
    // Jam - applying a REMOTE update. Deliberately talks to the controller
    // directly (not via play()/pause()/seekTo() above) so it never re-fires
    // the onLocal* callbacks and echoes the update straight back to the room.
    // ---------------------------------------------------------------------

    fun applyRemoteSongChange(song: Song, positionMs: Long, isPlaying: Boolean, updatedAtServerMs: Long) {
        val track = TrackSongBridge.toTrack(song)
        // Compensate for time already spent in flight since the host's broadcast, so a
        // slightly-late update doesn't leave a receiving/joining device behind.
        val elapsed = (System.currentTimeMillis() - updatedAtServerMs).coerceAtLeast(0L)
        val adjustedPosition = if (isPlaying) positionMs + elapsed else positionMs

        suppressNextLocalSongCallback = true
        scope.launch {
            // Safety net: if for some reason no onMediaItemTransition ever fires for this
            // (e.g. it's a no-op update for the song already playing), don't leave the next
            // genuinely-local song change silently un-broadcast.
            delay(2000)
            suppressNextLocalSongCallback = false
        }
        withController { c ->
            c.setMediaItems(listOf(track.toMediaItem()), 0, adjustedPosition.coerceAtLeast(0L))
            c.playWhenReady = isPlaying
            c.prepare()
        }
    }

    fun applyRemotePlayPause(isPlaying: Boolean, positionMs: Long) {
        withController { c ->
            correctPositionIfDrifted(c, positionMs)
            if (isPlaying) c.play() else c.pause()
        }
    }

    fun applyRemoteSeek(positionMs: Long) {
        withController { c -> correctPositionIfDrifted(c, positionMs, thresholdMs = 800L) }
    }

    private fun correctPositionIfDrifted(controller: MediaController, targetPositionMs: Long, thresholdMs: Long = 1200L) {
        if (abs(controller.currentPosition - targetPositionMs) > thresholdMs) {
            controller.seekTo(targetPositionMs)
        }
    }

    // ---------------------------------------------------------------------
    // Controller state -> StateFlow mirroring
    // ---------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_BUFFERING) {
                startStallWatchdog()
            } else {
                stallWatchdogJob?.cancel()
            }
            if (playbackState == Player.STATE_READY) {
                errorRetryCount = 0
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncQueueFromController()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _isShuffleEnabled.value = shuffleModeEnabled
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            stallWatchdogJob?.cancel()
            val track = mediaItem?.toTrackOrNull()
            _currentTrack.value = track
            syncQueueFromController()

            if (track != null) {
                recentlyPlayed.addFirst(track)
                while (recentlyPlayed.size > MAX_RECENTLY_PLAYED) recentlyPlayed.removeLast()

                if (suppressNextLocalSongCallback) {
                    suppressNextLocalSongCallback = false
                } else {
                    onLocalSongChange?.invoke(track)
                }
            }
            maybeAppendAutoplayTrack()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    private fun syncQueueFromController() {
        val c = controller ?: return
        _queue.value = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).toTrackOrNull() }
        _queueIndex.value = c.currentMediaItemIndex
    }

    // ---------------------------------------------------------------------
    // Autoplay
    // ---------------------------------------------------------------------

    private fun maybeAppendAutoplayTrack() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.currentMediaItemIndex != c.mediaItemCount - 1) return // more queued up already
        if (isFetchingAutoplay) return
        val provider = autoplayProvider ?: return
        val current = _currentTrack.value ?: return

        isFetchingAutoplay = true
        _isResolvingAutoplay.value = true
        scope.launch {
            try {
                val excludeIds = _queue.value.map { it.id }.toSet()
                val next = provider(current, excludeIds, recentlyPlayed.toList())
                val c2 = controllerReady.await()
                // Re-check we're still on the last item - the user may have skipped away or
                // added their own next track while this was resolving.
                if (next != null && c2.mediaItemCount > 0 && c2.currentMediaItemIndex == c2.mediaItemCount - 1) {
                    c2.addMediaItem(next.toMediaItem())
                }
            } catch (e: Exception) {
                // Autoplay is best-effort - a failure here just means playback stops at the
                // end of the queue instead of continuing, not worth surfacing as an error.
                Log.w(TAG, "Autoplay lookup failed", e)
            } finally {
                isFetchingAutoplay = false
                _isResolvingAutoplay.value = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Error handling: retry transient failures, wait out real connectivity
    // loss, otherwise skip the bad track and surface playbackError once.
    // ---------------------------------------------------------------------

    // See STALL_WATCHDOG_MS's comment: a throttled CDN connection can trickle data slowly enough
    // that ExoPlayer never leaves STATE_BUFFERING and never throws an error, so nothing here would
    // otherwise notice or recover. If we're still buffering once the timeout elapses, force a
    // fresh HTTP request at the same position - the same thing manually seeking does.
    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = scope.launch {
            delay(STALL_WATCHDOG_MS)
            val c = controller ?: return@launch
            if (c.playbackState == Player.STATE_BUFFERING) {
                Log.w(TAG, "Buffering stalled ${STALL_WATCHDOG_MS}ms at position ${c.currentPosition} - nudging with self-seek")
                c.seekTo(c.currentPosition)
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val c = controller ?: return

        // error.message on a PlaybackException coming through MediaController is almost always
        // just Media3's generic wrapper text ("Source error") for ANY IOException thrown at the
        // data-source layer - the real reason (403 from a stale/rejected stream URL, timeout,
        // resolve failure, etc.) lives in error.cause instead. Across the MediaController IPC
        // boundary the cause is flattened to a RemoteException, but its message string still
        // carries the original cause's message through - so prefer that over the useless
        // wrapper text. This is also logged so it shows up in logcat even before retries exhaust.
        val diagnosticMessage = error.cause?.message ?: error.message ?: "Couldn't play this track."
        Log.e(TAG, "Playback error (errorCode=${error.errorCodeName}, attempt=${errorRetryCount + 1}): $diagnosticMessage", error)

        if (!isOnline()) {
            waitForNetworkThenRetry()
            return
        }

        errorRetryCount++
        if (errorRetryCount <= MAX_RETRIES_PER_TRACK) {
            c.prepare()
            return
        }

        errorRetryCount = 0
        _playbackError.value = diagnosticMessage
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.prepare()
        } else {
            c.pause()
        }
    }

    private fun waitForNetworkThenRetry() {
        if (networkWaitJob?.isActive == true) return
        networkWaitJob = scope.launch {
            while (!isOnline()) {
                delay(2000)
            }
            errorRetryCount = 0
            controller?.prepare()
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "MusicPlayer"
        private const val MAX_RETRIES_PER_TRACK = 2
        // googlevideo.com CDN can throttle a connection to a slow trickle instead of failing it
        // outright - bytes keep arriving just not fast enough to keep up with playback, so
        // ExoPlayer sits in STATE_BUFFERING forever with no IOException ever thrown (no error
        // event, nothing for handlePlaybackError's retry logic to catch). The only fix is a fresh
        // HTTP request, which is exactly what manually seeking does. This watchdog automates that:
        // if still buffering after this long, self-nudge with a seek to the current position.
        private const val STALL_WATCHDOG_MS = 12_000L
        private const val MAX_RECENTLY_PLAYED = 20
        private const val AUTOPLAY_LOOKAHEAD_MS = 8000L
    }
}
