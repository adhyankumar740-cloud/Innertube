package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.data.model.TrackSource
import com.example.data.network.InnerTubeStreamResolver
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

enum class RepeatMode { OFF, ONE, ALL }

class MusicPlayer(
    private val context: Context
) {

    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null
    // JAM FIX: stop()/stopAndDismiss() never went through onIsPlayingChanged (they set
    // _isPlaying.value = false directly), so closing a song on one device never
    // reached Firebase at all - the other device just kept playing/paused as-is with
    // no idea anything happened. onLocalStop lets JamViewModel broadcast that as a
    // pause so every other device in the room reflects the close too.
    var onLocalStop: ((positionMs: Long) -> Unit)? = null

    private var isApplyingRemote = false
    private var suppressNextPlayPauseBroadcast = false
    private var suppressPlayPauseArmedAt = 0L
    // JAM SYNC FIX: isApplyingRemote is set true/false SYNCHRONOUSLY around play()/seekTo()
    // calls, but the MediaController is IPC-backed - the actual onIsPlayingChanged /
    // onPositionDiscontinuity callbacks (and, for song changes, the relay network
    // resolve) land LATER, asynchronously, by which time isApplyingRemote has already
    // flipped back to false. That race made a remote-applied change get re-broadcast
    // as if it were a fresh local action, causing the other device to receive an echo
    // and re-apply it - the ping-pong that showed up as sync delay / devices fighting
    // each other / playback occasionally getting stuck. suppressNextPlayPauseBroadcast
    // already solved this for applyRemotePlayPause; suppressNextSeekBroadcast does the
    // same for seeks (applyRemoteSeek and the seekTo() inside applyRemotePlayPause),
    // and applyRemoteSongChange now also arms suppressNextPlayPauseBroadcast so the
    // *delayed* isPlayingChanged that fires once the relay resolve finishes is caught
    // too, no matter how long that resolve takes.
    //
    // SAFETY-NET FIX: a "suppress the next event" flag is only safe if that next event
    // is guaranteed to actually happen. If a remote song change never actually reaches
    // isPlaying=true (relay stuck/failed, endless buffering), the flag stayed armed
    // forever - so the NEXT genuinely local pause/resume from the user silently got
    // swallowed and never reached Firebase (this is why pause sometimes stopped
    // syncing to the other device after a buffering hiccup). Both suppress flags now
    // carry an "armed at" timestamp and expire after suppressExpiryMs, so a stuck
    // remote apply can only eat broadcasts for a few seconds, never indefinitely.
    private var suppressNextSeekBroadcast = false
    private var suppressSeekArmedAt = 0L
    // TUNING FIX: this was 8s, but a cold-cache relay /resolve (RelayService's own
    // readTimeout is 45s, and onPlayerError even retries once more after that) can
    // legitimately take far longer than 8s. When a remote Jam song-change triggered
    // a slow resolve, the suppress flag expired WHILE we were still waiting on the
    // network - so the eventual onIsPlayingChanged(true)/seek, once the resolve
    // finally landed, got broadcast back to Firebase as if it were a brand new local
    // action. That produced an echo that could yank the position back to 0 / restart
    // the track on every other device, and is a big part of why Jam felt like it
    // never really settled after joining/switching songs. 60s comfortably covers the
    // full resolve+one-retry window with room to spare.
    private val suppressExpiryMs = 60_000L

    // SYNC-DRIFT FIX: the room's playback timing (position/isPlaying/server
    // timestamp) at the moment a remote song change was received, captured by
    // applyRemoteSongChange() and consumed once by playYoutubeTrack()/
    // playItunesTrack() right after THIS device's own resolve/buffering finishes.
    // Without this, every device just started a new track at 0:00 the instant its
    // own (variable-length) relay resolve completed - a device whose resolve took
    // even a few seconds longer than another's ended up permanently that far
    // behind, with nothing ever correcting the drift afterward.
    private data class RemoteCatchUp(val positionMs: Long, val isPlaying: Boolean, val updatedAtServerMs: Long)
    private var pendingCatchUp: RemoteCatchUp? = null

    /** Seeks to compensate for however long resolving THIS track took, without
     *  broadcasting the seek back to Firebase (it's a local time-sync correction,
     *  not a user/host action). Mirrors applyRemoteSeek()'s isApplyingRemote +
     *  suppressNextSeekBroadcast pattern. */
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

    /** Computes the room's current position from a captured RemoteCatchUp (adding
     *  elapsed real time on top if it was playing) and seeks to it, clamped to the
     *  track's duration. Call once, right after playback has actually started for
     *  the track this catch-up was captured for. */
    private fun resolveAndApplyCatchUp(catchUp: RemoteCatchUp?, trackDurationMs: Long) {
        if (catchUp == null) return
        val target = if (catchUp.isPlaying) {
            catchUp.positionMs + (System.currentTimeMillis() - catchUp.updatedAtServerMs).coerceAtLeast(0L)
        } else {
            catchUp.positionMs.coerceAtLeast(0L)
        }
        val clamped = if (trackDurationMs > 0) target.coerceIn(0L, (trackDurationMs - 500L).coerceAtLeast(0L)) else target
        // Small gaps aren't worth a seek (barely audible, and avoids a pointless
        // micro-stutter on the common case where resolves finish close together).
        if (clamped > 700L) {
            applyCatchUpSeek(clamped)
        }
    }

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

    // Sleep timer: value is the epoch-millis moment playback should auto-pause,
    // or null when no timer is running. Exposed as an end-time (not a raw
    // countdown) so the UI can recompute "time remaining" itself on every
    // recomposition without this StateFlow needing to tick every second.
    private val _sleepTimerEndsAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndsAt: StateFlow<Long?> = _sleepTimerEndsAt.asStateFlow()
    private var sleepTimerJob: Job? = null

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** Starts (or replaces) the sleep timer. Pauses playback when it elapses - does not stop/dismiss, so resuming just needs a tap on Play. */
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

    /** 1.0 = normal speed. Pitch is left uncorrected (matches ExoPlayer's default PlaybackParameters behaviour). */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        runOnController { it.setPlaybackSpeed(speed) }
    }

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    // DEBUG: unlike _playbackError (which playYoutubeTrack resets to null the
    // instant the NEXT track starts trying, so a real exception message was
    // getting wiped out within milliseconds during auto-skip cascades before
    // anyone could ever read it), this only ever gets overwritten by a fresh
    // error - never silently cleared - so the phone-only debugging dialog in
    // MainActivity can actually show it long enough to screenshot.
    private val _lastStreamErrorDebug = MutableStateFlow<String?>(null)
    val lastStreamErrorDebug: StateFlow<String?> = _lastStreamErrorDebug.asStateFlow()

    private var hasReachedPlayingState = false
    private var consecutivePlaybackFailures = 0
    private val maxConsecutivePlaybackFailures = 3

    private var playOrder: List<Int> = emptyList()
    private var playOrderPos = 0

    private val recentlyPlayedIds = ArrayDeque<Long>()
    private val recentlyPlayedTracks = ArrayDeque<Track>()
    private val recentlyPlayedCap = 40

    private var loadedYoutubeVideoId: String? = null

    // Ek hi videoId ke liye relay resolve sirf ek baar retry hota hai
    // (mid-playback error pe) - taaki relay baar-baar down hone par hum
    // infinite retry loop me na phasein; ek retry ke baad bhi fail ho to
    // track ko skip/error kar dete hain.
    private var relayRetriedForVideoId: String? = null

    private var bufferingWatchdogJob: Job? = null
    // BUG FIX (root cause of "song buffers then just skips to the next one"):
    // this was a single 12s timer with NO retry - if a track hadn't reached
    // STATE_READY within 12s of starting to load (very possible on a cold
    // relay resolve + a slow/weak connection, which is exactly when buffering
    // is worst), it was instantly treated as a hard failure and skipped, even
    // though it was still genuinely loading and would have played fine given
    // a few more seconds. onPlayerError already retries once before giving up
    // - the watchdog now does the same instead of skipping on the very first
    // timeout. 18s per attempt (36s total across both attempts) comfortably
    // covers a slow cold resolve without making a truly-stuck track hang
    // forever.
    private val bufferingTimeoutMs = 18_000L
    // Mirrors relayRetriedForVideoId's one-retry-then-give-up pattern, but for
    // the watchdog's own timeout path specifically.
    private var watchdogRetriedForVideoId: String? = null

    // --- Next-track preloading (removes the relay-resolve wait when advancing) ---
    // The relay's /resolve endpoint can be slow on a cold cache (it downloads/
    // converts the video from YouTube on first resolve for that videoId), which
    // is exactly the pause users saw between songs. As soon as a track starts
    // playing, we resolve the *next* track's stream URL in the background and
    // cache it here, so by the time skipNext()/auto-advance actually happens,
    // the URL is usually already sitting in the cache and playback can start
    // immediately instead of waiting on a fresh network round trip.
    private val preloadedStreamUrls = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 5
    }

    // BANDWIDTH-CONTENTION FIX (part of "buffers right after a song ends"):
    // preloadNextTrack()/preloadAutoplayCandidate() used to CacheWriter the
    // ENTIRE next track while the current one was still actively streaming
    // live. On anything less than a fast, uncontended connection, both
    // downloads competed for the same bandwidth - which could itself stall
    // the CURRENTLY playing track, and on a slow link the full-track
    // prefetch often hadn't finished by the time the track actually ended
    // anyway, so the transition still had to fall back to live fetching for
    // the remainder. Capping the prefetch to a fixed amount of audio (~90s
    // at a typical ~128kbps stream) is enough to let the next track start
    // and play smoothly the instant it's needed - it finishes far sooner and
    // takes far less bandwidth away from the track still playing - while
    // ExoPlayer's own cache-through data source simply fetches the rest live
    // once that next track actually starts, same as any normal track does.
    private val preloadCapBytes = 1_500_000L
    private var preloadJob: Job? = null

    // AUTOPLAY-GAP FIX (root cause of "song khatam hone ke baad next song
    // bajne me 10-15 sec lagta hai"): preloadNextTrack() below only pre-
    // resolves+pre-caches a track that's already IN the queue (peekNextTrack()
    // returns non-null). The moment the queue actually runs out and playback
    // has to fall back to autoplayProvider (recommendations), peekNextTrack()
    // returns null on purpose (see its own comment) - so nothing was ever
    // preloaded for that transition, and triggerAutoplay() paid the FULL cost
    // (recommendation lookup + a cold relay /resolve, which the preload
    // comment above already notes can be slow) only AFTER the track had
    // already ended. For an app that's mostly played by "let it keep going"
    // rather than a long manually-built queue, that's the transition that
    // happens on almost every song - hence a consistent 10-15s gap every time,
    // not just once at the end of a playlist.
    // Fix: whenever peekNextTrack() has nothing, speculatively call the same
    // autoplayProvider early (while the current track is still playing) and
    // pre-resolve/pre-cache whatever it returns, exactly like a normal queued
    // next-track. triggerAutoplay() then reuses this candidate instead of
    // hitting the provider/relay cold.
    private var autoplayPreloadJob: Job? = null
    private var pendingAutoplayTrack: Track? = null
    // Guards against staleness: only valid for a candidate computed against
    // the track that's STILL playing when triggerAutoplay() actually runs -
    // if the user skipped/changed tracks in between, this candidate no longer
    // reflects "what should play after the current track".
    private var pendingAutoplaySourceTrackId: Long? = null

    // Jab tak preloadJob URL resolve karke iske actual audio bytes disk cache
    // me utaar raha hota hai, uska CacheWriter yahan rakha jaata hai - taaki
    // agar is beech me prediction badal jaaye (user shuffle/skip kar de,
    // naya "next" track ban jaaye) to hum turant isko cancel() karke bandwidth
    // naye sahi target pe laga sakein, purane par waste na ho.
    private var activeCacheWriter: CacheWriter? = null

    private var progressJob: Job? = null
    // BACKGROUND-PLAYBACK FIX: this was `CoroutineScope(Dispatchers.Main + Job())`.
    // A plain Job() propagates any *uncaught* exception from one child coroutine
    // by cancelling the whole scope's Job - permanently. triggerAutoplay() (the
    // code path that finds and starts the NEXT track when a song ends) launches
    // on this same `scope`, and does real network I/O (relay resolve, autoplay
    // recommendation lookup) that's far more likely to throw while the app is
    // minimized/Doze-restricted. The first time that happened anywhere on this
    // scope, every *subsequent* scope.launch{} call (including every future
    // "song ended, play the next one" and the progress-position ticker) became
    // a silent no-op for the rest of the process's life - looking exactly like
    // "playback just stops advancing once the app is minimized, only works
    // again after force-closing and reopening". SupervisorJob() makes a failure
    // in one child fail only that child, never the shared scope.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // TRACK-TRANSITION WAKE-LOCK FIX (root cause of "song ends while minimized,
    // next song only plays after reopening the app"): ExoPlayer's own
    // setWakeMode(WAKE_MODE_NETWORK) wake lock in PlaybackService is tied to
    // ExoPlayer's OWN playing state - it's only held while playWhenReady/
    // isPlaying is true. The exact moment a track finishes (STATE_ENDED),
    // ExoPlayer is idle/ended, so that wake lock is already released - right
    // when it's needed most, because advancing to the next track needs a
    // network round trip (relay /resolve, or the autoplay recommendation
    // lookup) before playback can resume. If the screen is off at that exact
    // instant (the whole point of background playback), the CPU can suspend
    // or Doze can freeze background network access immediately, stalling that
    // resolve call mid-flight - the track then just sits paused until the
    // user reopens the app, which wakes the process back up and lets the
    // stuck network call finally finish. This wake lock explicitly covers
    // that gap: acquired the instant a track ends, released once the next
    // track is actually playing again (or playback gives up). A short
    // safety-timeout on acquire() means a stuck resolve can never hold it
    // forever and drain the battery even if a release call is somehow missed.
    private var transitionWakeLock: PowerManager.WakeLock? = null

    private fun acquireTransitionWakeLock() {
        if (transitionWakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            transitionWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Nirvana:TrackTransitionWakeLock"
            ).apply {
                setReferenceCounted(false)
                // BUG FIX (root cause of "minimize karne par ek song ke baad
                // band ho jata hai"): this was 20_000L, matching only the
                // FIRST relay-resolve attempt's own withTimeout(20_000L) in
                // playYoutubeTrack(). But on a resolve failure, retryRelayResolve()
                // runs a SECOND withTimeout(20_000L) attempt right after - so the
                // real worst case (first attempt times out, then a retry) is up
                // to ~40s of network work, not 20s. With the old 20s wake lock,
                // the CPU could suspend/Doze mid-retry exactly when the screen
                // was off, silently stalling that retry forever - which looked
                // exactly like "played one song, then just stopped" until the
                // app was reopened. 50s comfortably covers resolve + one retry
                // + a small margin for actually starting playback afterward.
                acquire(50_000L)
            }
        } catch (e: Exception) {
            Log.w("MusicPlayer", "Could not acquire transition wake lock", e)
        }
    }

    private fun releaseTransitionWakeLock() {
        try {
            transitionWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // already released (e.g. by its own timeout) - safe to ignore
        }
        transitionWakeLock = null
        // SAFETY NET: this is already the shared "a track-transition attempt is
        // over now, one way or another" signal (called on real success in
        // onPlaybackStateChanged/onIsPlayingChanged, AND on giving up entirely in
        // registerPlaybackFailureAndMaybeStop/triggerAutoplay's "nothing found"
        // branch) - piggyback the same clear here so the placeholder "Loading..."
        // notification from showLoadingNotification() can never get stuck showing
        // forever after a failure path that never reaches a real setMediaItem()
        // call (the 3 success-path call sites already clear it too; calling this
        // an extra time when it's already been cleared is a harmless no-op).
        PlaybackBridge.onMediaItemReady?.invoke()
    }

    init {
        PlaybackBridge.onNext = { skipNext() }
        PlaybackBridge.onPrevious = { skipPrevious() }
        PlaybackBridge.onSeek = { position -> seekTo(position) }

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                mediaController?.addListener(playerListener)
                pendingActions.forEach { it() }
                pendingActions.clear()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun runOnController(action: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller != null) action(controller) else pendingActions.add { mediaController?.let(action) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            // Only updates local UI state (buffering spinner, progress ticker) now -
            // see onPlayWhenReadyChanged() below for the Jam broadcast, which is the
            // signal that actually reflects a genuine play/pause action.
            _isPlaying.value = isPlayingNow
            if (isPlayingNow) {
                startProgressTracker()
                // The next track (or a repeat/retry of the current one) is actually
                // audible again now, so the gap the transition wake lock was
                // covering is over - see acquireTransitionWakeLock() above.
                releaseTransitionWakeLock()
            } else {
                stopProgressTracker()
            }
        }

        // JAM LOOP FIX: isPlaying (above) also flips to false the instant ExoPlayer
        // enters STATE_BUFFERING - even though playWhenReady never changed and
        // nobody actually paused anything - and flips back to true the moment
        // buffering clears. Broadcasting from onIsPlayingChanged (the old code)
        // meant every transient stall got sent to Firebase as a real pause, then a
        // real resume, right after. This bit hardest exactly on a seek into a chunk
        // that wasn't downloaded/cached yet (locally OR on the other device after
        // a remote seek was applied): both sides would buffer, both would
        // broadcast a spurious pause, the other side would dutifully pause in
        // response, buffering would clear on both, both would broadcast "resume" -
        // and if the network was still shaky at that position, straight into
        // another stall - a pause/resume cycle that kept both devices stuck right
        // there. playWhenReady only actually changes on a real play()/pause() call
        // (ours or a remote one we applied) or a forced system pause (e.g. audio
        // focus loss) - never on a buffering stall - so it's the correct signal to
        // broadcast on instead.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val stillArmed = suppressNextPlayPauseBroadcast &&
                (System.currentTimeMillis() - suppressPlayPauseArmedAt) < suppressExpiryMs
            suppressNextPlayPauseBroadcast = false
            if (!stillArmed) {
                onLocalPlayPause?.invoke(playWhenReady, _playbackPosition.value)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // ExoPlayer ab hamesha asli source hai (ITUNES preview ya relay-
            // resolved YouTube audio), isliye state seedha reflect hoti hai.
            when (state) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    _duration.value = (mediaController?.duration ?: 0L).coerceAtLeast(0L)
                    // BUG FIX: this was never set anywhere, so it was permanently
                    // false for every track. That made handleTrackEnded() treat
                    // EVERY normal, fully-played song as a "failure" and run it
                    // through registerPlaybackFailureAndMaybeStop() - which halts
                    // playback entirely after 3 songs in a row (even though all 3
                    // played perfectly fine start to finish). In a Jam this made
                    // one device randomly stop dead while the other kept going,
                    // looking exactly like "song sirf ek device pe chalta hai".
                    hasReachedPlayingState = true
                    // WAKE-LOCK FIX: the track has now actually started playing,
                    // so whatever wake lock was covering its startup (see
                    // acquireTransitionWakeLock() calls in playYoutubeTrack/
                    // playItunesTrack/handleTrackEnded) is no longer needed -
                    // release it here so it's tied to "did playback actually
                    // begin", the same real success signal for every path that
                    // can start a track (search/tap, skip, autoplay, transition),
                    // instead of relying only on its own 50s safety-timeout.
                    releaseTransitionWakeLock()
                }
                Player.STATE_ENDED -> handleTrackEnded()
                else -> {}
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Lock-screen/notification seek bar drag hone par yeh fire hota hai
            // (system seek seedha MediaSession ke player pe hoti hai, MusicPlayer
            // ke apne seekTo() se hoke nahi) - isliye yahan se local state aur
            // remote (Jam) listeners ko sync karna zaroori hai.
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
            val track = _currentTrack.value ?: return
            _lastStreamErrorDebug.value = "[ExoPlayer onPlayerError - 1st] ${error.errorCodeName}: ${error.message} | cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message} (track=${track.title})"
            when (track.source) {
                TrackSource.YOUTUBE -> {
                    val videoId = track.youtubeVideoId
                    // ROOT CAUSE FIX: this is exactly the "WEB_REMIX URL 403'd on the real
                    // ExoPlayer GET" case YTPlayerUtils.markWebRemixFailed()/webRemixFailedIds
                    // was built for (see its doc comment) - resolution skips HEAD-validating
                    // WEB_REMIX and lets ExoPlayer try it directly to save a round-trip, but
                    // nothing was ever calling markWebRemixFailed() when that direct try then
                    // failed here - so every retry kept re-resolving the SAME fragile WEB_REMIX
                    // client instead of falling through to a validated fallback client
                    // (VISIONOS etc.). That's why every track was consistently 403ing instead
                    // of just occasionally.
                    if (videoId != null) {
                        com.example.data.youtube.YTPlayerUtils.markWebRemixFailed(videoId)
                    }
                    // Ek hi videoId ke liye ek baar relay ko fresh URL ke saath
                    // phir try karte hain (aksar error expired/one-time signed
                    // url ki wajah se hoti hai, poore video ke unavailable hone
                    // ki wajah se nahi) - dusri baar fail hone par track skip.
                    if (videoId != null && relayRetriedForVideoId != videoId) {
                        relayRetriedForVideoId = videoId
                        Log.e("MusicPlayer", "Relay audio playback error, retrying relay resolve once", error)
                        retryRelayResolve(track, videoId, catchUp = null)
                    } else {
                        Log.e("MusicPlayer", "Relay audio playback error again, giving up on this track", error)
                        _playbackError.value = "Yeh gaana stream nahi ho paya."
                        _lastStreamErrorDebug.value = "[ExoPlayer onPlayerError] ${error.errorCodeName}: ${error.message} | cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message} (track=${track.title})"
                        registerPlaybackFailureAndMaybeStop()
                    }
                }
                TrackSource.ITUNES -> {
                    _lastStreamErrorDebug.value = "[ExoPlayer onPlayerError - ITUNES] ${error.errorCodeName}: ${error.message} (track=${track.title})"
                    registerPlaybackFailureAndMaybeStop()
                }
                else -> {}
            }
        }
    }

    // NOTIFICATION-ARTWORK FIX: none of the three MediaMetadata builders below
    // ever called .setArtworkUri() - only title/artist were set. Media3's
    // MediaSessionService builds the system media notification (and lock
    // screen / Bluetooth / Android Auto artwork) directly from this
    // MediaMetadata, so every notification showed just the generic app icon
    // with no album art, no matter what was playing. One shared helper so all
    // three playback paths (relay retry, iTunes preview, YouTube relay) build
    // metadata the same, correct way.
    private fun buildMediaMetadata(track: Track): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist ?: "Unknown Artist")
        if (!track.artworkUrl.isNullOrBlank()) {
            builder.setArtworkUri(Uri.parse(track.artworkUrl))
        }
        // SEEK-BAR FIX ("seek bar in the control panel disappears"): this
        // never told the session a duration, so the system media
        // notification/lock-screen/quick-settings seek bar depended entirely
        // on ExoPlayer figuring the duration out for itself from the stream -
        // which, for a network-fetched/cache-through source, isn't always
        // known immediately and can be briefly unknown again across a track
        // change or a rebuffer, making the seek bar blink out exactly at
        // those moments. Track duration is already known upfront (it's in
        // our own data), so setting it directly here gives the system UI a
        // duration to show right away and keeps it stable regardless of what
        // ExoPlayer itself currently reports.
        if (track.durationMs > 0) {
            builder.setDurationMs(track.durationMs)
        }
        return builder.build()
    }

    private fun retryRelayResolve(track: Track, videoId: String, catchUp: RemoteCatchUp?, autoPlay: Boolean = true) {
        scope.launch {
            try {
                val streamUrl = withTimeout(20_000L) {
                    withContext(Dispatchers.IO) {
                        InnerTubeStreamResolver.resolveStreamUrl(context, videoId)
                    }
                }
                if (loadedYoutubeVideoId != videoId) return@launch // stale, user ne dusra gaana chala diya
                runOnController { controller ->
                    val metadata = buildMediaMetadata(track)

                    val mediaItem = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaId("relay_${track.id}")
                        .setMediaMetadata(metadata)
                        .build()

                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    if (autoPlay) controller.play() else controller.pause()
                    PlaybackBridge.onMediaItemReady?.invoke()
                }
                resolveAndApplyCatchUp(catchUp, track.durationMs)
                startProgressTracker()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Stream resolve retry failed for $videoId, giving up on this track", e)
                if (loadedYoutubeVideoId != videoId) return@launch
                // TEMP DEBUG: show the real exception on-screen (phone-only debugging, no adb).
                val label = if (e is TimeoutCancellationException) "Stream resolve TIMED OUT (hung >20s)" else "Stream fail"
                val msg = "$label: ${e.javaClass.simpleName}: ${e.message}"
                _playbackError.value = msg
                _lastStreamErrorDebug.value = "[retry] $msg (track=${track.title})"
                registerPlaybackFailureAndMaybeStop()
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        _queue.value = tracks
        rebuildPlayOrder(anchorIndex = startIndex.coerceIn(0, tracks.size - 1))
        _queueIndex.value = playOrder[playOrderPos]
        play(tracks[playOrder[playOrderPos]])
    }

    fun addToQueue(track: Track) {
        _queue.value = _queue.value + track
        playOrder = playOrder + (_queue.value.size - 1)
        preloadNextTrack()
    }

    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return
        val removedTrackWasCurrent = index == _queueIndex.value
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        rebuildPlayOrder(anchorIndex = (_queueIndex.value - if (index < _queueIndex.value) 1 else 0).coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0)))
        if (removedTrackWasCurrent && _queue.value.isNotEmpty()) {
            _queueIndex.value = playOrder[playOrderPos]
            play(_queue.value[playOrder[playOrderPos]])
        }
    }

    fun playQueueItem(index: Int) {
        val tracks = _queue.value
        if (index !in tracks.indices) return
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        playOrderPos = playOrder.indexOf(index).coerceAtLeast(0)
        _queueIndex.value = index
        play(tracks[index])
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
        rebuildPlayOrder(anchorIndex = _queueIndex.value.coerceAtLeast(0))
        preloadNextTrack()
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        preloadNextTrack()
    }

    private fun rebuildPlayOrder(anchorIndex: Int) {
        val tracks = _queue.value
        if (tracks.isEmpty()) {
            playOrder = emptyList()
            playOrderPos = 0
            return
        }
        val safeAnchor = anchorIndex.coerceIn(0, tracks.size - 1)
        playOrder = if (_isShuffleEnabled.value) {
            val rest = tracks.indices.filter { it != safeAnchor }.shuffled()
            listOf(safeAnchor) + rest
        } else {
            tracks.indices.toList()
        }
        playOrderPos = playOrder.indexOf(safeAnchor).coerceAtLeast(0)
    }

    fun skipNext() = advance(1)
    fun skipPrevious() = advance(-1)

    private fun advance(direction: Int) {
        val tracks = _queue.value
        if (tracks.isEmpty()) return

        if (_repeatMode.value == RepeatMode.ONE) {
            _currentTrack.value?.let { play(it) }
            return
        }

        val nextPos = playOrderPos + direction
        when {
            nextPos in playOrder.indices -> {
                playOrderPos = nextPos
                val idx = playOrder[playOrderPos]
                _queueIndex.value = idx
                play(tracks[idx])
            }
            direction > 0 && _repeatMode.value == RepeatMode.ALL -> {
                playOrderPos = 0
                val idx = playOrder[0]
                _queueIndex.value = idx
                play(tracks[idx])
            }
            direction > 0 -> triggerAutoplay()
            else -> {
                _currentTrack.value?.let { seekTo(0L); if (!_isPlaying.value) resume() }
            }
        }
    }

    private fun triggerAutoplay() {
        val current = _currentTrack.value ?: return
        val provider = autoplayProvider ?: return
        // AUTOPLAY-GAP FIX: if preloadAutoplayCandidate() already resolved
        // (and pre-cached) a recommendation for THIS track while it was still
        // playing, use it directly - this is what actually removes the
        // 10-15s gap, since neither the recommendation lookup nor the relay
        // resolve have to happen again down in play()/playYoutubeTrack().
        val preloadedCandidate = pendingAutoplayTrack.takeIf { pendingAutoplaySourceTrackId == current.id }
        pendingAutoplayTrack = null
        pendingAutoplaySourceTrackId = null
        scope.launch {
            _isResolvingAutoplay.value = true
            try {
                // HANG FIX (root cause of "song ends naturally and nothing
                // happens - no error, no next song, just stuck"): every other
                // network call in this class (relay resolve, retry) is
                // wrapped in withTimeout - this one wasn't. getAutoplayRecommendation()
                // does a genre lookup plus several search calls with no
                // deadline of its own, so if any single one of them ever hung
                // (bad connection, slow DNS, a stuck request - most likely
                // right as the app goes to background) this coroutine just
                // sat there forever: no exception, so the catch block below
                // never ran, _isResolvingAutoplay never cleared, and nothing
                // ever played next. withTimeout guarantees this always either
                // succeeds or throws within 20s, so the catch block's
                // existing isOnline()-aware retry logic actually gets a
                // chance to run.
                val next = preloadedCandidate ?: withTimeout(20_000L) {
                    val excludeIds = (_queue.value.map { it.id } + recentlyPlayedIds).toSet()
                    val recentTracks = _queue.value + recentlyPlayedTracks
                    provider(current, excludeIds, recentTracks)
                }
                if (next != null) {
                    _queue.value = _queue.value + next
                    val newIndex = _queue.value.size - 1
                    playOrder = playOrder + newIndex
                    playOrderPos = playOrder.size - 1
                    _queueIndex.value = newIndex
                    play(next)
                } else {
                    // Pehle yahan kuch nahi hota tha - button dabane pe koi
                    // response hi nahi milta tha (na error, na naya gaana),
                    // isliye "Next" kaam hi nahi karta lagta tha. Ab user ko
                    // clear pata chalega ki koi similar gaana nahi mila
                    // (usually YouTube API key/quota issue ya genre match na milna).
                    _playbackError.value = "Koi agla gaana nahi mila - YouTube API key/quota check karo."
                    // Nothing is going to start playing, so nothing more to cover.
                    releaseTransitionWakeLock()
                }
            } catch (e: Exception) {
                // ROOT-CAUSE FIX ("song ends while app is minimized, next
                // song just never starts - looks like playback stopped"):
                // this whole block used to have NO catch. provider(...)
                // does real network I/O (recommendation lookup, then a
                // relay/stream resolve) which throws far more often right
                // after the app is minimized and the screen turns off -
                // exactly when a background network blip or a brief Doze/
                // battery-saver restriction is most likely. With no catch,
                // that exception just killed THIS coroutine silently:
                // _isResolvingAutoplay never even reset, no error shown, no
                // retry, and - critically - advance(1) never ran again, so
                // nothing ever played next. If it's specifically a
                // connectivity problem, wait for the network to actually
                // come back and retry automatically instead of giving up;
                // otherwise show the same error as the "no recommendation
                // found" case above and stop waiting.
                Log.w("MusicPlayer", "triggerAutoplay failed for ${current.title}", e)
                if (!isOnline()) {
                    waitForNetworkThenRetry()
                } else {
                    _playbackError.value = "Agla gaana load nahi ho paaya - dobara try kar rahe hain."
                    registerPlaybackFailureAndMaybeStop()
                }
            } finally {
                _isResolvingAutoplay.value = false
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private var reconnectCallback: ConnectivityManager.NetworkCallback? = null

    /** Holds the transition wake lock and waits for connectivity to actually
     *  come back (instead of repeatedly failing/counting against the give-up
     *  limit) before retrying the next-track advance - covers the common
     *  "screen just turned off, network briefly cut out" case that used to
     *  need reopening the app to recover from. */
    private fun waitForNetworkThenRetry() {
        if (reconnectCallback != null) return // already waiting on one
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            advance(1)
            return
        }
        acquireTransitionWakeLock()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { cm.unregisterNetworkCallback(this) } catch (e: Exception) { /* already gone */ }
                reconnectCallback = null
                scope.launch { advance(1) }
            }
        }
        reconnectCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
            // SAFETY-TIMEOUT FIX (root cause of "song ends, nothing happens,
            // no error, ever - only reopening/force-restarting the app
            // recovers"): onAvailable() below only fires on a genuine
            // transition to available. If isOnline() false-negatived even
            // once (a real ConnectivityManager quirk - it can briefly report
            // no active/validated network right as a track ends, even though
            // the connection never actually dropped), there is no transition
            // left to wait for, so onAvailable() never fires - this callback
            // then sits registered forever, completely silently: no
            // snackbar (that only comes from paths that reach
            // _playbackError), no log, no retry, no eventual give-up. This
            // bounds that wait: after 15s with no transition, stop waiting
            // and just retry anyway via the normal advance(1) path, which
            // still has its own error handling/give-up logic if the retry
            // itself fails for a real reason.
            scope.launch {
                delay(15_000L)
                if (reconnectCallback === callback) {
                    try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { /* already gone */ }
                    reconnectCallback = null
                    Log.w("MusicPlayer", "waitForNetworkThenRetry: timed out waiting for network callback, retrying anyway")
                    advance(1)
                }
            }
        } catch (e: Exception) {
            reconnectCallback = null
            releaseTransitionWakeLock()
        }
    }

    private fun handleTrackEnded() {
        // Cover the network gap between "this track just ended" and "the next
        // one is actually playing" - see acquireTransitionWakeLock() for why.
        acquireTransitionWakeLock()
        if (!hasReachedPlayingState) {
            registerPlaybackFailureAndMaybeStop()
            return
        }
        advance(1)
    }

    private fun registerPlaybackFailureAndMaybeStop() {
        // Same connectivity check as triggerAutoplay's catch block: a resolve
        // failure caused by having no network *right now* (common the moment
        // the screen turns off) isn't a broken track - don't count it against
        // the 3-strikes give-up limit, just wait for the network and retry.
        if (!isOnline()) {
            _isBuffering.value = false
            _isPlaying.value = false
            waitForNetworkThenRetry()
            return
        }
        consecutivePlaybackFailures++
        if (consecutivePlaybackFailures >= maxConsecutivePlaybackFailures) {
            consecutivePlaybackFailures = 0
            _isBuffering.value = false
            _isPlaying.value = false
            _playbackError.value = "Kai gaane play nahi ho paaye, ruk gaya - kuch aur try karein."
            Log.e("MusicPlayer", "Stopping auto-skip after $maxConsecutivePlaybackFailures playback failures in a row")
            // Giving up entirely - nothing more will start playing, so hold no wake lock.
            releaseTransitionWakeLock()
            return
        }
        advance(1)
    }

    private fun trackRecentlyPlayed(track: Track) {
        if (recentlyPlayedIds.lastOrNull() != track.id) {
            recentlyPlayedIds.addLast(track.id)
            recentlyPlayedTracks.addLast(track)
            while (recentlyPlayedIds.size > recentlyPlayedCap) recentlyPlayedIds.removeFirst()
            while (recentlyPlayedTracks.size > recentlyPlayedCap) recentlyPlayedTracks.removeFirst()
        }
    }

    fun play(track: Track, autoPlay: Boolean = true) {
        trackRecentlyPlayed(track)
        if (track.source == TrackSource.YOUTUBE) {
            playYoutubeTrack(track, autoPlay)
        } else {
            playItunesTrack(track, autoPlay)
        }
        preloadNextTrack()
    }

    // Figures out which track would play next without changing any state -
    // mirrors advance()'s own logic (repeat-one/repeat-all/queue order) so the
    // prediction stays correct with shuffle on. Returns null when the next
    // step would require asking the autoplay provider, since resolving that
    // early would trigger its side effects (network calls, quota use) before
    // the user has actually reached the end of the queue.
    private fun peekNextTrack(): Track? {
        val tracks = _queue.value
        if (tracks.isEmpty() || playOrder.isEmpty()) return null
        if (_repeatMode.value == RepeatMode.ONE) return _currentTrack.value
        val nextPos = playOrderPos + 1
        return when {
            nextPos in playOrder.indices -> tracks.getOrNull(playOrder[nextPos])
            _repeatMode.value == RepeatMode.ALL -> tracks.getOrNull(playOrder[0])
            else -> null
        }
    }

    // Do kaam ek ke baad ek, current track chalte-chalte background me:
    //  1) Agle track ka relay stream URL resolve karo (jaisa pehle hota tha)
    //  2) URL milte hi, USSI TIME uske asli audio chunks disk cache me utaarna
    //     shuru kar do (CacheWriter se) - "chunks jama karna", ExoPlayer ke
    //     asli play hone se pehle hi.
    // Isse buffering sirf pehli baar hoti hai: jab track 2 par switch hota
    // hai, uske bytes pehle se cache me mil jaate hai (PlaybackService ka
    // ExoPlayer isi shared PlaybackCache se padhta hai), aur play() ke end
    // me yeh function dobara call hoke turant track 3 ke chunks jama karna
    // shuru kar deta hai - is tarah har baar sirf agle ek gaane ka hi
    // prefetch chalta hai, kabhi bhi ek se zyada cheezein ek saath download
    // nahi hoti.
    private fun preloadNextTrack() {
        preloadJob?.cancel()
        activeCacheWriter?.cancel()
        activeCacheWriter = null
        autoplayPreloadJob?.cancel()
        pendingAutoplayTrack = null
        pendingAutoplaySourceTrackId = null

        val next = peekNextTrack()
        if (next == null) {
            // Nothing queued to play next - this is exactly the case that
            // used to fall through with no preloading at all. See the
            // AUTOPLAY-GAP FIX comment above pendingAutoplayTrack.
            preloadAutoplayCandidate()
            return
        }
        if (next.source != TrackSource.YOUTUBE) return
        val videoId = next.youtubeVideoId ?: return

        preloadJob = scope.launch {
            try {
                val streamUrl = preloadedStreamUrls[videoId] ?: run {
                    val resolved = withContext(Dispatchers.IO) {
                        InnerTubeStreamResolver.resolveStreamUrl(context, videoId)
                    }
                    preloadedStreamUrls[videoId] = resolved
                    resolved
                }

                withContext(Dispatchers.IO) {
                    val dataSource =
                        PlaybackCache.cacheDataSourceFactory(context).createDataSource() as CacheDataSource
                    val cappedSpec = DataSpec.Builder()
                        .setUri(Uri.parse(streamUrl))
                        .setLength(preloadCapBytes)
                        .build()
                    val cacheWriter = CacheWriter(
                        dataSource,
                        cappedSpec,
                        /* temporaryBuffer = */ null,
                        /* progressListener = */ null
                    )
                    activeCacheWriter = cacheWriter
                    cacheWriter.cache()
                }
            } catch (e: Exception) {
                // Best-effort prefetch/pre-cache only - agar yeh fail ho jaaye
                // (relay down, cache full, ya prediction badalne se cancel ho
                // gaya) to playYoutubeTrack() apna normal resolve+play flow
                // chalayega jaise pehle hota tha, bas ek extra buffering ke
                // saath jo warna avoid ho jaati.
                Log.w("MusicPlayer", "Next-track prefetch/pre-cache failed or cancelled for $videoId", e)
            } finally {
                activeCacheWriter = null
            }
        }
    }

    // Speculative version of preloadNextTrack() for when the queue is about
    // to run out. Asks autoplayProvider EARLY (while the current track is
    // still playing, well before STATE_ENDED) so the recommendation lookup
    // and relay resolve both happen in the background with time to spare,
    // instead of after the track has already finished. triggerAutoplay()
    // picks this up via pendingAutoplayTrack instead of calling the provider
    // again from scratch.
    private fun preloadAutoplayCandidate() {
        val current = _currentTrack.value ?: return
        val provider = autoplayProvider ?: return
        val sourceId = current.id

        autoplayPreloadJob = scope.launch {
            try {
                val excludeIds = (_queue.value.map { it.id } + recentlyPlayedIds).toSet()
                val recentTracks = _queue.value + recentlyPlayedTracks
                // Same hang-guard as triggerAutoplay() - this runs in the
                // background so a stuck call here wouldn't freeze playback
                // directly, but it would mean this speculative candidate
                // never resolves, silently forcing triggerAutoplay() to fall
                // through to its own (now timeout-guarded) cold call anyway.
                val next = withTimeout(20_000L) {
                    provider(current, excludeIds, recentTracks)
                } ?: return@launch
                // Stale if the currently playing track changed while this was
                // in flight (user skipped/picked something else meanwhile).
                if (_currentTrack.value?.id != sourceId) return@launch
                pendingAutoplayTrack = next
                pendingAutoplaySourceTrackId = sourceId

                if (next.source != TrackSource.YOUTUBE) return@launch
                val videoId = next.youtubeVideoId ?: return@launch
                val streamUrl = preloadedStreamUrls[videoId] ?: run {
                    val resolved = withContext(Dispatchers.IO) {
                        InnerTubeStreamResolver.resolveStreamUrl(context, videoId)
                    }
                    preloadedStreamUrls[videoId] = resolved
                    resolved
                }

                withContext(Dispatchers.IO) {
                    val dataSource =
                        PlaybackCache.cacheDataSourceFactory(context).createDataSource() as CacheDataSource
                    val cappedSpec = DataSpec.Builder()
                        .setUri(Uri.parse(streamUrl))
                        .setLength(preloadCapBytes)
                        .build()
                    val cacheWriter = CacheWriter(
                        dataSource,
                        cappedSpec,
                        /* temporaryBuffer = */ null,
                        /* progressListener = */ null
                    )
                    activeCacheWriter = cacheWriter
                    cacheWriter.cache()
                }
            } catch (e: Exception) {
                // Best-effort only - if this fails (provider error, relay down,
                // cancelled because the prediction changed), triggerAutoplay()
                // just falls back to its normal cold-start flow below.
                Log.w("MusicPlayer", "Autoplay-candidate prefetch/pre-cache failed or cancelled", e)
            } finally {
                activeCacheWriter = null
            }
        }
    }

    private fun playItunesTrack(track: Track, autoPlay: Boolean = true) {
        // FOREGROUND-STARTUP FIX: must run synchronously, before anything
        // async below, so the service is already a protected foreground
        // service for the whole rest of this function. See the full
        // reasoning on PlaybackService.showLoadingNotification().
        PlaybackBridge.onPlaybackStarting?.invoke()
        cancelBufferingWatchdog()
        // WAKE-LOCK FIX (root cause of "search a song, minimize right away,
        // it never starts"): acquireTransitionWakeLock() used to only be
        // called from handleTrackEnded() - i.e. only for AUTOMATIC track-to-
        // track transitions. Any track started directly (tap a search
        // result, tap a queue item, skip) got NO wake lock at all for its
        // startup network fetch. If the screen turned off right after that
        // tap, the CPU had nothing keeping it awake and could suspend mid-
        // fetch - the track then just never starts, with no error, until the
        // phone happens to wake up for some other reason. Covering every
        // track start here (released the moment STATE_READY actually fires,
        // see onPlaybackStateChanged) closes that gap for good.
        acquireTransitionWakeLock()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        hasReachedPlayingState = false
        val catchUp = pendingCatchUp
        pendingCatchUp = null

        runOnController { controller ->
            val metadata = buildMediaMetadata(track)

            val mediaItem = MediaItem.Builder()
                .setUri(track.previewUrl)
                .setMediaId(track.id.toString())
                .setMediaMetadata(metadata)
                .build()

            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.setMediaItem(mediaItem)
            controller.prepare()
            // ROOM-STATE FIX: honour the caller's intended play state instead of
            // always forcing play() - see playYoutubeTrack for the full reasoning.
            if (autoPlay) controller.play() else controller.pause()
            PlaybackBridge.onMediaItemReady?.invoke()
        }
        resolveAndApplyCatchUp(catchUp, track.durationMs)
        startProgressTracker()
    }

    private fun playYoutubeTrack(track: Track, autoPlay: Boolean = true) {
        // FOREGROUND-STARTUP FIX: same as playItunesTrack above - must be the
        // very first thing that happens, synchronously, before the relay/
        // InnerTube resolve (the slow, network-bound part) starts below.
        PlaybackBridge.onPlaybackStarting?.invoke()
        relayRetriedForVideoId = null
        watchdogRetriedForVideoId = null
        cancelBufferingWatchdog()
        // WAKE-LOCK FIX: see the matching comment in playItunesTrack() above -
        // this covers the exact same gap for YouTube/relay tracks, which is
        // the more common case and does more network work (relay resolve)
        // during startup, making it even more exposed to this bug.
        acquireTransitionWakeLock()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)

        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _duration.value = track.durationMs
        hasReachedPlayingState = false
        _playbackError.value = null
        // Captured synchronously (not re-read later) so a different track started
        // while THIS resolve is still in flight can't steal or corrupt this catch-up.
        val catchUp = pendingCatchUp
        pendingCatchUp = null

        val videoId = track.youtubeVideoId
        loadedYoutubeVideoId = videoId
        if (videoId == null) {
            registerPlaybackFailureAndMaybeStop()
            return
        }

        // Relay (Revo-music /resolve) se ek real audio stream url resolve
        // karke seedha ExoPlayer se bajao. Fail hone par (relay down, timeout,
        // 401/502 etc.) playerListener.onPlayerError ek baar retry karta hai,
        // uske baad bhi fail ho to track skip/error ho jata hai.
        // FIX: if preloadNextTrack() already resolved this videoId while the
        // previous track was playing, use that cached URL immediately instead
        // of making the caller wait on a fresh (potentially slow) relay call -
        // this is what actually removes the buffering gap between tracks.
        val preloadedUrl = preloadedStreamUrls.remove(videoId)
        scope.launch {
            try {
                // TIMEOUT FIX: resolveStreamUrl() (cipher deobfuscation + PoToken + multi-client
                // fallback) had no overall deadline - if it got stuck internally (WebView hang,
                // a network call with no read timeout, etc.) the spinner span forever with no
                // error, because startBufferingWatchdog() only guards AFTER a URL is obtained.
                // withTimeout guarantees this always either succeeds or throws within 20s, so the
                // existing catch block below (retry-once, then show _playbackError) always runs.
                val streamUrl = preloadedUrl ?: withTimeout(20_000L) {
                    withContext(Dispatchers.IO) {
                        InnerTubeStreamResolver.resolveStreamUrl(context, videoId)
                    }
                }
                if (loadedYoutubeVideoId != videoId) return@launch // stale, user ne dusra gaana chala diya
                runOnController { controller ->
                    val metadata = buildMediaMetadata(track)

                    val mediaItem = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaId("relay_${track.id}")
                        .setMediaMetadata(metadata)
                        .build()

                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    // ROOM-STATE FIX: a remote song change that arrives while the
                    // Jam room is paused (e.g. joining a room where someone already
                    // queued a track but nobody hit play) must stay paused here too,
                    // instead of unconditionally starting audio on this device.
                    if (autoPlay) controller.play() else controller.pause()
                    PlaybackBridge.onMediaItemReady?.invoke()
                }
                // SYNC-DRIFT FIX: only now (once THIS device's resolve has actually
                // finished) do we know how long it took - so only now can we correctly
                // compute and apply the catch-up seek. See resolveAndApplyCatchUp().
                resolveAndApplyCatchUp(catchUp, track.durationMs)
                startProgressTracker()
                startBufferingWatchdog(videoId)
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Stream resolve failed for $videoId, retrying once", e)
                val firstLabel = if (e is TimeoutCancellationException) "Stream resolve TIMED OUT (hung >20s)" else "Stream fail"
                _lastStreamErrorDebug.value = "[1st try] $firstLabel: ${e.javaClass.simpleName}: ${e.message} (track=${track.title})"
                if (loadedYoutubeVideoId != videoId) return@launch
                if (relayRetriedForVideoId != videoId) {
                    relayRetriedForVideoId = videoId
                    retryRelayResolve(track, videoId, catchUp, autoPlay)
                } else {
                    // TEMP DEBUG: include the real exception so it's visible on-screen
                    // (via the Snackbar in MainActivity) without needing adb/logcat.
                    val label = if (e is TimeoutCancellationException) "Stream resolve TIMED OUT (hung >20s)" else "Stream fail"
                    _playbackError.value = "$label: ${e.javaClass.simpleName}: ${e.message}"
                    registerPlaybackFailureAndMaybeStop()
                }
            }
        }
    }

    private fun startBufferingWatchdog(videoId: String) {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = scope.launch {
            delay(bufferingTimeoutMs)
            if (loadedYoutubeVideoId == videoId && _isBuffering.value && !hasReachedPlayingState) {
                if (watchdogRetriedForVideoId != videoId) {
                    // First timeout: this is very likely just a slow cold
                    // resolve/connection, not a broken stream - re-resolve
                    // (fresh signed URL) and give it one more full window
                    // before treating it as a real failure.
                    watchdogRetriedForVideoId = videoId
                    val track = _currentTrack.value
                    if (track != null) {
                        Log.w("MusicPlayer", "Buffering watchdog timed out for $videoId, retrying resolve once before giving up")
                        retryRelayResolve(track, videoId, catchUp = null)
                        startBufferingWatchdog(videoId)
                    }
                } else {
                    // Second timeout in a row for the same track - genuinely
                    // stuck, not just slow. Give up on it the same way a
                    // second onPlayerError would.
                    _isBuffering.value = false
                    registerPlaybackFailureAndMaybeStop()
                }
            }
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
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
        releaseTransitionWakeLock()
        runOnController { it.stop() }
        _isPlaying.value = false
        _playbackPosition.value = 0L
        if (!isApplyingRemote) onLocalStop?.invoke(0L)
    }

    fun stopAndDismiss() {
        stop()
        _currentTrack.value = null
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
        // STUCK-FLAG FIX (root cause of the intermittent "play/pause loop" and
        // sync randomly breaking after a song change): suppressNextPlayPauseBroadcast
        // used to be armed unconditionally here, but it is only ever *disarmed* by
        // the onPlayWhenReadyChanged callback it's meant to catch. If this device's
        // playWhenReady already equals the room's target `isPlaying` - the common
        // case, since a device that's already playing usually stays playing across
        // a song change - play()/pause() below causes no real change, so that
        // callback never fires, and the flag was left permanently "hot" for up to
        // suppressExpiryMs (60s). Any genuinely local pause/resume the user made
        // in that window then got silently swallowed and never reached Firebase -
        // that's what looked like sync randomly failing or playback getting stuck
        // right after a track switched. Now we only arm the flag when a real
        // transition is actually expected, so it can never outlive its callback.
        val willChangePlayState = mediaController?.playWhenReady != isPlaying
        if (willChangePlayState) {
            suppressNextPlayPauseBroadcast = true
            suppressPlayPauseArmedAt = System.currentTimeMillis()
        }
        // SYNC-DRIFT FIX: stash the room's timing state so that once THIS device's
        // own relay resolve/buffering finishes (which can take a few seconds longer
        // or shorter than any other device's), playYoutubeTrack()/playItunesTrack()
        // can seek forward to compensate for exactly that gap instead of always
        // starting at 0:00 - see applyCatchUpSeek().
        pendingCatchUp = RemoteCatchUp(positionMs, isPlaying, updatedAtServerMs)
        try {
            // ROOM-STATE FIX: play() used to always force controller.play(),
            // regardless of `isPlaying`. That meant joining (or receiving a song
            // change in) a room that was genuinely paused made audio start
            // playing anyway on every device that got the update - now the new
            // track loads but only actually starts if the room is playing.
            play(TrackSongBridge.toTrack(song), autoPlay = isPlaying)
        } finally {
            isApplyingRemote = false
        }
    }

    fun applyRemotePlayPause(isPlaying: Boolean, positionMs: Long) {
        isApplyingRemote = true
        // Same stuck-flag fix as applyRemoteSongChange above - only arm if this
        // call will actually flip playWhenReady.
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
            // DRIFT-CORRECTION FIX: this is now also called by Jam's periodic
            // heartbeat (not just real user scrubs), so it fires every few seconds
            // while a room is playing. Unconditionally seeking every time caused a
            // tiny audible stutter on every heartbeat even when devices were already
            // essentially in sync. A gap this small isn't perceptible, so only
            // actually seek when the two devices have drifted far enough apart to
            // matter - this is what keeps Jam feeling like "one device playing"
            // instead of slowly sliding out of sync between corrections.
            val currentPos = mediaController?.currentPosition ?: _playbackPosition.value
            val driftMs = kotlin.math.abs(currentPos - positionMs)
            if (driftMs > 700) {
                seekTo(positionMs)
            }
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
        releaseTransitionWakeLock()
        cancelBufferingWatchdog()
        reconnectCallback?.let { cb ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { /* already gone */ }
        }
        reconnectCallback = null
        preloadJob?.cancel()
        autoplayPreloadJob?.cancel()
        pendingAutoplayTrack = null
        pendingAutoplaySourceTrackId = null
        activeCacheWriter?.cancel()
        activeCacheWriter = null
        preloadedStreamUrls.clear()
        mediaController?.release()
        mediaController = null
        if (PlaybackBridge.onNext != null) PlaybackBridge.onNext = null
        if (PlaybackBridge.onPrevious != null) PlaybackBridge.onPrevious = null
        if (PlaybackBridge.onSeek != null) PlaybackBridge.onSeek = null
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
