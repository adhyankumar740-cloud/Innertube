package com.example.jam

import com.example.data.model.ChatMessage
import com.example.data.model.MessageReaction
import com.example.data.model.MessageStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import timber.log.Timber
import java.time.Instant
import java.util.UUID

/**
 * Real-time group chat for a Jam room, backed by **Supabase** (Postgrest for
 * message persistence/history in the `jam_messages` table, Realtime Broadcast
 * for instant delivery) instead of Firebase Realtime Database.
 *
 * Reuses the SAME realtime channel JamManager already subscribes to for the
 * room (one websocket connection per room handles both playback sync and
 * chat) - call [attach] with `jamManager.realtimeChannel` right after
 * `JamManager.createRoom`/`joinRoom` succeeds.
 *
 * Data lives in:
 *   jam_messages -> one row per message (id, code, sender fields, text,
 *                   reply-to fields, reactions jsonb)
 * Typing indicator moved to JamManager.setTyping() - see its doc comment for
 * why (Presence only supports one tracked state per client per channel).
 */
class JamChatManager {

    @Serializable
    private data class MessageRow(
        val id: String,
        val code: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("sender_name") val senderName: String,
        @SerialName("sender_avatar_url") val senderAvatarUrl: String,
        val text: String,
        @SerialName("reply_to_id") val replyToId: String? = null,
        @SerialName("reply_to_text") val replyToText: String? = null,
        @SerialName("reply_to_sender_name") val replyToSenderName: String? = null,
        val reactions: Map<String, List<String>> = emptyMap(),
        @SerialName("created_at") val createdAt: String? = null,
    ) {
        fun toChatMessage(): ChatMessage = ChatMessage(
            id = id,
            jamId = code,
            senderId = senderId,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            text = text,
            timestamp = createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: System.currentTimeMillis(),
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
            reactions = reactions.mapNotNull { (emoji, uids) -> if (uids.isEmpty()) null else MessageReaction(emoji, uids) },
            status = MessageStatus.SENT,
        )
    }

    @Serializable
    private data class ReactionsColumnUpdate(val reactions: Map<String, List<String>>)

    companion object {
        private const val MESSAGES_TABLE = "jam_messages"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Realtime's broadcast() takes a JsonObject, not an arbitrary
     *  @Serializable instance - encode explicitly rather than relying on
     *  overload resolution to do it for us. */
    private inline fun <reified T> T.toJsonObject(): JsonObject =
        json.encodeToJsonElement(this) as JsonObject

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Timber.tag("JamChatManager").e(e, "Unhandled error in Jam chat background task")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var roomCode: String? = null
    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    var onMessageAdded: ((ChatMessage) -> Unit)? = null
    var onMessageChanged: ((ChatMessage) -> Unit)? = null

    /** Start listening to messages for [code] over [channel] (pass
     *  `jamManager.realtimeChannel`, already subscribed by JamManager). */
    fun attach(code: String, channel: RealtimeChannel?) {
        detach()
        roomCode = code
        val ch = channel ?: return
        this.channel = ch

        jobs += scope.launch {
            ch.broadcastFlow<MessageRow>(event = "chat")
                .catch { e -> Timber.tag("JamChatManager").e(e, "Bad chat payload, skipping") }
                .collect { row -> onMessageAdded?.invoke(row.toChatMessage()) }
        }
        jobs += scope.launch {
            ch.broadcastFlow<MessageRow>(event = "reaction")
                .catch { e -> Timber.tag("JamChatManager").e(e, "Bad reaction payload, skipping") }
                .collect { row -> onMessageChanged?.invoke(row.toChatMessage()) }
        }

        // Load recent history once on attach - equivalent to the old
        // `.limitToLast(200)` on a Firebase ChildEventListener attach.
        jobs += scope.launch {
            try {
                val history = SupabaseClient.client.postgrest[MESSAGES_TABLE]
                    .select(columns = Columns.ALL) {
                        filter { eq("code", code) }
                        order("created_at", Order.ASCENDING)
                        limit(200)
                    }
                    .decodeList<MessageRow>()
                history.forEach { onMessageAdded?.invoke(it.toChatMessage()) }
            } catch (e: Exception) {
                // Non-fatal: live messages over broadcast still work even if history load fails.
                // Logged (rather than silently swallowed) so a broken history load - e.g. schema
                // not migrated, RLS blocking select, network failure - is actually visible in
                // Logcat instead of just showing up as "no messages" with no explanation.
                Timber.tag("JamChatManager").e(e, "Failed to load Jam chat history for room $code")
            }
        }
    }

    fun detach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        channel = null
        roomCode = null
    }

    fun sendMessage(
        senderId: String,
        senderName: String,
        senderAvatarUrl: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSenderName: String? = null,
    ) {
        val code = roomCode ?: return
        val ch = channel ?: return
        if (text.isBlank()) return

        val row = MessageRow(
            id = UUID.randomUUID().toString(),
            code = code,
            senderId = senderId,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            text = text,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
        )
        scope.launch {
            try {
                // Broadcast first so it feels instant (JamManager creates the
                // channel with `broadcast { self = true }`, so our own bubble
                // renders through the same onMessageAdded path as everyone
                // else's - no separate local-echo codepath to keep in sync).
                ch.broadcast(event = "chat", message = row.toJsonObject())
                SupabaseClient.client.postgrest[MESSAGES_TABLE].insert(row)
            } catch (e: Exception) {
                // Best-effort: if persistence fails the message still landed live via broadcast.
                // Logged so a failure here - e.g. the anon key lacking insert rights, the
                // jam_messages table not existing yet - doesn't just look like "chat is broken"
                // with nothing in the logs to explain why.
                Timber.tag("JamChatManager").e(e, "Failed to send/persist Jam chat message")
            }
        }
    }

    /** Toggle-style reaction: adding it if the user hasn't reacted with this emoji yet, else removing it. */
    fun toggleReaction(messageId: String, emoji: String, uid: String) {
        val ch = channel ?: return
        scope.launch {
            try {
                val current = SupabaseClient.client.postgrest[MESSAGES_TABLE]
                    .select(columns = Columns.ALL) { filter { eq("id", messageId) } }
                    .decodeSingleOrNull<MessageRow>() ?: return@launch

                val existingUids = current.reactions[emoji].orEmpty()
                val updatedUids = if (uid in existingUids) existingUids - uid else existingUids + uid
                val updatedReactions = current.reactions.toMutableMap().apply {
                    if (updatedUids.isEmpty()) remove(emoji) else put(emoji, updatedUids)
                }

                SupabaseClient.client.postgrest[MESSAGES_TABLE].update(ReactionsColumnUpdate(updatedReactions)) {
                    filter { eq("id", messageId) }
                }
                ch.broadcast(event = "reaction", message = current.copy(reactions = updatedReactions).toJsonObject())
            } catch (e: Exception) {
                // Best-effort - the tap just silently doesn't register if this fails.
                Timber.tag("JamChatManager").e(e, "Failed to toggle reaction on message $messageId")
            }
        }
    }
}
