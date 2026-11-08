package dev.avijit.orbit.server.agent

import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/** A walker turn waiting for the phone's client-tool results. Everything needed to resume it lives here. */
class PendingTurn(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val state: WalkerState,
    val clientTools: List<ToolSpec>,
    val zoneId: String,
    val createdMs: Long,
    val deadlineMs: Long,
)

/**
 * In-memory store of paused walker turns (lost on restart; the phone then gets 404 and starts over).
 *
 * - Ids are 128-bit random, base64url.
 * - Each turn expires [ttlMs] after it was paused, and is single use: a successful [claim] removes it.
 * - Only the device that started a turn can claim it; for any other device it does not exist.
 * - At most [maxPending] turns. When full, expired turns are evicted oldest first; if none has expired, [put]
 *   refuses (the route answers 503 `busy`). Evicted turns are passed to [onDropped] (for the access log).
 */
class WalkerTurns(
    private val clock: Clock,
    private val maxPending: Int = MAX_PENDING,
    private val ttlMs: Long = TTL_MS,
    private val onDropped: (PendingTurn) -> Unit = {},
) {
    private val turns = java.util.concurrent.ConcurrentHashMap<String, PendingTurn>()
    private val random = SecureRandom()

    sealed interface Claim {
        data class Ok(val turn: PendingTurn) : Claim
        /** Unknown id, or another device's turn. */
        data object Unknown : Claim
        data class Expired(val turn: PendingTurn) : Claim
        /** [claim]'s check refused it; the turn stays pending. */
        data object Rejected : Claim
    }

    val size: Int get() = turns.size

    /** Stores a paused turn and returns its id, or null when the store is full of live turns. */
    fun put(deviceId: String, deviceName: String, state: WalkerState, clientTools: List<ToolSpec>, zoneId: String): String? {
        val dropped = mutableListOf<PendingTurn>()
        val id = synchronized(this) {
            val now = clock.millis()
            if (turns.size >= maxPending) {
                val expired = turns.values.filter { it.deadlineMs <= now }.sortedBy { it.createdMs }
                for (t in expired) {
                    if (turns.size < maxPending) break
                    if (turns.remove(t.id, t)) dropped += t
                }
            }
            if (turns.size >= maxPending) return@synchronized null
            val id = newId()
            turns[id] = PendingTurn(id, deviceId, deviceName, state, clientTools, zoneId, now, now + ttlMs)
            id
        }
        dropped.forEach(onDropped)
        return id
    }

    /**
     * Takes turn [id] for [deviceId] if [accept] agrees (e.g. the results match its calls). An expired turn is removed
     * and reported as [Claim.Expired]; a rejected one stays pending.
     */
    fun claim(id: String, deviceId: String, accept: (PendingTurn) -> Boolean): Claim = synchronized(this) {
        val t = turns[id]
        if (t == null || t.deviceId != deviceId) return@synchronized Claim.Unknown
        if (t.deadlineMs <= clock.millis()) {
            turns.remove(id, t)
            return@synchronized Claim.Expired(t)
        }
        if (!accept(t)) return@synchronized Claim.Rejected
        turns.remove(id, t)
        Claim.Ok(t)
    }

    private fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        const val MAX_PENDING = 64
        const val TTL_MS = 120_000L
    }
}
