package com.example.jam

import com.example.data.model.Song
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await

/**
 * Real cross-device group listening ("Jam"), backed by Firebase Realtime Database
 * (free Spark plan is enough for this — a Jam room is a tiny amount of data).
 *
 * How it works:
 *  - One person creates a room -> gets a short room code (e.g. "NIR482")
 *  - Anyone else enters that code to join the SAME Firebase node
 *  - Whenever either person changes the song / play-pause / seeks, that action is
 *    written to Firebase. Firebase pushes the update to every other connected
 *    device in real time, and their local player mirrors it.
 *  - `isApplyingRemoteUpdate` prevents an infinite loop: when we apply a change
 *    that came from Firebase, we don't immediately re-broadcast it back.
 *
 * SYNC-LATENCY FIX: all playback-relevant fields (song + isPlaying + positionMs +
 * updatedAt) now live together under one `playback` child node and are written with a
 * SINGLE atomic `updateChildren` call instead of 2-3 separate sequential `setValue()`
 * calls. Previously each push (e.g. play/pause) did 3 separate network round-trips,
 * and the other device's listener re-fired once per round-trip with a
 * partially-updated snapshot (isPlaying flipped but positionMs still stale, etc.) -
 * that's what produced the 1-2 second, multi-step "catch up" delay. A single write
 * means a single round-trip and a single, fully-consistent callback on other devices.
 * The listener is also now scoped to just `playback` (not the whole room), so
 * unrelated participant join/leave churn no longer triggers extra processing on the
 * playback-sync path. `updatedAt` uses Firebase's server clock (ServerValue.TIMESTAMP)
 * instead of the writer's device clock, so the "how much time has passed since this
 * update was written" compensation isn't thrown off by clock skew between devices.
 */
class JamManager {

    data class JamParticipant(
        val uid: String,
        val name: String,
        val avatar: String,
        val isHost: Boolean
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
        // Relay/YouTube video id - the field that actually matters for real
        // songs now (relay-only, no more direct YouTube API path). Without
        // this making the round trip through Firebase, every other device in
        // the room had nothing to resolve real audio from. See TrackSongBridge.
        val youtubeVideoId: String? = null
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
            youtubeVideoId = youtubeVideoId
        )

        /** Flattened fields for a Firebase `updateChildren` call, relative to whatever
         *  reference is used to perform the update (e.g. `.child("playback")`). */
        fun toFieldMap(): Map<String, Any?> = mapOf(
            "songId" to songId,
            "title" to title,
            "artist" to artist,
            "durationMs" to durationMs,
            "source" to source,
            "streamUrl" to streamUrl,
            "genre" to genre,
            "artwork" to artwork,
            "youtubeVideoId" to youtubeVideoId
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
                youtubeVideoId = song.youtubeVideoId
            )
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance().reference

    var roomCode: String? = null
        private set
    var isHost: Boolean = false
        private set

    // Set to true only while we are applying a change that came FROM Firebase,
    // so the callers know not to re-push it (avoids an update loop).
    var isApplyingRemoteUpdate: Boolean = false
        private set

    // Split into two tightly-scoped listeners instead of one listener on the whole
    // room: playback state changes (song/play-pause/seek) are what need to feel
    // instant, and they shouldn't be delayed or diluted by unrelated roster churn.
    private var playbackListener: ValueEventListener? = null
    private var participantsListener: ValueEventListener? = null
    // BUG FIX (v2): pehle wala "pending flag" tareeka timing pe depend karta tha - agar
    // song-push ke turant baad hi play-push chala jaye (jo Jam me hamesha hota hai),
    // to doosra push pehle wale ka pending flag overwrite kar deta, aur jab pehle
    // wale ka apna hi echo Firebase se wapas aata, use "remote" samajh liya jata aur
    // galti se pause() call ho jata - isi se gaana "chalu-band-chalu-band" karta tha.
    // Fix: har write ke saath apna device ka uid (senderUid) bhejo. Listener me agar
    // senderUid == apna hi uid hai, to yeh guaranteed apna khud ka echo hai (chahe
    // kitne bhi writes ek saath in-flight ho), state trackers update karo lekin
    // onRemote* callback kabhi mat fire karo. Sirf doosre device (alag uid) ke
    // updates par hi local player ko control karo.
    private var myUid: String? = null

