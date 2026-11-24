package com.riffbuddy.data

import com.riffbuddy.domain.nowMillis
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One message in the coach chat. */
data class ChatMessage(
    val id: Long,
    val fromPlayer: Boolean,
    val content: String,
    val provider: String,
    val model: String,
    val at: Instant,
)

/** A past chat, as the Past chats list shows it. */
data class Conversation(val id: String, val title: String, val lastAt: Instant, val messages: Int)

/**
 * The coach's chat history: stored on the device, clearable, never sent
 * anywhere on its own.
 *
 * Chats are conversations: a follow-up goes out with the turns
 * before it in the same chat, so "what about for the solo?" knows what "it"
 * is. "What I sent" lists those turns too, so it still shows exactly what
 * leaves the device. New chat starts a fresh conversation; the old ones stay
 * here to reopen.
 *
 * `conversation_id` was "coach" for every message before chats were
 * conversations; that one chat is simply the oldest conversation now.
 */
class ChatRepo(private val db: Db) {

    suspend fun history(conversation: String, limit: Int = 200): List<ChatMessage> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id, role, content, provider, model, created_at FROM ai_messages " +
                "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
            arrayOf(conversation, limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ChatMessage(
                        id = c.getLong(0),
                        fromPlayer = c.getString(1) == "user",
                        content = c.requireString(2),
                        provider = c.requireString(3),
                        model = c.requireString(4),
                        at = Instant.fromEpochMilliseconds(c.getLong(5)),
                    )
                )
            }
        }.reversed()
    }

    /** Every chat, most recently active first, titled by its first question. */
    suspend fun conversations(): List<Conversation> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT conversation_id, MAX(created_at), COUNT(*), " +
                "(SELECT content FROM ai_messages f WHERE f.conversation_id = m.conversation_id AND f.role = 'user' ORDER BY f.id LIMIT 1) " +
                "FROM ai_messages m GROUP BY conversation_id ORDER BY MAX(id) DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Conversation(
                        id = c.requireString(0),
                        title = (c.getString(3) ?: "Chat").lineSequence().first().take(60),
                        lastAt = Instant.fromEpochMilliseconds(c.getLong(1)),
                        messages = c.getInt(2),
                    )
                )
            }
        }
    }

    /** The chat to open on: the one used most recently, or a new one. */
    suspend fun latest(): String = conversations().firstOrNull()?.id ?: newConversation()

    fun newConversation(): String = "chat-${nowMillis()}"

    suspend fun add(
        conversation: String,
        fromPlayer: Boolean,
        content: String,
        provider: String = "",
        model: String = "",
        at: Instant = Clock.System.now(),
    ): Long = withContext(Dispatchers.Blocking) {
        db.writableDatabase.insert(
            "ai_messages", null,
            SqlValues().apply {
                put("conversation_id", conversation)
                put("role", if (fromPlayer) "user" else "assistant")
                put("content", content)
                put("provider", provider)
                put("model", model)
                put("created_at", at.toEpochMilliseconds())
            },
        )
    }

    suspend fun delete(conversation: String): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("ai_messages", "conversation_id = ?", arrayOf(conversation))
    }

    suspend fun clearAll(): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("ai_messages", null, null)
    }
}
