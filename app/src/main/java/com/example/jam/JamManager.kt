package com.example.jam

import android.content.Context
import com.example.data.local.DeviceIdentity
import com.example.data.model.Song
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceDataFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Real cross-device group listening ("Jam"), backed by **Supabase** (Postgrest +
 * Realtime) instead of Firebase Realtime Database.
 *
 * How it works:
 *  - One person creates a room -> gets a short room code (e.g. "NIR482"), which
 *    is both a row in the `jam_rooms` table (for persistence / late joiners)
 *    and a Supabase Realtime channel named `jam:{code}`.
 *  - Anyone else enters that code to join the SAME channel + room row.
 *  - Whenever either person changes the song / play-pause / seeks, that action
 *    is (a) **broadcast** on the channel - delivered to every other connected
 *    device in real time with no DB round-trip on the hot path - and (b)
 *    written to the `jam_rooms` row so a device that (re)joins later can fetch
 *    "what's currently playing" with a single SELECT.
 *  - `isApplyingRemoteUpdate` prevents an infinite loop: when we apply a change
 *    that came from another device, we don't immediately re-broadcast it back.
 *  - Participants are tracked with Realtime **Presence**, not a table - like
 *    Firebase's `onDisconnect()`, presence automatically drops a participant
 *    the instant their socket disconnects, no cleanup write needed. The typing
 *    indicator rides along on the same presence payload (see setTyping()),
 *    since a client can only track one presence state per channel.
 *
 * senderUid echo suppression: every broadcast/row write carries the sending
 * device's uid. If an incoming update's `senderUid` matches our own uid, it's
 * guaranteed to be our own echo (not a change from another device) - update
 * internal trackers but never fire the onRemote* callbacks for it, which is
 * what stops the local player from restarting/pausing itself.
 */
class JamManager(private val context: Context) {

    data class JamParticipant(
        val uid: String,
        val name: String,
        val avatar: String,
        val isHost: Boolean,
    )

    data class JamSongState(
        val songId: String = "",
        val title: String = "",
        val artist: String = "",
        val durationMs: Long = 0,
        val source: String = "",
        val streamUrl: String = "",
        val genre: String = "",
        val artwork: String = "",
        val youtubeVideoId: String? = null,
    ) {
        fun toSong(): Song = Song(
            id = songId,
            title = title,
            artist = artist,
            duration = durationMs,
            source = source,
            streamUrl = streamUrl,
            genre = genre,
            artwork = artwork,
            youtubeVideoId = youtubeVideoId,
        )

        companion object {
            fun fromSong(song: Song) = JamSongState(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                durationMs = song.duration,
                source = song.source,
                streamUrl = song.streamUrl,
                genre = song.genre,
                artwork = song.artwork,
                youtubeVideoId = song.youtubeVideoId,
            )
        }
    }

    // ---- Wire payloads (kotlinx.serialization - Supabase-kt requires it,
    // unlike the old Firebase SDK which worked directly with Map<String,Any?>).
    // snake_case @SerialName on anything that's a Postgres COLUMN; the plain
    // broadcast/presence payloads don't hit Postgres so camelCase is fine there. ----

    @Serializable
    private data class RoomRow(
        val code: String,
        @SerialName("host_id") val hostId: String,
        @SerialName("song_id") val songId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val source: String? = null,
        @SerialName("stream_url") val streamUrl: String? = null,
        val genre: String? = null,
        val artwork: String? = null,
        @SerialName("youtube_video_id") val youtubeVideoId: String? = null,
        @SerialName("is_playing") val isPlaying: Boolean = false,
        @SerialName("position_ms") val positionMs: Long = 0L,
        @SerialName("sender_uid") val senderUid: String? = null,
    )

    @Serializable
    private data class PlaybackColumnUpdate(
        @SerialName("song_id") val songId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val source: String? = null,
        @SerialName("stream_url") val streamUrl: String? = null,
        val genre: String? = null,
        val artwork: String? = null,
        @SerialName("youtube_video_id") val youtubeVideoId: String? = null,
        @SerialName("is_playing") val isPlaying: Boolean? = null,
        @SerialName("position_ms") val positionMs: Long? = null,
        @SerialName("sender_uid") val senderUid: String? = null,
    )

