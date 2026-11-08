package dev.avijit.orbit.server.web

import dev.avijit.orbit.server.Secrets
import java.security.MessageDigest
import java.time.Clock

/**
 * One dashboard browser session. Anonymous sessions exist only to carry the login form's CSRF token; a successful
 * login replaces them with a new authenticated session under a new id.
 * [adminTag] ties an authenticated session to the admin password hash it was created under.
 */
class DashSession internal constructor(
    val csrf: String,
    val authenticated: Boolean,
    val createdMs: Long,
    val adminTag: String?,
) {
    @Volatile var lastSeenMs: Long = createdMs
        internal set

    /** Constant-time comparison of a submitted CSRF token. */
    fun csrfMatches(submitted: String?): Boolean =
        submitted != null && MessageDigest.isEqual(submitted.toByteArray(Charsets.UTF_8), csrf.toByteArray(Charsets.UTF_8))
}

/**
 * Server-side session store, in memory (a restart signs everyone out). Session ids are 256-bit random and only
 * their SHA-256 is kept as the key. Sessions end after [idleMs] without a request or [absoluteMs] after creation,
 * whichever comes first. At most [maxSessions] are kept: when full, the least recently used anonymous session is
 * evicted first, so a flood of login-page visits cannot push out a signed-in admin.
 */
class SessionStore(
    private val clock: Clock,
    val idleMs: Long = 30 * 60_000L,
    val absoluteMs: Long = 12 * 60 * 60_000L,
    private val maxSessions: Int = 1_000,
) {
    private val byHash = LinkedHashMap<String, DashSession>(16, 0.75f, true)

    /** A new session; returns the raw id (for the cookie only) and the session. */
    @Synchronized
    fun create(authenticated: Boolean, adminTag: String?): Pair<String, DashSession> {
        val now = clock.millis()
        purge(now)
        while (byHash.size >= maxSessions) evictOne()
        val id = Secrets.newToken()
        val s = DashSession(Secrets.newToken(), authenticated, now, adminTag)
        byHash[Secrets.sha256Hex(id)] = s
        return id to s
    }

    /** The live session for [id] (touching it), or null. Authenticated sessions die if the admin password changed. */
    @Synchronized
    fun get(id: String?, adminTag: String?): DashSession? {
        if (id == null || !Secrets.looksLikeToken(id)) return null
        val key = Secrets.sha256Hex(id)
        val s = byHash[key] ?: return null
        val now = clock.millis()
        if (expired(s, now) || (s.authenticated && s.adminTag != adminTag)) {
            byHash.remove(key)
            return null
        }
        s.lastSeenMs = now
        return s
    }

    @Synchronized
    fun remove(id: String?) {
        if (id != null && Secrets.looksLikeToken(id)) byHash.remove(Secrets.sha256Hex(id))
    }

    @Synchronized
    fun size(): Int = byHash.size

    private fun expired(s: DashSession, now: Long) = now - s.lastSeenMs > idleMs || now - s.createdMs > absoluteMs

    private fun purge(now: Long) {
        byHash.entries.removeIf { expired(it.value, now) }
    }

    private fun evictOne() {
        val victim = byHash.entries.firstOrNull { !it.value.authenticated }?.key ?: byHash.keys.first()
        byHash.remove(victim)
    }
}
