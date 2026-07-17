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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JamUiState(
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val isInRoom: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Real cross-device Jam + live chat, backed by Supabase (Postgrest + Realtime)
 * - JamManager for playback sync + participants + typing, JamChatManager for
 * messages/reactions. User identity (uid) still comes from Firebase Auth (see
 * myUid below); only the Jam data layer itself moved to Supabase.
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

    val myUid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    init {
        jamManager.onParticipantsChanged = { list -> _participants.value = list }
        jamManager.onRemoteSongChange = { song, positionMs, isPlaying, updatedAtServerMs ->
            musicPlayer.applyRemoteSongChange(song, positionMs, isPlaying, updatedAtServerMs)
        }
        jamManager.onRemotePlayPause = { isPlaying, positionMs -> musicPlayer.applyRemotePlayPause(isPlaying, positionMs) }
        jamManager.onRemoteSeek = { positionMs -> musicPlayer.applyRemoteSeek(positionMs) }
        jamManager.onLog = { msg -> _uiState.value = _uiState.value.copy(errorMessage = msg) }

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
        jamChatManager.onMessageAdded = { msg ->
            if (_messages.value.none { it.id == msg.id }) {
                _messages.value = (_messages.value + msg).sortedBy { it.timestamp }
            }
        }
        jamChatManager.onMessageChanged = { msg ->
            _messages.value = _messages.value.map { if (it.id == msg.id) msg else it }
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
                jamChatManager.attach(code, jamManager.realtimeChannel)
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
                    jamChatManager.attach(roomCode, jamManager.realtimeChannel)
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
        jamChatManager.sendMessage(
            senderId = uid,
            senderName = me?.name ?: "Me",
            senderAvatarUrl = me?.avatar ?: "🎧",
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
