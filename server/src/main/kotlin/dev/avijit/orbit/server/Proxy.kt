package dev.avijit.orbit.server

import dev.avijit.orbit.server.api.RateLimiter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.InetAddress

/** An IPv4 or IPv6 network (`172.16.0.0/12`, `::1/128`; a bare address is a single host). Never resolves names. */
data class Cidr private constructor(private val text: String, private val network: List<Byte>, private val prefix: Int) {
    val isIpv6: Boolean get() = network.size == 16
    val prefixLength: Int get() = prefix

    /** Wider than a /16 (IPv4) or /48 (IPv6): likely more than one proxy's network. */
    val isBroad: Boolean get() = prefix < if (isIpv6) 48 else 16

    /** True when [address] is an IP literal inside this network. Names and junk are never inside. */
    fun contains(address: String): Boolean {
        val bytes = literalBytes(address) ?: return false
        if (bytes.size != network.size) return false
        var bits = prefix
        for (i in bytes.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    override fun toString() = text

    companion object {
        /** Throws [IllegalArgumentException] for anything that is not `address[/prefix]` with a valid prefix. */
        fun parse(spec: String): Cidr {
            val s = spec.trim()
            val slash = s.indexOf('/')
            val addr = if (slash >= 0) s.substring(0, slash) else s
            val bytes = literalBytes(addr) ?: throw IllegalArgumentException("not an IP network: '${s.take(60)}'")
            val max = bytes.size * 8
            val prefix = if (slash >= 0) {
                s.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..max } ?: throw IllegalArgumentException("bad prefix in '${s.take(60)}'")
            } else max
            return Cidr(s, bytes.toList(), prefix)
        }

        /** Bytes of an IP literal (brackets allowed; IPv4-mapped IPv6 becomes IPv4), or null. No DNS lookups. */
        internal fun literalBytes(raw: String): ByteArray? {
            val h = raw.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            val ipv6Shaped = ':' in h && h.all { it in "0123456789abcdefABCDEF:." }
            if (!HostNames.isIpLiteral(h) && !ipv6Shaped) return null
            return try { InetAddress.getByName(h).address } catch (e: Exception) { null } // literal: no lookup
        }
    }
}

/**
 * `run --behind-proxy`: plain HTTP on an internal address, with TLS terminated by a reverse proxy (Caddy) that has a
 * public certificate for [publicUrl].
 *
 * `X-Forwarded-For` and `X-Forwarded-Proto` are believed only when the TCP peer is inside [trusted]; from anyone
 * else they are ignored. The client address is the rightmost `X-Forwarded-For` entry that is not itself a trusted
 * proxy (the leftmost entries are whatever the client chose to send); when every hop is trusted it is the TCP peer.
 * [peerOf] reads the TCP peer (tests replace it).
 */
class ProxyMode(
    val publicUrl: String,
    val trusted: List<Cidr> = DEFAULT_TRUSTED,
    private val peerOf: (ApplicationCall) -> String = { it.request.local.remoteAddress },
) {
    init {
        require(publicUrl.startsWith("https://")) { "the public URL must be https" }
    }

    /** `https://host[:port]` as browsers write it in `Origin` (lower case, no path). */
    val publicOrigin: String = java.net.URI(publicUrl).let { u ->
        "https://${u.host.lowercase()}${if (u.port != -1 && u.port != 443) ":${u.port}" else ""}"
    }

    val publicHost: String = java.net.URI(publicUrl).host.lowercase().removePrefix("[").removeSuffix("]")

    fun isTrusted(address: String): Boolean = trusted.any { it.contains(address) }

    /** The client address given the TCP [peer] and the `X-Forwarded-For` header lines. */
    fun resolveClient(peer: String, forwardedFor: List<String>): String {
        if (!isTrusted(peer)) return peer
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        for (hop in hops.asReversed()) {
            val addr = hopAddress(hop) ?: return peer
            if (!isTrusted(addr)) return addr
        }
        // Every hop is a trusted proxy (or there are none): never fall back to a client-controlled entry.
        return peer
    }

    fun clientAddress(call: ApplicationCall): String =
        resolveClient(peerOf(call), call.request.headers.getAll("X-Forwarded-For").orEmpty())

    /** True only when a trusted proxy says the browser used https (the last `X-Forwarded-Proto` value it set). */
    fun forwardedHttps(call: ApplicationCall): Boolean {
        if (!isTrusted(peerOf(call))) return false
        val proto = call.request.headers.getAll("X-Forwarded-Proto").orEmpty()
            .flatMap { it.split(',') }.map { it.trim() }.lastOrNull { it.isNotEmpty() }
        return proto.equals("https", ignoreCase = true)
    }

    companion object {
        /**
         * Loopback only: a proxy on the same host. Anything else (a Docker network) must be named with
         * `--trusted-proxy`, which `run` requires whenever it binds a non-loopback address.
         */
        val DEFAULT_TRUSTED: List<Cidr> = listOf("127.0.0.0/8", "::1/128").map(Cidr::parse)

        /** `a/n,b/n` (repeatable on the command line). Throws [IllegalArgumentException]. */
        fun parseTrusted(specs: List<String>): List<Cidr> =
            specs.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.map(Cidr::parse)
                .also { require(it.isNotEmpty()) { "no trusted proxy networks given" } }

        /** An `X-Forwarded-For` entry as a canonical IP literal (brackets and an IPv4 `:port` stripped), or null. */
        private fun hopAddress(hop: String): String? {
            var h = hop.trim()
            if (h.startsWith("[")) h = h.substring(1, h.indexOf(']').takeIf { it > 0 } ?: return null)
            else if (h.count { it == ':' } == 1) h = h.substringBefore(':')
            val bytes = Cidr.literalBytes(h) ?: return null
            return InetAddress.getByAddress(bytes).hostAddress.let { if (':' in it) HostNames.validate(it) ?: it else it }
        }
    }
}

/** The client address for limits and logs: the forwarded client behind a trusted proxy, else the TCP peer. */
fun ServerDeps.clientAddress(call: ApplicationCall): String =
    proxy?.clientAddress(call) ?: call.request.origin.remoteAddress

/** The rate-limiter key for this request's client (IPv6 by /64, see [RateLimiter.clientKey]). */
fun ServerDeps.clientKey(call: ApplicationCall): String = RateLimiter.clientKey(clientAddress(call))
