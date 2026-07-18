package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.jam.JamChatManager
import com.example.jam.JamManager
import com.example.player.MusicPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class JamUiState(
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val isInRoom: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Real cross-device Jam + live chat, backed entirely by Supabase (Postgrest +
 * Realtime) - JamManager for playback sync + participants + typing,
 * JamChatManager for messages/reactions. User identity (uid) is a stable
 * local device id (see [com.example.data.local.DeviceIdentity] / myUid
 * below) - there's no auth backend involved at all for Jam.
 */
class JamViewModel(
    private val jamManager: JamManager,
    private val jamChatManager: JamChatManager,
    val musicPlayer: MusicPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(JamUiState())
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private val _participants = MutableStateFlow<List<JamManager.JamParticipant>>(emptyList())
    val participants: StateFlow<List<JamManager.JamParticipant>> = _participants.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())
    val typingUsers: StateFlow<Set<String>> = _typingUsers.asStateFlow()

    private val _replyMessage = MutableStateFlow<ChatMessage?>(null)
    val replyMessage: StateFlow<ChatMessage?> = _replyMessage.asStateFlow()

    // DRIFT-CORRECTION: periodically re-broadcasts the host's position while a
    // room is playing (see startHeartbeat()) so devices don't slowly slide out
    // of sync between real events (song change/play-pause/seek) - without this,
    // the only thing keeping two devices aligned was whatever event last fired,
    // and small decoder/network timing differences would accumulate in between.
    private var heartbeatJob: Job? = null

    val myUid: String? get() = jamManager.localUid()

    init {
        jamManager.onParticipantsChanged = { list -> _participants.value = list }
        jamManager.onRemoteSongChange = { song, positionMs, isPlaying, updatedAtServerMs ->
            musicPlayer.applyRemoteSongChange(song, positionMs, isPlaying, updatedAtServerMs)
        }
        jamManager.onRemotePlayPause = { isPlaying, positionMs -> musicPlayer.applyRemotePlayPause(isPlaying, positionMs) }
        jamManager.onRemoteSeek = { positionMs -> musicPlayer.applyRemoteSeek(positionMs) }
        jamManager.onLog = { msg -> _uiState.value = _uiState.value.copy(errorMessage = msg) }

        // CHAT-LISTENER-TIMING FIX: JamChatManager.attach() must register its
        // "chat"/"reaction" broadcastFlow collectors BEFORE the channel calls
        // subscribe() - otherwise this device can miss (or entirely never
        // receive) broadcasts other participants send, while still seeing
        // its own messages fine (those are added to local state directly,
        // not via this listener at all). Previously attach() was called
        // AFTER jamManager.createRoom()/joinRoom() had already returned -
        // i.e. after subscribe() had already run - which was exactly that
        // bug. Hooking onChannelCreated fires this at the right moment,
        // right after the channel object exists but before it subscribes.
        jamManager.onChannelCreated = { ch ->
            jamManager.roomCode?.let { code -> jamChatManager.attach(code, ch) }
        }

        // Whenever THIS device changes song/play-pause/seek locally, broadcast it -
        // but only while we're actually in a room.
        musicPlayer.onLocalSongChange = { track ->
            if (jamManager.roomCode != null) jamManager.pushSongChange(TrackSongBridge.toSong(track))
        }
        musicPlayer.onLocalPlayPause = { isPlaying, positionMs ->
            if (jamManager.roomCode != null) jamManager.pushPlayPause(isPlaying, positionMs)
        }
        musicPlayer.onLocalSeek = { positionMs ->
            if (jamManager.roomCode != null) jamManager.pushSeek(positionMs)
        }
        // Closing/stopping a song locally didn't broadcast anything before - the
        // other device(s) just kept playing/paused with no idea. Treat "closed" as
        // "paused at 0" for the rest of the room; there's no room for a real
        // "no song" state without changing JamSongState, and pausing everyone is the
        // sensible behavior when one person stops what's playing.
        musicPlayer.onLocalStop = { positionMs ->
            if (jamManager.roomCode != null) jamManager.pushPlayPause(false, positionMs)
        }

        // DUPLICATE-MESSAGE FIX: a message you send can legitimately reach
        // this callback twice - once from the live "chat" broadcast (which
        // JamManager subscribes with receiveOwnBroadcasts = true, so your
        // own sent message comes back to you same as everyone else's), and
        // again from attach()'s one-time history SELECT if that query
        // happens to run/complete around the same time (e.g. you send a
        // message right after creating/joining a room, before the initial
        // history load has finished). Before this fix there was no id check
        // here, so that race showed up as the same chat bubble appearing
        // twice - looking exactly like "Jam chat isn't working properly".
        // ORDERING FIX: attach()'s one-time history load (up to 200 past
        // messages, oldest first) runs concurrently with the live "chat"
        // broadcast collector - if a brand-new message arrives while older
        // history is still being appended, appending-to-the-end put it
        // ABOVE messages that are actually older than it. Sorting by
        // timestamp on every insert keeps the list chronological regardless
        // of which source (history vs. live) delivered a given message first.
        // ATOMICITY FIX: the history load and the live broadcast collector
        // run on separate coroutines (JamChatManager's scope is Dispatchers.IO,
        // which can schedule them on different threads), so a plain
        // `_messages.value = (_messages.value + msg)...` here was a
        // read-then-write race - two calls landing close together could both
        // read the same starting list before either wrote back, and whichever
        // write won would silently overwrite the other's message. In practice
        // that could make a message you'd just sent vanish the moment the
        // history backfill (still running from when you joined/created the
        // room) wrote after it. MutableStateFlow.update {} makes the
        // read-modify-write atomic (retries under contention) instead.
        jamChatManager.onMessageAdded = { msg ->
            _messages.update { current ->
                if (current.any { it.id == msg.id }) current
                else (current + msg).sortedBy { it.timestamp }
            }
        }
        jamChatManager.onMessageChanged = { msg ->
            _messages.update { current -> current.map { if (it.id == msg.id) msg else it } }
        }
        // Typing indicator now rides on JamManager's Presence connection (see
        // JamManager.setTyping doc comment) instead of a separate Firebase node.
        jamManager.onTypingUsersChanged = { uids ->
            _typingUsers.value = uids - setOfNotNull(myUid)
        }
    }

    fun createRoom(displayName: String, avatar: String = "🎧") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
            try {
                val currentTrack = musicPlayer.currentTrack.value
                val code = jamManager.createRoom(
                    displayName = displayName,
                    avatar = avatar,
                    currentSong = currentTrack?.let { TrackSongBridge.toSong(it) },
                    isPlaying = musicPlayer.isPlaying.value,
                    positionMs = musicPlayer.playbackPosition.value
                )
                _uiState.value = JamUiState(roomCode = code, isHost = true, isInRoom = true)
                startHeartbeat()
            } catch (e: Exception) {
                _uiState.value = JamUiState(errorMessage = e.message ?: "Failed to create Jam room")
            }
        }
    }

    fun joinRoom(code: String, displayName: String, avatar: String = "🎧") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
            try {
                val joined = jamManager.joinRoom(code, displayName, avatar)
                if (joined) {
                    val roomCode = jamManager.roomCode!!
                    _uiState.value = JamUiState(roomCode = roomCode, isHost = false, isInRoom = true)
                    startHeartbeat()
                } else {
                    _uiState.value = JamUiState(errorMessage = "Room not found. Double-check the code and try again.")
                }
            } catch (e: Exception) {
                _uiState.value = JamUiState(errorMessage = e.message ?: "Failed to join Jam room")
            }
        }
    }

    fun leaveRoom() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        jamManager.leaveRoom()
        jamChatManager.detach()
        _messages.value = emptyList()
        _participants.value = emptyList()
        _typingUsers.value = emptySet()
        _uiState.value = JamUiState()
    }

    /**
     * Every few seconds while the room is playing, the HOST re-broadcasts its
     * current position (reusing the same playback/positionMs write a manual seek
     * uses). Only the host does this - not every device - so two devices never
     * "fight" over whose clock is correct; everyone else just gently corrects
     * toward the host's position (and only if the drift is actually big enough
     * to matter - see MusicPlayer.applyRemoteSeek). This is what keeps Jam
     * feeling like one device playing instead of slowly drifting apart between
     * real events (song change/play-pause/manual seek).
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                if (jamManager.roomCode != null && jamManager.isHost && musicPlayer.isPlaying.value) {
                    jamManager.pushSeek(musicPlayer.playbackPosition.value)
                }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, isConnecting = false)
    }

    fun setReplyTo(message: ChatMessage?) {
        _replyMessage.value = message
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val uid = myUid ?: return
        val me = _participants.value.find { it.uid == uid }
        val reply = _replyMessage.value
        val senderName = me?.name ?: "Me"
        val senderAvatar = me?.avatar ?: "🎧"
        val messageId = UUID.randomUUID().toString()

        // LOCAL ECHO FIX: sending used to depend entirely on the realtime
        // channel's "self" broadcast making it back to this same device to
        // render the sender's own bubble (see JamChatManager.sendMessage's
        // old comment). In practice that echo isn't reliable enough to hang
        // the UI on - the first message after joining a room would only
        // happen to show up because it also landed in the one-time history
        // load that runs on attach(); every message after that had no such
        // fallback and just never appeared, even to whoever sent it. Adding
        // it to our own state immediately removes that dependency entirely.
        // The id is shared with the broadcast row below, so if the echo (or
        // a later history refresh) does deliver the same message, the
        // existing dedup-by-id in onMessageAdded just no-ops instead of
        // duplicating the bubble.
        _messages.update { current ->
            (current + ChatMessage(
                id = messageId,
                jamId = jamManager.roomCode.orEmpty(),
                senderId = uid,
                senderName = senderName,
                senderAvatarUrl = senderAvatar,
                text = text,
                timestamp = System.currentTimeMillis(),
                replyToId = reply?.id,
                replyToText = reply?.text,
                replyToSenderName = reply?.senderName,
            )).sortedBy { it.timestamp }
        }

        jamChatManager.sendMessage(
            id = messageId,
            senderId = uid,
            senderName = senderName,
            senderAvatarUrl = senderAvatar,
            text = text,
            replyToId = reply?.id,
            replyToText = reply?.text,
            replyToSenderName = reply?.senderName
        )
        _replyMessage.value = null
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val uid = myUid ?: return
        jamChatManager.toggleReaction(messageId, emoji, uid)
    }

    fun setUserTyping(isTyping: Boolean) {
        if (myUid == null) return
        jamManager.setTyping(isTyping)
    }

    /** Host or any participant picks a track inside the Jam - broadcasts via MusicPlayer's onLocalSongChange hook. */
    fun hostPlayTrack(track: Track, tracksList: List<Track>) {
        musicPlayer.setQueue(tracksList, tracksList.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        jamManager.leaveRoom()
        jamChatManager.detach()
    }

    class Factory(
        private val jamManager: JamManager,
        private val jamChatManager: JamChatManager,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JamViewModel(jamManager, jamChatManager, musicPlayer) as T
        }
    }
}
