package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.AccessLog
import dev.avijit.orbit.server.AgentTokenRow
import dev.avijit.orbit.server.AgentTokens
import dev.avijit.orbit.server.HostNames
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.net.InetAddress
import java.net.URI

/**
 * Agent-token auth for MCP and the data API: `Authorization: Bearer <agentToken>`, a separate credential from device
 * tokens (neither works in the other's place). Unknown, malformed and revoked tokens all get the same 401
 * `invalid_token`; use of a revoked token is also written to the access log under its name (rate-limited like any
 * request, so a looping script cannot flood the log). Valid tokens share one budget of
 * [dev.avijit.orbit.server.Limits.agentRequestsPerMinute] requests per minute across MCP and REST (429 past it).
 */
class AgentGate(private val tokens: AgentTokens, private val accessLog: AccessLog, private val limiter: RateLimiter) {

    /** The current token row for this request, or throws [ApiError] 401 / 429. [surface] names the endpoint in the log. */
    fun require(call: ApplicationCall, surface: String): AgentTokenRow =
        when (val auth = tokens.authenticate(bearerToken(call))) {
            is AgentTokens.Auth.Ok -> {
                if (!limiter.tryAcquire(auth.row.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
                auth.row
            }
            is AgentTokens.Auth.Revoked -> {
                if (limiter.tryAcquire(auth.row.id)) accessLog.record(auth.row.name, surface, "DENIED revoked token", 0)
                throw ApiError(HttpStatusCode.Unauthorized, INVALID_TOKEN)
            }
            AgentTokens.Auth.Unknown -> throw ApiError(HttpStatusCode.Unauthorized, INVALID_TOKEN)
        }

    /** Charges one more unit of [row]'s per-minute budget (extra tools/call messages in an MCP batch). */
    fun tryCharge(row: AgentTokenRow): Boolean = limiter.tryAcquire(row.id)

    companion object {
        const val INVALID_TOKEN = "invalid_token"

        fun bearerToken(call: ApplicationCall): String? {
            val h = call.request.headers[HttpHeaders.Authorization]?.trim() ?: return null
            val space = h.indexOf(' ')
            if (space <= 0 || !h.substring(0, space).equals("Bearer", ignoreCase = true)) return null
            return h.substring(space + 1).trim().takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Browser `Origin` check against DNS rebinding: a web page on an attacker's name that resolves to this LAN server
 * must not reach `/mcp`. Non-browser MCP clients send no Origin and pass. Allowed origins are http(s) on loopback
 * names (`localhost`, `*.localhost`), loopback / private / link-local / unique-local IP literals, mDNS `*.local`
 * names, and the server's own configured names ([extraHosts], e.g. the certificate's names).
 */
object OriginPolicy {
    fun allowed(origin: String?, extraHosts: Set<String>): Boolean {
        if (origin == null) return true
        val uri = try { URI(origin.trim()) } catch (e: Exception) { return false }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.trimEnd('.') ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host in extraHosts.map { it.lowercase() }) return true
        if (!HostNames.isIpLiteral(host)) return false
        val addr = try { InetAddress.getByName(host) } catch (e: Exception) { return false } // literal: no DNS lookup
        val uniqueLocal = addr.address.size == 16 && (addr.address[0].toInt() and 0xfe) == 0xfc
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || uniqueLocal
    }
}
