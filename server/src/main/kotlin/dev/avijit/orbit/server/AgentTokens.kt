package dev.avijit.orbit.server

import dev.avijit.orbithealth.sync.Scope
import java.time.Clock
import java.util.UUID

/**
 * Agent tokens: the credentials the user's own agents (Claude Code, scripts) use for MCP and the data API.
 * 256-bit random, shown once, stored as SHA-256 only. Each has a name (the access-log actor), a set of scopes and
 * can be revoked; the row is re-read on every request, so revocation and scope changes apply at once.
 */
class AgentTokens(private val store: Store, private val clock: Clock) {

    /** [token] is the only copy of the secret: print it once and drop it. */
    class Created(val row: AgentTokenRow, val token: String) {
        override fun toString() = "Created(id=${row.id}, name=${row.name}, token=***)"
    }

    sealed interface Auth {
        data class Ok(val row: AgentTokenRow) : Auth
        data class Revoked(val row: AgentTokenRow) : Auth
        data object Unknown : Auth
    }

    /**
     * Creates a token. Throws [IllegalArgumentException] (safe message) for an empty name, a name an active token
     * already uses (case-insensitive; it would make the access log ambiguous), no scopes or an unknown scope.
     */
    fun create(name: String, scopes: Set<String>): Created {
        val clean = Pairing.cleanName(name)
        require(clean.isNotEmpty()) { "token name is empty" }
        require(scopes.isNotEmpty()) { "give at least one scope (${Scope.ALL_WIRE.sorted().joinToString(",")} or all)" }
        val unknown = scopes.filter { Scope.fromWire(it) == null }
        require(unknown.isEmpty()) { "unknown scope(s): ${unknown.joinToString(",") { it.take(20) }}; valid: ${Scope.ALL_WIRE.sorted().joinToString(",")}" }
        require(store.listAgentTokens().none { !it.revoked && it.name.equals(clean, ignoreCase = true) }) {
            "an active token is already named '$clean'"
        }
        val token = Secrets.newToken()
        val row = AgentTokenRow(UUID.randomUUID().toString(), clean, Secrets.sha256Hex(token), scopes.toSet(), clock.millis(), revoked = false)
        store.insertAgentToken(row)
        return Created(row, token)
    }

    fun list(): List<AgentTokenRow> = store.listAgentTokens()

    fun revoke(id: String): Boolean = store.revokeAgentToken(id)

    /** The full id for [idOrPrefix] (a full id, or a unique prefix of at least 8 characters); null if none or ambiguous. */
    fun resolveId(idOrPrefix: String): String? {
        val all = list()
        all.firstOrNull { it.id == idOrPrefix }?.let { return it.id }
        if (idOrPrefix.length < 8) return null
        return all.filter { it.id.startsWith(idOrPrefix) }.singleOrNull()?.id
    }

    /** Looks [token] up by hash (never a string comparison of secrets). */
    fun authenticate(token: String?): Auth {
        if (token == null || !Secrets.looksLikeToken(token)) return Auth.Unknown
        val row = store.agentTokenByHash(Secrets.sha256Hex(token)) ?: return Auth.Unknown
        return if (row.revoked) Auth.Revoked(row) else Auth.Ok(row)
    }

    companion object {
        /** `heart,sleep` or `all` → wire scope names. Unknown names pass through for [create] to reject. */
        fun parseScopes(s: String): Set<String> {
            val parts = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            return if (parts == setOf("all")) Scope.ALL_WIRE else parts
        }
    }
}
