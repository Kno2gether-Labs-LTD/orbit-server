package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.bind
import dev.avijit.orbit.server.query
import dev.avijit.orbit.server.update
import java.security.SecureRandom
import java.util.Base64

/** One stored message of a walker conversation. [role] is [ConversationStore.USER] or [ConversationStore.ASSISTANT]. */
data class StoredMessage(val role: String, val text: String, val createdMs: Long)

/**
 * OrbitWalker conversation history, per device, in the `walker_messages` table. Messages are only ever appended;
 * each device keeps at most [maxPerDevice] (its oldest are deleted). Conversations are just a label on messages:
 * one "exists" once it has a message. Kept deliberately plain so it can grow into long-term memory later.
 *
 * Revoking a device deletes its messages ([Store.revokeDevice]).
 */
class ConversationStore(private val store: Store, private val maxPerDevice: Int = MAX_PER_DEVICE) {
    private val random = SecureRandom()

    /** The last [n] messages of [conversation] for [deviceId], oldest first. Another device's conversation is empty. */
    fun recent(deviceId: String, conversation: String, n: Int): List<StoredMessage> = store.read { c ->
        c.query(
            "SELECT role, text, created_at FROM walker_messages WHERE device_id = ? AND conversation = ? ORDER BY id DESC LIMIT ?",
            deviceId, conversation, n.coerceIn(0, maxPerDevice),
        ) { StoredMessage(it.getString(1), it.getString(2), it.getLong(3)) }.asReversed()
    }

    /** When [conversation]'s newest message was stored, or null when it has none (unknown, or another device's). */
    fun lastMessageMs(deviceId: String, conversation: String): Long? = store.read { c ->
        c.query("SELECT MAX(created_at) FROM walker_messages WHERE device_id = ? AND conversation = ?", deviceId, conversation) { r ->
            r.getLong(1).takeUnless { r.wasNull() }
        }.firstOrNull()
    }

    /**
     * Appends [messages] (role to text, in order) at [atMs] in one transaction, each text cut to [MAX_TEXT_CHARS],
     * then trims the device to its newest [maxPerDevice] messages.
     */
    fun append(deviceId: String, conversation: String, messages: List<Pair<String, String>>, atMs: Long) {
        require(CONVERSATION_ID.matches(conversation)) { "invalid conversation id" }
        require(messages.all { it.first == USER || it.first == ASSISTANT }) { "invalid role" }
        if (messages.isEmpty()) return
        store.transaction { c ->
            c.prepareStatement("INSERT INTO walker_messages(device_id, conversation, role, text, created_at) VALUES (?,?,?,?,?)").use { st ->
                for ((role, text) in messages) st.bind(deviceId, conversation, role, text.take(MAX_TEXT_CHARS), atMs).executeUpdate()
            }
            c.update(
                "DELETE FROM walker_messages WHERE device_id = ? AND id NOT IN " +
                    "(SELECT id FROM walker_messages WHERE device_id = ? ORDER BY id DESC LIMIT ?)",
                deviceId, deviceId, maxPerDevice,
            )
        }
    }

    /** The conversation a walker turn belongs to. [history] is null for a new conversation. */
    data class Resolved(val id: String, val history: List<StoredMessage>?)

    /**
     * [requested] when it is one of [deviceId]'s conversations whose newest message is less than [IDLE_MS] old,
     * with its model history (the last [HISTORY_MESSAGES] messages, oldest dropped until they total at most
     * [HISTORY_CHARS]); otherwise a new conversation. A malformed [requested] is the caller's to refuse.
     */
    fun resolve(deviceId: String, requested: String?, nowMs: Long): Resolved {
        if (requested != null && CONVERSATION_ID.matches(requested)) {
            val last = lastMessageMs(deviceId, requested)
            if (last != null && nowMs - last < IDLE_MS) {
                val msgs = ArrayDeque(recent(deviceId, requested, HISTORY_MESSAGES))
                var total = msgs.sumOf { it.text.length }
                while (total > HISTORY_CHARS && msgs.isNotEmpty()) total -= msgs.removeFirst().text.length
                return Resolved(requested, msgs.toList())
            }
        }
        return Resolved(newId(), null)
    }

    fun count(deviceId: String? = null): Int = store.read { c ->
        if (deviceId == null) c.query("SELECT COUNT(*) FROM walker_messages") { it.getInt(1) }.first()
        else c.query("SELECT COUNT(*) FROM walker_messages WHERE device_id = ?", deviceId) { it.getInt(1) }.first()
    }

    /** Deletes every message of [deviceId]; returns how many. */
    fun deleteAll(deviceId: String): Int = store.write { c -> c.update("DELETE FROM walker_messages WHERE device_id = ?", deviceId) }

    /** Deletes every device's messages; returns how many. */
    fun deleteAll(): Int = store.write { c -> c.update("DELETE FROM walker_messages") }

    /** A new random conversation id (128 bits, base64url, 22 characters). */
    fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val MAX_PER_DEVICE = 2_000
        const val MAX_TEXT_CHARS = 4_000
        const val HISTORY_MESSAGES = 20
        const val HISTORY_CHARS = 16_000
        /** A conversation whose newest message is this old has ended; the next ask starts a new one. */
        const val IDLE_MS = 6 * 60 * 60 * 1000L
        /** What a client may send as `conversation`. */
        val CONVERSATION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
