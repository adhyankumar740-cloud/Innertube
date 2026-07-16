package com.example.jam

import com.example.data.model.ChatMessage
import com.example.data.model.MessageReaction
import com.example.data.model.MessageStatus
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Real-time group chat for a Jam room, backed by the same Firebase Realtime
 * Database room JamManager uses (`jams/{code}/...`). Construct one alongside
 * a JamManager and call [attach] with the same room code once a room has been
 * created/joined.
 *
 * Data lives under:
 *   jams/{code}/messages/{pushId}  -> { senderId, senderName, senderAvatarUrl,
 *                                       text, timestamp, replyToId, replyToText,
 *                                       replyToSenderName, reactions/{emoji}/{uid} }
 *   jams/{code}/typing/{uid}       -> true while that user is typing
 *
 * Messages are streamed with a ChildEventListener (onChildAdded) instead of a
 * ValueEventListener on the whole list, so a growing chat history doesn't
 * re-download and re-parse every message on every new message.
 */
class JamChatManager {

    private val db = FirebaseDatabase.getInstance().reference

    private var roomCode: String? = null
    private var messagesListener: ChildEventListener? = null
    private var typingListener: ValueEventListener? = null

    var onMessageAdded: ((ChatMessage) -> Unit)? = null
    var onMessageChanged: ((ChatMessage) -> Unit)? = null
    var onTypingUsersChanged: ((Set<String>) -> Unit)? = null

    /** Start listening to messages/typing for [code]. Call after JamManager.createRoom/joinRoom. */
    fun attach(code: String) {
        detach()
        roomCode = code
        val roomRef = db.child("jams").child(code)

        val mListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                parseMessage(code, snapshot)?.let { onMessageAdded?.invoke(it) }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                parseMessage(code, snapshot)?.let { onMessageChanged?.invoke(it) }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesListener = mListener
        // Cap history pulled per session; plenty for an active listening session.
        roomRef.child("messages").limitToLast(200).addChildEventListener(mListener)

        val tListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val typingUids = snapshot.children
                    .filter { it.getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }
                    .toSet()
                onTypingUsersChanged?.invoke(typingUids)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        typingListener = tListener
        roomRef.child("typing").addValueEventListener(tListener)
    }

    fun detach() {
        val code = roomCode
        if (code != null) {
            val roomRef = db.child("jams").child(code)
            messagesListener?.let { roomRef.child("messages").removeEventListener(it) }
            typingListener?.let { roomRef.child("typing").removeEventListener(it) }
        }
        messagesListener = null
        typingListener = null
        roomCode = null
    }

    fun sendMessage(
        senderId: String,
        senderName: String,
        senderAvatarUrl: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSenderName: String? = null
    ) {
        val code = roomCode ?: return
        if (text.isBlank()) return
        val messagesRef = db.child("jams").child(code).child("messages")
        val newRef = messagesRef.push()
        val message = mapOf(
            "senderId" to senderId,
            "senderName" to senderName,
            "senderAvatarUrl" to senderAvatarUrl,
            "text" to text,
            "timestamp" to ServerValue.TIMESTAMP,
            "replyToId" to replyToId,
            "replyToText" to replyToText,
            "replyToSenderName" to replyToSenderName
        )
        newRef.setValue(message)
    }

    /** Toggle-style reaction: adding it if the user hasn't reacted with this emoji yet, else removing it. */
    fun toggleReaction(messageId: String, emoji: String, uid: String) {
        val code = roomCode ?: return
        val reactionRef = db.child("jams").child(code).child("messages").child(messageId)
            .child("reactions").child(emoji).child(uid)
        reactionRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) reactionRef.removeValue() else reactionRef.setValue(true)
        }
    }

    fun setTyping(uid: String, isTyping: Boolean) {
        val code = roomCode ?: return
        val ref = db.child("jams").child(code).child("typing").child(uid)
        ref.setValue(isTyping)
        ref.onDisconnect().removeValue()
    }

    private fun parseMessage(jamId: String, snapshot: DataSnapshot): ChatMessage? {
        val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return null
        val senderName = snapshot.child("senderName").getValue(String::class.java) ?: "Someone"
        val senderAvatarUrl = snapshot.child("senderAvatarUrl").getValue(String::class.java) ?: "🎧"
        val text = snapshot.child("text").getValue(String::class.java) ?: return null
        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
        val replyToId = snapshot.child("replyToId").getValue(String::class.java)
        val replyToText = snapshot.child("replyToText").getValue(String::class.java)
        val replyToSenderName = snapshot.child("replyToSenderName").getValue(String::class.java)

        val reactions = snapshot.child("reactions").children.mapNotNull { emojiSnap ->
            val emoji = emojiSnap.key ?: return@mapNotNull null
            val uids = emojiSnap.children.mapNotNull { it.key }
            if (uids.isEmpty()) null else MessageReaction(emoji = emoji, userIds = uids)
        }

        return ChatMessage(
            id = snapshot.key ?: return null,
            jamId = jamId,
            senderId = senderId,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            text = text,
            timestamp = timestamp,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
            reactions = reactions,
            status = MessageStatus.SENT
        )
    }
}