    // Callbacks the ViewModel wires up to actually control local playback / UI
    // NOTE: carries positionMs/isPlaying/updatedAtServerMs alongside the Song so
    // MusicPlayer.applyRemoteSongChange() can seek to where the room actually is
    // instead of always starting the new track at 0:00 (see resolveAndApplyCatchUp).
    var onRemoteSongChange: ((song: Song, positionMs: Long, isPlaying: Boolean, updatedAtServerMs: Long) -> Unit)? = null
    var onRemotePlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onRemoteSeek: ((positionMs: Long) -> Unit)? = null
    var onParticipantsChanged: ((List<JamParticipant>) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private suspend fun ensureSignedIn(): String {
        myUid?.let { return it }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        val uid = user?.uid ?: throw IllegalStateException("Firebase anonymous sign-in failed")
        myUid = uid
        return uid
    }

    /** Creates a brand-new Jam room and returns the shareable room code. */
    suspend fun createRoom(
        displayName: String,
        avatar: String,
        currentSong: Song?,
        isPlaying: Boolean = false,
        positionMs: Long = 0L
    ): String {
        val uid = ensureSignedIn()
        var code = generateRoomCode()
        // Extremely unlikely, but make sure we don't collide with an existing live room.
        var attempts = 0
        while (db.child("jams").child(code).get().await().exists() && attempts < 5) {
            code = generateRoomCode()
            attempts++
        }
        roomCode = code
        isHost = true

        val roomRef = db.child("jams").child(code)

        // One atomic multi-path write for the whole initial room state (host id,
        // playback state, and our own participant entry) instead of several
        // sequential ones.
        val updates = mutableMapOf<String, Any?>(
            "hostId" to uid,
            "playback/isPlaying" to isPlaying,
            "playback/positionMs" to positionMs,
            "playback/updatedAt" to ServerValue.TIMESTAMP,
            "playback/senderUid" to uid,
            "participants/$uid" to mapOf("name" to displayName, "avatar" to avatar, "isHost" to true)
        )
        currentSong?.let { song ->
            JamSongState.fromSong(song).toFieldMap().forEach { (key, value) ->
                updates["playback/$key"] = value
            }
        }
        roomRef.updateChildren(updates).await()
        roomRef.child("participants").child(uid).onDisconnect().removeValue()

        // Seed the listener with what we just wrote ourselves, so the very first snapshot
        // Firebase echoes back (ValueEventListener always fires immediately with the
        // current data on attach) isn't mistaken for a "remote" change. Without this, the
        // host's own currently-playing song would immediately restart from 0:00 the moment
        // they tapped "Create Jam Session".
        attachListener(code, initialSongId = currentSong?.id, initialIsPlaying = isPlaying)
        onLog?.invoke("Jam room created! Share code $code with your friend.")
        return code
    }

    /** Joins an existing Jam room by code. Returns true if the room exists. */
    suspend fun joinRoom(code: String, displayName: String, avatar: String): Boolean {
        val uid = ensureSignedIn()
        val normalizedCode = code.trim().uppercase()
        val roomRef = db.child("jams").child(normalizedCode)
        val snapshot = roomRef.get().await()
        if (!snapshot.exists()) {
            return false
        }

        roomCode = normalizedCode
        isHost = false

        val myRef = roomRef.child("participants").child(uid)
        myRef.setValue(mapOf("name" to displayName, "avatar" to avatar, "isHost" to false)).await()
        myRef.onDisconnect().removeValue()

        attachListener(normalizedCode)
        onLog?.invoke("Joined Jam room $normalizedCode!")
        return true
    }

    fun leaveRoom() {
        val code = roomCode ?: return
        val uid = myUid
        val roomRef = db.child("jams").child(code)
        playbackListener?.let { roomRef.child("playback").removeEventListener(it) }
        participantsListener?.let { roomRef.child("participants").removeEventListener(it) }
        if (uid != null) {
            roomRef.child("participants").child(uid).removeValue()
        }
        playbackListener = null
        participantsListener = null
        roomCode = null
        isHost = false
    }

    /** Call when the local user changes the song (only pushes if this device caused it).
     *  Single atomic write across all song fields + reset position, in one round-trip. */
    fun pushSongChange(song: Song) {
        val code = roomCode ?: return
        if (isApplyingRemoteUpdate) return
        val playbackRef = db.child("jams").child(code).child("playback")
        val updates = mutableMapOf<String, Any?>(
            "positionMs" to 0L,
            "updatedAt" to ServerValue.TIMESTAMP,
            "senderUid" to myUid
        )
        updates.putAll(JamSongState.fromSong(song).toFieldMap())
        playbackRef.updateChildren(updates)
    }

    /** Call when the local user plays/pauses. Single atomic write, one round-trip. */
    fun pushPlayPause(isPlaying: Boolean, positionMs: Long) {
        val code = roomCode ?: return
        if (isApplyingRemoteUpdate) return
        val playbackRef = db.child("jams").child(code).child("playback")
        playbackRef.updateChildren(
            mapOf(
                "isPlaying" to isPlaying,
                "positionMs" to positionMs,
                "updatedAt" to ServerValue.TIMESTAMP,
                "senderUid" to myUid
            )
        )
    }

    /** Call when the local user seeks/scrubs. Single atomic write, one round-trip. */
    fun pushSeek(positionMs: Long) {
        val code = roomCode ?: return
        if (isApplyingRemoteUpdate) return
        val playbackRef = db.child("jams").child(code).child("playback")
        playbackRef.updateChildren(
            mapOf(
                "positionMs" to positionMs,
                "updatedAt" to ServerValue.TIMESTAMP,
                "senderUid" to myUid
            )
        )
    }

    private suspend fun attachListener(
        code: String,
        initialSongId: String? = null,
        initialIsPlaying: Boolean? = null
    ) {
        val roomRef = db.child("jams").child(code)
        playbackListener?.let { roomRef.child("playback").removeEventListener(it) }
        participantsListener?.let { roomRef.child("participants").removeEventListener(it) }

        // hostId is fixed at room creation and never changes, so a one-time read is
        // enough - no need for a live listener on it, keeping the roster listener
        // lightweight.
        val hostId = try {
            roomRef.child("hostId").get().await().getValue(String::class.java)
        } catch (e: Exception) {
            null
        }

        val pListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val participants = snapshot.children.mapNotNull { child ->
                    val name = child.child("name").getValue(String::class.java) ?: return@mapNotNull null
                    val avatar = child.child("avatar").getValue(String::class.java) ?: "🎧"
                    JamParticipant(
                        uid = child.key ?: "",
                        name = name,
                        avatar = avatar,
                        isHost = child.key == hostId
                    )
                }
                onParticipantsChanged?.invoke(participants)
            }

            override fun onCancelled(error: DatabaseError) {
                onLog?.invoke("Jam sync error: ${error.message}")
            }
        }
        participantsListener = pListener
        roomRef.child("participants").addValueEventListener(pListener)

