package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.HostNames
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Clock

/**
 * Sliding-window limiter: at most [limit] attempts per [windowMs] per key. Every attempt counts unless the caller
 * [refund]s it (the dashboard login refunds successful attempts, so only failures count).
 *
 * Memory is bounded by [maxKeys]. When full, keys whose window has passed are dropped first; if it is still full
 * (traffic from very many addresses), only the least recently used key is evicted. Other keys' limits are never
 * reset by an overflow.
 */
class RateLimiter(private val limit: Int, private val windowMs: Long, private val clock: Clock, private val maxKeys: Int = 10_000) {
    /** Access-ordered: iteration starts at the least recently used key. */
    private val hits = LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true)

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.millis()
        if (!hits.containsKey(key) && hits.size >= maxKeys) makeRoom(now)
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && q.first() <= now - windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        return true
    }

    /** Gives back the most recent attempt for [key] (e.g. a login that succeeded). */
    @Synchronized
    fun refund(key: String) {
        hits[key]?.removeLastOrNull()
    }

    private fun makeRoom(now: Long) {
        hits.entries.removeIf { (_, q) -> q.isEmpty() || q.last() <= now - windowMs }
        if (hits.size >= maxKeys) {
            val it = hits.entries.iterator()
            it.next()
            it.remove()
        }
    }

    companion object {
        /**
         * The limiter key for a TCP peer address: IPv4 as is, IPv4-mapped IPv6 as its IPv4 address, and other IPv6
         * addresses by their /64 prefix (one host usually controls a whole /64, so per-address keys would let it
         * rotate addresses to dodge the limit). Anything that is not an IP literal is used unchanged.
         */
        fun clientKey(remote: String): String {
            val host = remote.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            // isIpLiteral is false for IPv4-mapped IPv6 (the JDK parses it as IPv4), so accept any IPv6-shaped text too.
            val ipv6Shaped = ':' in host && host.all { it in "0123456789abcdefABCDEF:." }
            if (!HostNames.isIpLiteral(host) && !ipv6Shaped) return remote
            val addr = try { InetAddress.getByName(host) } catch (e: Exception) { return remote } // literal: no DNS lookup
            if (addr !is Inet6Address) return addr.hostAddress
            val b = addr.address
            return (0 until 8 step 2).joinToString(":", postfix = "::/64") { i -> "%x".format(((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)) }
        }
    }
}
