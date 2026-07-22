package com.example.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.data.network.InnerTubeStreamResolver
import com.example.data.youtube.YTPlayerUtils
import kotlinx.coroutines.runBlocking

/**
 * MetroList-style playback engine for this app.
 *
 * The previous PlaybackService kept ExoPlayer permanently down to a single MediaItem and
 * app-level code (MusicPlayer) manually swapped that item on every skip - Media3's own
 * Next/Previous availability, auto-advance and system media notification are all driven off
 * the *player's own timeline*, so a single-item player structurally could never expose a real
 * "next" item, which is exactly what forced the old ForwardingPlayer/MediaSession.Callback hack
 * in the previous version of this file (see PlaybackBridge's old doc comment for the full
 * history) and what still made system UI (notification / lock screen / Bluetooth / Android
 * Auto) unreliable.
 *
 * This version, like MetroList's MusicService, gives ExoPlayer a REAL multi-item playlist
 * (MusicPlayer.setQueue()/addToQueue()/etc. now call MediaController.setMediaItems()/
 * addMediaItem() with one MediaItem per track - see TrackMediaItem.kt) and resolves each
 * item's actual audio URL lazily, only once ExoPlayer is actually about to read its bytes, via
 * a [ResolvingDataSource] wrapped around the existing on-disk [PlaybackCache]. That is the same
 * mechanism MetroList's MusicService.createDataSourceFactory() uses for YouTube streams. With a
 * real timeline in place:
 *  - Next/Previous/seek/queue-reordering are all native MediaSession/Player behaviour - no
 *    custom ForwardingPlayer or onPlayerCommandRequest() override needed any more.
 *  - Auto Next between playlist items is native ExoPlayer playlist advance; auto-advancing into
 *    a fresh autoplay recommendation once the playlist runs out is still driven by MusicPlayer
 *    (it owns the autoplayProvider callback, which needs MusicRepository), which appends the
 *    next MediaItem to this same real timeline just before the last item ends - see
 *    MusicPlayer's onMediaItemTransition handling.
 *  - Background playback + the system media notification come from Media3's own foreground
 *    promotion (as soon as the session player has media items) and [DefaultMediaNotificationProvider],
 *    same as MetroList - no manual startForeground()/placeholder-notification workaround needed.
 */
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    // Short-lived resolved-URL cache so replaying/seeking back into a track that was already
    // resolved this session doesn't need a fresh network round trip every time ExoPlayer reopens
    // its data source (e.g. after a seek outside the cached range). Mirrors MetroList's own
    // songUrlCache TTL pattern in MusicService.createDataSourceFactory().
    private data class ResolvedUrl(val url: String, val contentLength: Long?, val expiresAtMs: Long)
    private val resolvedUrlCache = object : LinkedHashMap<String, ResolvedUrl>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedUrl>?) = size > 30
    }
    private val resolvedUrlTtlMs = 4 * 60 * 1000L // YouTube-resolved URLs are short-lived signed links

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1000,
                1500
            )
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor()) }
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(contentIntent)
            .build()

        // Real Media3 media notification (art, title/artist, transport controls including
        // Next/Previous which now work natively because the player has a real timeline) - the
        // exact same provider class + constructor shape MetroList's MusicService uses, instead
        // of the old hand-rolled placeholder NotificationCompat builder this service used to
        // post itself.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.playback_channel_name
            )
        )
    }

    /**
     * Builds the lazy-resolving data source chain: cache-through [CacheDataSource] (same shared
     * on-disk cache the old service used, via [PlaybackCache]) wrapped in a [ResolvingDataSource]
     * that only resolves a real playable URL for a MediaItem the moment ExoPlayer actually needs
     * its bytes - see TrackMediaItem.kt for the "yt:"/"it:" mediaId scheme this switches on.
     * This is the same overall shape as MetroList's MusicService.createDataSourceFactory().
     */
    private fun createDataSourceFactory(): DataSource.Factory {
        val cacheDataSourceFactory = PlaybackCache.cacheDataSourceFactory(this)
        return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            // customCacheKey doesn't always reliably reach DataSpec.key depending on how the
            // MediaSource ended up being inferred, so don't depend on it alone - fall back to
            // pulling the video id straight out of the placeholder URI we build in
            // Track.toMediaItem() ("https://music.youtube.com/watch?v=<id>"). This is what was
            // causing playback to buffer forever: the resolver branch below was silently never
            // being entered, so ExoPlayer just kept trying to load that placeholder HTML page.
            val mediaId = dataSpec.key
            val youtubeVideoId = (mediaId?.let { MediaIdScheme.youtubeVideoIdOrNull(it) })
                ?: dataSpec.uri.getQueryParameter("v")

            if (youtubeVideoId.isNullOrBlank()) {
                Log.d("MusicService", "No YouTube id for uri=${dataSpec.uri} key=$mediaId - treating as direct URL")
                return@Factory dataSpec // iTunes items already carry their real, direct preview URL.
            }

            val cacheKey = mediaId ?: "yt:$youtubeVideoId"

            // Already fully cached on disk from an earlier play-through - CacheDataSource will
            // serve it straight from disk, no need to resolve (or hit the network) at all.
            val cache = PlaybackCache.get(this)
            if (cache.isCached(cacheKey, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)) {
                Log.d("MusicService", "Already cached, skipping resolve: $cacheKey")
                return@Factory dataSpec
            }

            resolvedUrlCache[cacheKey]
                ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
                ?.let {
                    Log.d("MusicService", "Reusing resolved URL for $cacheKey")
                    return@Factory boundedDataSpec(dataSpec, it.url, it.contentLength)
                }

            Log.d("MusicService", "Resolving stream for videoId=$youtubeVideoId")
            val resolved = try {
                runBlocking {
                    kotlinx.coroutines.withTimeout(15_000L) {
                        InnerTubeStreamResolver.resolveStreamUrl(this@MusicService, youtubeVideoId)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e("MusicService", "Timed out resolving $youtubeVideoId")
                throw PlaybackException(
                    getString(R.string.error_unknown_playback),
                    e,
                    PlaybackException.ERROR_CODE_TIMEOUT
                )
            } catch (e: PlaybackException) {
                throw e
            } catch (e: java.net.ConnectException) {
                throw PlaybackException(
                    getString(R.string.error_no_internet_playback),
                    e,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                )
            } catch (e: java.net.UnknownHostException) {
                throw PlaybackException(
                    getString(R.string.error_no_internet_playback),
                    e,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                )
            } catch (e: Exception) {
                Log.e("MusicService", "Resolve failed for $youtubeVideoId", e)
                throw PlaybackException(
                    e.message ?: getString(R.string.error_unknown_playback),
                    e,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }

            Log.d("MusicService", "Resolved $youtubeVideoId -> ${resolved.url.take(80)}... (contentLength=${resolved.contentLength})")
            resolvedUrlCache[cacheKey] = ResolvedUrl(resolved.url, resolved.contentLength, System.currentTimeMillis() + resolvedUrlTtlMs)
            boundedDataSpec(dataSpec, resolved.url, resolved.contentLength)
        }
    }

    /**
     * googlevideo needs an explicit, bounded Range header - a Range-less GET fails outright, and
     * a Range whose upper bound exceeds the real file size can make some edges echo the
     * *requested* length back in Content-Length instead of what's actually available, which trips
     * OkHttp's "unexpected end of stream" check the moment the real file ends early (surfaces to
     * the user as generic "Source error"). So the Range has to match the real remaining length -
     * from the format's own contentLength (as reported by the InnerTube API), never a guessed cap.
     * Falls back to a generous guess only on the rare format that doesn't report a contentLength.
     */
    private fun boundedDataSpec(dataSpec: DataSpec, resolvedUrl: String, contentLength: Long?): DataSpec {
        val remaining = if (contentLength != null && contentLength > dataSpec.position) {
            contentLength - dataSpec.position
        } else {
            FALLBACK_RANGE_LENGTH
        }
        return dataSpec.withUri(Uri.parse(resolvedUrl))
            .subrange(dataSpec.uriPositionOffset, remaining)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e("MusicService", "Player error: ${error.errorCodeName}", error)
            handleStreamRejection(error)
        }
    }

    /**
     * This is the other half of the WEB_REMIX self-heal that [createDataSourceFactory] sets up
     * (see the comment on the `webRemixFailedIds`/`markWebRemixFailed` check there): WEB_REMIX
     * skips HEAD validation and is handed straight to ExoPlayer on the assumption that a URL
     * which 403s on HEAD can still serve fine on ExoPlayer's real byte-range GET - but when that
     * assumption is wrong (stale cipher/poToken), nothing was ever calling
     * [YTPlayerUtils.markWebRemixFailed], so every single retry re-tried the exact same broken
     * WEB_REMIX client and never fell through to the validated fallback clients. On top of that,
     * [resolvedUrlCache] would keep re-serving that same broken URL for its whole TTL, so even a
     * fresh resolve attempt wouldn't help. Both gaps mean a track whose WEB_REMIX link is bad
     * would buffer/error and retry (see MusicPlayer.handlePlaybackError) against the same dead
     * URL over and over instead of ever recovering - this is what fixes that.
     */
    private fun handleStreamRejection(error: PlaybackException) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val youtubeVideoId = MediaIdScheme.youtubeVideoIdOrNull(mediaId) ?: return
        if (!isHttpStreamRejection(error)) return

        Log.w(
            "MusicService",
            "Stream rejected (${error.errorCodeName}) for $youtubeVideoId - marking WEB_REMIX " +
                "failed so the next resolve falls through to a validated fallback client"
        )
        YTPlayerUtils.markWebRemixFailed(youtubeVideoId)
        // Otherwise the retry MusicPlayer is about to trigger would just get served this exact
        // same stale/broken URL straight back out of the cache before ever reaching the client
        // fallback logic.
        resolvedUrlCache.remove(mediaId)
    }

    /** Walks the cause chain for an HTTP-level rejection (e.g. the 403 a stale/expired
     *  googlevideo signature or poToken produces) as opposed to a generic decode/network error
     *  that isn't specific to this stream URL being bad. */
    private fun isHttpStreamRejection(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return true
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return true
            cause = cause.cause
        }
        return false
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession
        if (session == null || !session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "music_playback_channel"
        // Only used on the rare format that doesn't report a contentLength at all - generous
        // enough to cover any real track when we're forced to guess.
        private const val FALLBACK_RANGE_LENGTH = 200L * 1024 * 1024 // 200MB
    }
}
