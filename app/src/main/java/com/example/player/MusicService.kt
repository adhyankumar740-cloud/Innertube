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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.data.network.InnerTubeStreamResolver
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
    private data class ResolvedUrl(val url: String, val expiresAtMs: Long)
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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
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
            val mediaId = dataSpec.key ?: return@Factory dataSpec

            val youtubeVideoId = MediaIdScheme.youtubeVideoIdOrNull(mediaId)
                ?: return@Factory dataSpec // iTunes items already carry their real, direct preview URL.

            // Already fully cached on disk from an earlier play-through - CacheDataSource will
            // serve it straight from disk, no need to resolve (or hit the network) at all.
            val cache = PlaybackCache.get(this)
            if (cache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)) {
                return@Factory dataSpec
            }

            resolvedUrlCache[mediaId]
                ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
                ?.let { return@Factory dataSpec.withUri(Uri.parse(it.url)) }

            val resolvedUrl = try {
                runBlocking { InnerTubeStreamResolver.resolveStreamUrl(this@MusicService, youtubeVideoId) }
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
                throw PlaybackException(
                    e.message ?: getString(R.string.error_unknown_playback),
                    e,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }

            resolvedUrlCache[mediaId] = ResolvedUrl(resolvedUrl, System.currentTimeMillis() + resolvedUrlTtlMs)
            dataSpec.withUri(Uri.parse(resolvedUrl))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e("MusicService", "Player error: ${error.errorCodeName}", error)
        }
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
    }
}
