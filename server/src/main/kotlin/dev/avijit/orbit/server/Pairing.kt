package dev.avijit.orbit.server

import dev.avijit.orbithealth.sync.PairResponse
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.TrustMode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Bearer secrets: 256-bit random, base64url without padding. Only their SHA-256 hex is ever stored. */
object Secrets {
    private val random = SecureRandom()
    private val SHAPE = Regex("[A-Za-z0-9_-]{16,128}")

    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { bytes.fill(0) }
    }

    fun sha256Hex(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Cheap shape check before hashing: anything else can never match a stored hash. */
    fun looksLikeToken(s: String): Boolean = SHAPE.matches(s)
}

/**
 * One-time pairing. [newToken] stores only the token's hash with a 10-minute expiry; [redeem] consumes it with a
 * single atomic UPDATE (see [Store.consumePairToken]) so concurrent redemptions yield exactly one device.
 * Secrets are looked up by hash, never compared as strings.
 */
class Pairing(private val store: Store, private val clock: Clock) {

    /** A pin-mode link for a self-signed server with [fingerprint]. */
    fun newToken(url: String, fingerprint: String, serverName: String): PairingUri = mint { PairingUri(url, fingerprint, it, serverName) }

    /** A public-CA link (trust mode `ca`, no fingerprint) for a server behind a reverse proxy at [url]. */
    fun newPublicCaToken(url: String, serverName: String): PairingUri = mint { PairingUri(url, null, it, serverName, TrustMode.CA) }

    private fun mint(link: (token: String) -> PairingUri): PairingUri {
        val now = clock.millis()
        store.purgePairTokens(now)
        val token = Secrets.newToken()
        val uri = link(token)
        store.insertPairToken(Secrets.sha256Hex(token), now + TOKEN_TTL_MS)
        return uri
    }

    /**
     * Consumes [token] and creates a device with every scope. Null when the token is unknown, used or expired.
     * Throws [IllegalArgumentException] for a device name that is empty after cleaning.
     */
    fun redeem(token: String, deviceName: String): PairResponse? {
        val name = cleanName(deviceName)
        require(name.isNotEmpty()) { "device name is empty" }
        if (!Secrets.looksLikeToken(token)) return null
        val now = clock.millis()
        if (!store.consumePairToken(Secrets.sha256Hex(token), now)) return null
        val deviceToken = Secrets.newToken()
        val id = UUID.randomUUID().toString()
        val scopes = Scope.ALL_WIRE
        store.insertDevice(DeviceRow(id, name, Secrets.sha256Hex(deviceToken), scopes, createdMs = now, lastSeenMs = now, revoked = false))
        val serverName = store.getSetting(Settings.SERVER_NAME) ?: DEFAULT_SERVER_NAME
        return PairResponse(deviceToken = deviceToken, deviceId = id, serverName = serverName, scopes = scopes)
    }

    companion object {
        const val TOKEN_TTL_MS = 10 * 60 * 1000L
        const val MAX_DEVICE_NAME = 64
        const val DEFAULT_SERVER_NAME = "Orbit Server"

        /** Drops control characters, collapses whitespace, trims and caps the length. */
        fun cleanName(raw: String): String =
            raw.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .replace(Regex("\\s+"), " ").trim().take(MAX_DEVICE_NAME).trim()
    }
}
