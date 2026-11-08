package dev.avijit.orbit.server

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Validation for `--host` values: a DNS name or a real IP literal, never a URL, port or path. */
object HostNames {
    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
    private val IPV6_CHARS = Regex("""[0-9a-fA-F:.]+""")
    private val LABEL = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")

    /** True only for a syntactically valid IPv4 dotted quad or IPv6 literal (no brackets, no zone). */
    fun isIpLiteral(h: String): Boolean = when {
        IPV4.matches(h) -> true
        ':' in h && IPV6_CHARS.matches(h) -> {
            // Strings containing ':' are parsed as IPv6 literals by the JDK, never looked up in DNS.
            runCatching { InetAddress.getByName(h) is Inet6Address }.getOrDefault(false)
        }
        else -> false
    }

    /**
     * The normalised host (lower case; IP literals in canonical JDK form, without brackets), or null when [raw]
     * is neither a valid RFC 1123 host name nor an IP literal. Names whose last label is all digits are refused
     * so that things like `999.1.1.1` are not mistaken for addresses.
     */
    fun validate(raw: String): String? {
        val t = raw.trim().lowercase()
        val unbracketed = if (t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1) else t
        if (isIpLiteral(unbracketed)) {
            return if (':' in unbracketed) InetAddress.getByName(unbracketed).hostAddress.substringBefore('%').let(::compressIpv6)
            else unbracketed
        }
        if (unbracketed != t) return null
        if (t.isEmpty() || t.length > 253) return null
        val labels = t.removeSuffix(".").split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null
        return t.removeSuffix(".")
    }

    /** Host part for a URL: IPv6 literals get brackets. */
    fun forUrl(host: String): String = if (':' in host) "[$host]" else host

    /**
     * The first non-loopback, up, non-virtual interface's IPv4 address, preferring private (site-local)
     * ranges; null when there is none.
     */
    fun firstLanAddress(): String? {
        val candidates = runCatching { NetworkInterface.networkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
            .sortedBy { it.index }
            .flatMap { it.inetAddresses.toList() }
            .filter { it.address.size == 4 && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        return (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())?.hostAddress
    }

    /** Collapses the JDK's long IPv6 form (`fd00:0:0:0:0:0:0:1`) to the usual short one (`fd00::1`). */
    private fun compressIpv6(full: String): String {
        val parts = full.split(':').map { it.trimStart('0').ifEmpty { "0" } }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < parts.size) {
            if (parts[i] == "0") {
                var j = i
                while (j < parts.size && parts[j] == "0") j++
                if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestStart < 0) return parts.joinToString(":")
        val head = parts.subList(0, bestStart).joinToString(":")
        val tail = parts.subList(bestStart + bestLen, parts.size).joinToString(":")
        return "$head::$tail"
    }
}