    /** Broadcast message shape sent over the realtime channel for instant sync. */
    @Serializable
    private data class PlaybackBroadcast(
        val songId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val durationMs: Long? = null,
        val source: String? = null,
        val streamUrl: String? = null,
        val genre: String? = null,
        val artwork: String? = null,
        val youtubeVideoId: String? = null,
        val isPlaying: Boolean,
        val positionMs: Long,
        // Sender device's own clock - Broadcast has no server-timestamp
        // equivalent to Firebase's ServerValue.TIMESTAMP. Broadcast delivery
        // is near-instant, so the remaining error here is clock-skew between
        // devices rather than network delay; acceptable for this feature.
        val updatedAt: Long,
        val senderUid: String,
    )

    /** Presence payload: participant identity + typing flag combined, since a
     *  client can only track ONE presence state per channel (see setTyping). */
    @Serializable
    private data class ParticipantPresence(
        val uid: String,
        val name: String,
        val avatar: String,
        val isHost: Boolean,
        val isTyping: Boolean = false,
    )

    companion object {
        private const val ROOMS_TABLE = "jam_rooms"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Realtime's track()/broadcast() take a JsonObject, not an arbitrary
     *  @Serializable instance - encode explicitly rather than relying on
     *  overload resolution to do it for us. */
    private inline fun <reified T> T.toJsonObject(): JsonObject =
        json.encodeToJsonElement(this) as JsonObject

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var roomCode: String? = null
        private set
    var isHost: Boolean = false
        private set

    var isApplyingRemoteUpdate: Boolean = false
        private set

    private var myUid: String? = null
    private var channel: RealtimeChannel? = null

    /** Exposed so JamChatManager can reuse the SAME realtime channel (one
     *  websocket subscription per room) for its own chat/reaction broadcasts. */
    val realtimeChannel: RealtimeChannel? get() = channel

    private var lastSongId: String? = null
    private var lastIsPlaying: Boolean? = null
    private var myPresence: ParticipantPresence? = null
    private var typingUids: Set<String> = emptySet()

    var onRemoteSongChange: ((song: Song, positionMs: Long, isPlaying: Boolean, updatedAtServerMs: Long) -> Unit)? = null
    var onRemotePlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onRemoteSeek: ((positionMs: Long) -> Unit)? = null
    var onParticipantsChanged: ((List<JamParticipant>) -> Unit)? = null
    var onTypingUsersChanged: ((Set<String>) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private fun ensureSignedIn(): String {
        myUid?.let { return it }
        val uid = DeviceIdentity.getUid(context)
        myUid = uid
        return uid
    }

    /** This device's stable local id (see [DeviceIdentity]). Exposed so callers
     *  like JamViewModel can show/compare "my" uid without depending on any
     *  auth SDK themselves. */
    fun localUid(): String = ensureSignedIn()

    /** Creates a brand-new Jam room and returns the shareable room code. */
    suspend fun createRoom(
        displayName: String,
        avatar: String,
        currentSong: Song?,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
    ): String {
        val uid = ensureSignedIn()
        var code = generateRoomCode()
        var attempts = 0
        while (roomExists(code) && attempts < 5) {
            code = generateRoomCode()
            attempts++
        }
        roomCode = code
        isHost = true

        val songState = currentSong?.let { JamSongState.fromSong(it) }
        val row = RoomRow(
            code = code,
            hostId = uid,
            songId = songState?.songId,
            title = songState?.title,
            artist = songState?.artist,
            durationMs = songState?.durationMs,
            source = songState?.source,
            streamUrl = songState?.streamUrl,
            genre = songState?.genre,
            artwork = songState?.artwork,
            youtubeVideoId = songState?.youtubeVideoId,
            isPlaying = isPlaying,
            positionMs = positionMs,
            senderUid = uid,
        )
        SupabaseClient.client.postgrest[ROOMS_TABLE].insert(row)

        lastSongId = currentSong?.id
        lastIsPlaying = isPlaying

        attachChannel(code = code, uid = uid, displayName = displayName, avatar = avatar, isHostFlag = true)
        onLog?.invoke("Jam room created! Share code $code with your friend.")
        return code
    }

    /** Joins an existing Jam room by code. Returns true if the room exists. */
    suspend fun joinRoom(code: String, displayName: String, avatar: String): Boolean {
        val uid = ensureSignedIn()
        val normalizedCode = code.trim().uppercase()

        val existing = SupabaseClient.client.postgrest[ROOMS_TABLE]
            .select(columns = Columns.ALL) { filter { eq("code", normalizedCode) } }
            .decodeSingleOrNull<RoomRow>() ?: return false

        roomCode = normalizedCode
        isHost = false
        lastSongId = existing.songId
        lastIsPlaying = existing.isPlaying

        attachChannel(code = normalizedCode, uid = uid, displayName = displayName, avatar = avatar, isHostFlag = false)

        // Seed local playback with whatever the room's current state already is
        // (a fresh SELECT we just did above), so a guest catches up to a jam
        // already in progress instead of starting from silence.
        if (existing.songId != null) {
            val songState = JamSongState(
                songId = existing.songId,
                title = existing.title.orEmpty(),
                artist = existing.artist.orEmpty(),
                durationMs = existing.durationMs ?: 0L,
                source = existing.source.orEmpty(),
                streamUrl = existing.streamUrl.orEmpty(),
                genre = existing.genre.orEmpty(),
                artwork = existing.artwork.orEmpty(),
                youtubeVideoId = existing.youtubeVideoId,
            )
            onRemoteSongChange?.invoke(songState.toSong(), existing.positionMs, existing.isPlaying, System.currentTimeMillis())
        }

        onLog?.invoke("Joined Jam room $normalizedCode!")
        return true
    }

    fun leaveRoom() {
        val ch = channel
        channel = null
        roomCode = null
        isHost = false
        typingUids = emptySet()
        myPresence = null
        scope.launch {
            try {
                ch?.untrack()
                ch?.unsubscribe()
                ch?.let { SupabaseClient.client.realtime.removeChannel(it) }
            } catch (e: Exception) {
                onLog?.invoke("Jam leave error: ${e.message}")
            }
        }
    }

    /** Call when the local user changes the song (only pushes if this device caused it). */
    fun pushSongChange(song: Song) {
        val code = roomCode ?: return
        val uid = myUid ?: return
        if (isApplyingRemoteUpdate) return
        val state = JamSongState.fromSong(song)
        lastSongId = song.id
        broadcastPlayback(
            PlaybackBroadcast(
                songId = state.songId, title = state.title, artist = state.artist,
                durationMs = state.durationMs, source = state.source, streamUrl = state.streamUrl,
                genre = state.genre, artwork = state.artwork, youtubeVideoId = state.youtubeVideoId,
                isPlaying = lastIsPlaying ?: false, positionMs = 0L,
                updatedAt = System.currentTimeMillis(), senderUid = uid,
            ),
        )
        updateRoomRow(
            code,
            PlaybackColumnUpdate(
                songId = state.songId, title = state.title, artist = state.artist,
                durationMs = state.durationMs, source = state.source, streamUrl = state.streamUrl,
                genre = state.genre, artwork = state.artwork, youtubeVideoId = state.youtubeVideoId,
                positionMs = 0L, senderUid = uid,
            ),
        )
    }

    /** Call when the local user plays/pauses. */
    fun pushPlayPause(isPlaying: Boolean, positionMs: Long) {
        val code = roomCode ?: return
        val uid = myUid ?: return
        if (isApplyingRemoteUpdate) return
        lastIsPlaying = isPlaying
        broadcastPlayback(
            PlaybackBroadcast(
                isPlaying = isPlaying, positionMs = positionMs,
                updatedAt = System.currentTimeMillis(), senderUid = uid,
            ),
        )
        updateRoomRow(code, PlaybackColumnUpdate(isPlaying = isPlaying, positionMs = positionMs, senderUid = uid))
    }

    /** Call when the local user seeks/scrubs. */
    fun pushSeek(positionMs: Long) {
        val code = roomCode ?: return
        val uid = myUid ?: return
        if (isApplyingRemoteUpdate) return
        broadcastPlayback(
            PlaybackBroadcast(
                isPlaying = lastIsPlaying ?: false, positionMs = positionMs,
                updatedAt = System.currentTimeMillis(), senderUid = uid,
            ),
        )
        updateRoomRow(code, PlaybackColumnUpdate(positionMs = positionMs, senderUid = uid))
    }

    /** Start/stop showing this device's user as "typing" in the room's chat.
     *  Rides along on the same Presence connection as the participant roster
     *  (a client can only track one presence state per channel), and - like
     *  Firebase's onDisconnect() - automatically clears itself if the app dies
     *  mid-typing, since the whole presence entry disappears on disconnect. */
    fun setTyping(isTyping: Boolean) {
        val ch = channel ?: return
        val updated = (myPresence ?: return).copy(isTyping = isTyping)
        myPresence = updated
        scope.launch {
            try {
                ch.track(updated.toJsonObject())
            } catch (e: Exception) {
                onLog?.invoke("Jam typing error: ${e.message}")
            }
        }
    }

    private fun broadcastPlayback(payload: PlaybackBroadcast) {
        val ch = channel ?: return
        scope.launch {
            try {
                ch.broadcast(event = "playback", message = payload.toJsonObject())
            } catch (e: Exception) {
                onLog?.invoke("Jam sync error: ${e.message}")
            }
        }
    }

    private fun updateRoomRow(code: String, update: PlaybackColumnUpdate) {
        scope.launch {
            try {
                SupabaseClient.client.postgrest[ROOMS_TABLE].update(update) {
                    filter { eq("code", code) }
                }
            } catch (e: Exception) {
                onLog?.invoke("Jam persist error: ${e.message}")
            }
        }
    }

    private suspend fun roomExists(code: String): Boolean =
        SupabaseClient.client.postgrest[ROOMS_TABLE]
            .select(columns = Columns.list("code")) { filter { eq("code", code) } }
            .decodeList<Map<String, String>>()
            .isNotEmpty()

    private suspend fun attachChannel(
        code: String,
        uid: String,
        displayName: String,
        avatar: String,
        isHostFlag: Boolean,
    ) {
        // receiveOwnBroadcasts = true: broadcasts we send are also delivered back to
        // us. Playback echo-suppression already handles this via senderUid (see
        // handleIncomingPlayback); JamChatManager relies on it too, so a sent
        // chat message renders through the exact same onMessageAdded path for
        // every participant, including its own sender.
        val ch = SupabaseClient.client.channel("jam:$code") {
            broadcast {
                receiveOwnBroadcasts = true
            }
        }
        channel = ch

        scope.launch {
            ch.broadcastFlow<PlaybackBroadcast>(event = "playback").collect { payload ->
                handleIncomingPlayback(payload, uid)
            }
        }

        scope.launch {
            ch.presenceDataFlow<ParticipantPresence>().collect { states ->
                onParticipantsChanged?.invoke(
                    states.map { JamParticipant(uid = it.uid, name = it.name, avatar = it.avatar, isHost = it.isHost) },
                )
                val newTyping = states.filter { it.isTyping && it.uid != uid }.map { it.uid }.toSet()
                if (newTyping != typingUids) {
                    typingUids = newTyping
                    onTypingUsersChanged?.invoke(newTyping)
                }
            }
        }

        ch.subscribe(blockUntilSubscribed = true)
        val presence = ParticipantPresence(uid = uid, name = displayName, avatar = avatar, isHost = isHostFlag)
        myPresence = presence
        ch.track(presence.toJsonObject())
    }

    private fun handleIncomingPlayback(payload: PlaybackBroadcast, myUid: String) {
        val isEcho = payload.senderUid == myUid
        isApplyingRemoteUpdate = true
        try {
            val adjustedPosition = if (payload.isPlaying) {
                payload.positionMs + (System.currentTimeMillis() - payload.updatedAt).coerceAtLeast(0L)
            } else {
                payload.positionMs
            }

            var songJustChanged = false
            if (payload.songId != null && payload.songId != lastSongId) {
                lastSongId = payload.songId
                songJustChanged = true
                lastIsPlaying = payload.isPlaying
                if (!isEcho) {
                    val songState = JamSongState(
                        songId = payload.songId,
                        title = payload.title.orEmpty(),
                        artist = payload.artist.orEmpty(),
                        durationMs = payload.durationMs ?: 0L,
                        source = payload.source.orEmpty(),
                        streamUrl = payload.streamUrl.orEmpty(),
                        genre = payload.genre.orEmpty(),
                        artwork = payload.artwork.orEmpty(),
                        youtubeVideoId = payload.youtubeVideoId,
                    )
                    onRemoteSongChange?.invoke(songState.toSong(), payload.positionMs, payload.isPlaying, payload.updatedAt)
                }
            }
            if (songJustChanged) return

            if (payload.isPlaying != lastIsPlaying) {
                lastIsPlaying = payload.isPlaying
                if (!isEcho) {
                    onRemotePlayPause?.invoke(payload.isPlaying, adjustedPosition)
                }
            } else if (payload.isPlaying && !isEcho) {
                onRemoteSeek?.invoke(adjustedPosition)
            }
        } finally {
            isApplyingRemoteUpdate = false
        }
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no confusing chars like 0/O, 1/I
        return "NIR" + (1..4).map { chars.random() }.joinToString("")
    }
}