        // Seeded with whatever we already know locally (e.g. the host's own song/state
        // that was just written to Firebase) so the guaranteed initial callback from
        // addValueEventListener doesn't get misread as an incoming remote change.
        val plListener = object : ValueEventListener {
            private var lastSongId: String? = initialSongId
            private var lastIsPlaying: Boolean? = initialIsPlaying

            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                isApplyingRemoteUpdate = true
                try {
                    val senderUid = snapshot.child("senderUid").getValue(String::class.java)
                    val isEcho = senderUid != null && senderUid == myUid

                    // Read play/pause + position up front - both the song-change path
                    // below and the play/pause/seek path further down need them, now
                    // that onRemoteSongChange also carries the room's current playback
                    // position/state (so the receiving device can catch up to where the
                    // room actually is instead of starting the new track at 0:00).
                    val isPlaying = snapshot.child("isPlaying").getValue(Boolean::class.java) ?: false
                    val positionMs = snapshot.child("positionMs").getValue(Long::class.java) ?: 0L
                    // updatedAt is now the Firebase SERVER's clock (ServerValue.TIMESTAMP),
                    // not the writer's device clock, so this compensation is only ever
                    // off by the reader's own clock drift - not the writer's too.
                    val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()

                    // Account for network delay: if it's playing, add elapsed time since the update.
                    val adjustedPosition = if (isPlaying) {
                        positionMs + (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
                    } else {
                        positionMs
                    }

                    // Current song - all fields land together atomically, so this is
                    // always internally consistent (no more "isPlaying updated but
                    // positionMs still stale" in-between states).
                    val songId = snapshot.child("songId").getValue(String::class.java)
                    var songJustChanged = false
                    if (songId != null && songId != lastSongId) {
                        lastSongId = songId
                        songJustChanged = true
                        lastIsPlaying = isPlaying
                        if (!isEcho) {
                            val songState = JamSongState(
                                songId = songId,
                                title = snapshot.child("title").getValue(String::class.java) ?: "",
                                artist = snapshot.child("artist").getValue(String::class.java) ?: "",
                                durationMs = snapshot.child("durationMs").getValue(Long::class.java) ?: 0L,
                                source = snapshot.child("source").getValue(String::class.java) ?: "",
                                streamUrl = snapshot.child("streamUrl").getValue(String::class.java) ?: "",
                                genre = snapshot.child("genre").getValue(String::class.java) ?: "",
                                artwork = snapshot.child("artwork").getValue(String::class.java) ?: "",
                                youtubeVideoId = snapshot.child("youtubeVideoId").getValue(String::class.java)
                            )
                            // Pass the raw positionMs/updatedAt (not adjustedPosition) -
                            // MusicPlayer.applyRemoteSongChange does its own elapsed-time
                            // catch-up once its relay resolve actually finishes.
                            onRemoteSongChange?.invoke(songState.toSong(), positionMs, isPlaying, updatedAt)
                        }
                        // isEcho == true -> yeh humara apna hi pushSongChange wapas aaya
                        // hai (koi doosra device nahi). MusicPlayer isse already play kar
                        // raha hai, dobara play() call karke restart karne ki zaroorat nahi.
                    }

                    // BUG FIX: pushSongChange() doesn't touch the "isPlaying" field at
                    // all (only song fields + positionMs=0), so right after a genuine
                    // song change, "isPlaying" in this same snapshot is still whatever
                    // it was BEFORE the change - usually still true. That made this
                    // code fall into the "isPlaying && !isEcho" branch below and fire
                    // onRemoteSeek() immediately after onRemoteSongChange(), in the same
                    // callback - seeking a media item that was still mid-transition
                    // (relay resolve / prepare() hadn't even finished yet). That spurious
                    // seek is what was interfering with the new track and leaving it
                    // stuck buffering - only in Jam, since solo playback never has this
                    // listener at all. A fresh song change already resets position via
                    // applyRemoteSongChange -> play(), so skip this entirely when one
                    // just happened in this same snapshot.
                    if (songJustChanged) return

                    if (isPlaying != lastIsPlaying) {
                        lastIsPlaying = isPlaying
                        if (!isEcho) {
                            onRemotePlayPause?.invoke(isPlaying, adjustedPosition)
                        }
                        // isEcho == true -> humara apna hi pushPlayPause wapas aaya hai -
                        // local player already isi state me hai, dobara resume()/pause()
                        // call karke usse interrupt/restart karne ki zaroorat nahi (isi
                        // wajah se gaana "chalu-band-chalu-band" karta tha).
                    } else if (isPlaying && !isEcho) {
                        // Playing but the state was just a position/seek update, aur yeh
                        // kisi doosre device se aaya hai.
                        onRemoteSeek?.invoke(adjustedPosition)
                    }
                } finally {
                    isApplyingRemoteUpdate = false
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onLog?.invoke("Jam sync error: ${error.message}")
            }
        }
        playbackListener = plListener
        roomRef.child("playback").addValueEventListener(plListener)
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no confusing chars like 0/O, 1/I
        return "NIR" + (1..4).map { chars.random() }.joinToString("")
    }
}
