package com.example.player

import android.app.PendingIntent
import android.content.Intent
import com.example.MainActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    // ExoPlayer yahan hamesha ek hi media item ke saath kaam karta hai (poori
    // playlist load nahi hoti, agla/pichla track queue+autoplay logic se app
    // ke andar decide hota hai), isliye iske apne timeline mein kabhi "next"
    // item nahi hota.
    //
    // NOTE: MediaSession.Callback.onConnect() mein availableCommands add karna
    // (neeche) sirf ek per-controller PERMISSION hai - woh asli ExoPlayer ke
    // "kya next hai" wale internal answer ko badalta nahi hai. System ka media
    // notification/quick-settings widget seedha real Player object
    // (yahan `player`, ForwardingPlayer wrap se pehle) ke getAvailableCommands()
    // se check karta hai ki Next button dikhana hai ya nahi - aur single-item
    // ExoPlayer hamesha COMMAND_SEEK_TO_NEXT ko false bolta hai, isliye button
    // hi gayab ho jaata tha (screenshot: sirf Previous dikh raha tha).
    // Fix `sessionPlayer` (neeche) mein hai - MediaSession ko raw `player`
    // nahi, uska ForwardingPlayer wrapper diya jaata hai jo Next/Previous ko
    // hamesha available bolta hai.
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val defaultResult = super.onConnect(session, controller)
            val availableCommands = defaultResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.accept(
                defaultResult.availableSessionCommands,
                availableCommands
            )
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            // FIX: returning RESULT_ERROR_NOT_SUPPORTED here told Media3 the
            // command failed, which caused system UI (notification, lock
            // screen, Bluetooth/Auto) to treat Next/Previous as broken after
            // a single press - sometimes disabling or hiding them entirely.
            // RESULT_INFO_SKIPPED means "handled, just not via the default
            // player behaviour" - it lets us run our own skipNext/skipPrevious
            // logic while keeping the buttons enabled and responsive.
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    PlaybackBridge.onNext?.invoke()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    PlaybackBridge.onPrevious?.invoke()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // FIX: pehle ExoPlayer seedha raw HTTP data source se stream karta tha,
        // isliye har naye track pe (chahe MusicPlayer ne background me URL
        // resolve bhi kar liya ho) audio bytes fresh network se hi aate the -
        // yani buffering baar baar hoti thi. Ab ExoPlayer ISI shared disk cache
        // (PlaybackCache) se hoke play karta hai jisme MusicPlayer.preloadNextTrack()
        // agle gaane ke chunks pehle se utaar chuka hota hai - agar wo chunk
        // already cache me hai to ExoPlayer network wait kiye bina seedha disk
        // se serve kar deta hai, cache miss hone par hi normal network fetch
        // hoti hai (aur wo bhi cache me save ho jaata hai agli baar ke liye).
        val cacheDataSourceFactory = PlaybackCache.cacheDataSourceFactory(this)

        // BUFFERING FIX: ExoPlayer's own defaults (bufferForPlaybackMs=2500,
        // bufferForPlaybackAfterRebufferMs=5000) are tuned for video, where a
        // deep buffer matters a lot more. For a low-bitrate audio-only stream,
        // that's 2.5s+ of dead air before a track even starts, and up to 5s of
        // stall every time the network hiccups mid-song - a big chunk of what
        // felt like "too much buffering" here. minBufferMs/maxBufferMs (how far
        // ahead ExoPlayer keeps loading) are left at the defaults - only the
        // "how much do I need before I'm allowed to actually start/resume
        // playing" thresholds are lowered, so it starts noticeably sooner
        // without changing how much gets buffered overall.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1000,
                1500
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(loadControl)
            // FIX: ExoPlayer ab hamesha asli, audible track bajata hai (relay-
            // resolved YouTube audio ya iTunes preview) - purane silent
            // keep-alive setup ke ulat, isko ab audio focus properly handle
            // karna zaroori hai (calls pe pause, duck for notifications, doosre
            // music app ke upar se na bajna), isliye 'true'.
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // FIX: background playback (screen off) needs a real audio stream
            // to keep buffering over the network without the CPU going to
            // sleep mid-download. WAKE_LOCK was already declared in the
            // manifest but never actually used - WAKE_MODE_NETWORK holds a
            // partial wake lock + wifi lock while playing, which is what
            // actually keeps relay-resolved audio streaming smoothly once the
            // screen turns off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // TAP-TO-OPEN FIX: without a session activity PendingIntent, the system
        // media notification / lock-screen / quick-settings "control centre"
        // widget has nothing to launch when the user taps its art or seek-bar
        // area - so taps there silently did nothing. This tells the session
        // what to open (the app, straight to Now Playing via MainActivity's
        // existing single-top launch behaviour) whenever any part of that
        // widget - other than the dedicated transport buttons - is tapped.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer(player))
            .setCallback(sessionCallback)
            .setSessionActivity(contentIntent)
            .build()
    }

    // Real `player` (ExoPlayer) ko seedha session ko dene ke bajaye, iska yeh
    // wrapper diya jaata hai - taaki system media notification/quick-settings
    // widget hamesha Next/Previous button dikhaye (getAvailableCommands()),
    // aur agar koi controller seekToNext()/seekToNextMediaItem() seedha player
    // par bhi call kar de (sessionCallback.onPlayerCommandRequest() ko bypass
    // karke), tab bhi woh humari asli skipNext/skipPrevious logic (queue +
    // autoplay) tak hi pahunche, ExoPlayer ke khud ke (non-existent) internal
    // "next item" tak nahi.
    private fun sessionPlayer(exoPlayer: ExoPlayer): Player {
        return object : ForwardingPlayer(exoPlayer) {
            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                PlaybackBridge.onNext?.invoke()
            }

            override fun seekToNextMediaItem() {
                PlaybackBridge.onNext?.invoke()
            }

            override fun seekToPrevious() {
                PlaybackBridge.onPrevious?.invoke()
            }

            override fun seekToPreviousMediaItem() {
                PlaybackBridge.onPrevious?.invoke()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return super.onTaskRemoved(rootIntent)
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
