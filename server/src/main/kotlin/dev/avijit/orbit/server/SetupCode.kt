package dev.avijit.orbit.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * First-run setup without default credentials. While no admin password exists, `run` issues a one-time setup code
 * and prints it to stdout only (never to a log file or the access log). The dashboard's "Set up Orbit Server" page
 * takes the code plus a new password; [complete] stores the bcrypt hash and burns the code in one transaction.
 *
 * The code is 30 Crockford base32 characters (150 bits) in six groups of five. Only its SHA-256 and expiry are
 * stored; with 150 random bits a fast hash is enough. Issuing a new code replaces (invalidates) the old one.
 */
class SetupCode(private val store: Store, private val clock: Clock) {

    data class Issued(val code: String, val expiresMs: Long) {
        override fun toString() = "SetupCode.Issued(expiresMs=$expiresMs)" // never the code
    }

    enum class Outcome { DONE, BAD_CODE, ALREADY_SET_UP }

    /** A fresh code valid for [TTL_MS]; any earlier code stops working. */
    fun issue(): Issued {
        val chars = CharArray(CHARS) { ALPHABET[random.nextInt(32)] }
        val bare = String(chars)
        chars.fill('\u0000')
        val expires = clock.millis() + TTL_MS
        store.putSetting(Settings.SETUP_CODE_HASH, Secrets.sha256Hex(bare))
        store.putSetting(Settings.SETUP_CODE_EXPIRES, expires.toString())
        return Issued(bare.chunked(GROUP).joinToString("-"), expires)
    }

    /** True while [input] is the pending, unexpired code (a cheap pre-check before hashing a password). */
    fun matches(input: String): Boolean {
        val norm = normalize(input) ?: return false
        val stored = store.getSetting(Settings.SETUP_CODE_HASH) ?: return false
        val expires = store.getSetting(Settings.SETUP_CODE_EXPIRES)?.toLongOrNull() ?: return false
        if (clock.millis() >= expires) return false
        return MessageDigest.isEqual(stored.toByteArray(), Secrets.sha256Hex(norm).toByteArray())
    }

    /** Sets the admin password hash and burns the code, if [input] is the pending code and no admin exists yet. */
    fun complete(input: String, bcryptHash: String): Outcome {
        val norm = normalize(input) ?: return if (store.getSetting(Settings.ADMIN_HASH) != null) Outcome.ALREADY_SET_UP else Outcome.BAD_CODE
        return store.completeSetup(Secrets.sha256Hex(norm), clock.millis(), bcryptHash)
    }

    companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        private const val CHARS = 30
        private const val GROUP = 5
        const val BITS = CHARS * 5
        /** Crockford base32: no I, L, O or U, so the code survives being read aloud or retyped. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val random = SecureRandom()

        /**
         * The canonical form of a typed code: upper case, spaces and dashes dropped, look-alikes mapped (O→0,
         * I/L→1). Null unless exactly 30 alphabet characters remain.
         */
        fun normalize(input: String): String? {
            if (input.length > 100) return null
            val s = input.uppercase().filterNot { it == '-' || it.isWhitespace() }
                .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
            return s.takeIf { it.length == CHARS && it.all { c -> c in ALPHABET } }
        }

        /** The stdout banner for [issued]. */
        fun banner(issued: Issued, setupUrl: String, zone: ZoneId = ZoneId.systemDefault()): String {
            val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(zone).format(Instant.ofEpochMilli(issued.expiresMs))
            val rule = "=".repeat(72)
            return buildString {
                appendLine(rule)
                appendLine("  Orbit Server is not set up yet. There is no default password.")
                appendLine()
                appendLine("  Open  $setupUrl")
                appendLine("  and enter this one-time setup code:")
                appendLine()
                appendLine("      ${issued.code}")
                appendLine()
                appendLine("  It works once and expires in 24 hours (at $at).")
                appendLine("  Need a new one? Run: orbit-server setup-code   (or restart the server)")
                appendLine(rule)
            }
        }
    }
}
