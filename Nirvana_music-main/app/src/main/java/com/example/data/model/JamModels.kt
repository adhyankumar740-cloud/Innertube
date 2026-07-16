package com.example.data.model

import com.example.data.database.ChatMessageEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

enum class MessageStatus {
    SENT, DELIVERED, READ
}

@JsonClass(generateAdapter = true)
data class MessageReaction(
    val emoji: String,
    val userIds: List<String>
)

data class ChatMessage(
    val id: String,
    val jamId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String,
    val text: String,
    val timestamp: Long,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSenderName: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val status: MessageStatus = MessageStatus.SENT
) {
    fun toEntity(moshi: Moshi): ChatMessageEntity {
        val type = Types.newParameterizedType(List::class.java, MessageReaction::class.java)
        val adapter = moshi.adapter<List<MessageReaction>>(type)
        val reactionsJson = adapter.toJson(reactions) ?: "[]"
        return ChatMessageEntity(
            id = id,
            jamId = jamId,
            senderId = senderId,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            text = text,
            timestamp = timestamp,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSenderName = replyToSenderName,
            reactionsJson = reactionsJson,
            status = status.name
        )
    }

    companion object {
        fun fromEntity(entity: ChatMessageEntity, moshi: Moshi): ChatMessage {
            val type = Types.newParameterizedType(List::class.java, MessageReaction::class.java)
            val adapter = moshi.adapter<List<MessageReaction>>(type)
            val reactions = try {
                adapter.fromJson(entity.reactionsJson) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            return ChatMessage(
                id = entity.id,
                jamId = entity.jamId,
                senderId = entity.senderId,
                senderName = entity.senderName,
                senderAvatarUrl = entity.senderAvatarUrl,
                text = entity.text,
                timestamp = entity.timestamp,
                replyToId = entity.replyToId,
                replyToText = entity.replyToText,
                replyToSenderName = entity.replyToSenderName,
                reactions = reactions,
                status = try { MessageStatus.valueOf(entity.status) } catch (e: Exception) { MessageStatus.SENT }
            )
        }
    }
}


