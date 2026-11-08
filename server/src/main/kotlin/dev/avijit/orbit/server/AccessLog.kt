package dev.avijit.orbit.server

import dev.avijit.orbithealth.sync.AccessLogEntry
import java.time.Clock

/**
 * Record of every READ of health data by an agent: agent tokens, MCP calls, `/v1/agent/ask` and the data API.
 * Device writes (ingest) are not logged here; they only update the device's `last_seen`.
 *
 * Open so a request can count the rows it wrote (see [CountingAccessLog]).
 *
 * Callers must keep [record]'s `detail` free of health values and secrets: name the metric and range, not the data.
 */
open class AccessLog(private val store: Store, private val clock: Clock) {

    open fun record(actor: String, action: String, detail: String, rows: Int) {
        store.appendAccessLog(clock.millis(), clean(actor, MAX_ACTOR), clean(action, MAX_ACTION), clean(detail, MAX_DETAIL), rows.coerceAtLeast(0))
    }

    /** Newest first; [limit] is clamped to 1..[MAX_LIMIT]. */
    fun recent(limit: Int): List<AccessLogEntry> = store.accessLog(limit.coerceIn(1, MAX_LIMIT))

    companion object {
        const val MAX_LIMIT = 500
        const val DEFAULT_LIMIT = 100
        const val MAX_ACTOR = 64
        const val MAX_ACTION = 64
        const val MAX_DETAIL = 200

        /** Control characters become spaces (no log-line injection), then the text is capped. */
        private fun clean(s: String, max: Int) = s.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(max)
    }
}

/** Forwards to [inner] and counts the rows written, so a request that logged nothing can add its own row. */
class CountingAccessLog(private val inner: AccessLog, store: Store, clock: Clock) : AccessLog(store, clock) {
    @Volatile var count = 0
        private set

    override fun record(actor: String, action: String, detail: String, rows: Int) {
        inner.record(actor, action, detail, rows)
        count++
    }
}
