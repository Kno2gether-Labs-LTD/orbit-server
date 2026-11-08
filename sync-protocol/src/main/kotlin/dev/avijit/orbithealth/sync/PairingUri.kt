package dev.avijit.orbithealth.sync

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How the app should trust the server's TLS certificate.
 * - [PIN]: a self-signed certificate pinned by its SHA-256 fingerprint (`fp`, required). The default.
 * - [CA]: a public CA certificate (behind a reverse proxy such as Caddy) checked against the exact host of `u`;
 *   `fp` is optional and, when present, must still be a well-formed fingerprint.
 */
enum class TrustMode(val wire: String) {
    PIN("pin"), CA("ca");

    companion object {
        fun fromWire(s: String): TrustMode? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * `orbit://pair?u=<urlenc https url>&fp=<sha256 hex lowercase>&t=<token>&n=<urlenc server name>[&m=pin|ca]`
 *
 * Pin-mode links are written exactly as before (no `m`), so older apps keep reading them. Ca-mode links carry
 * `m=ca` and usually no `fp`.
 *
 * The OrbitWalker glasses app registers [WALKER_PREFIX] (`orbitwalker://pair`, same query) so the Orbit Health app
 * never intercepts its links; [toUri] with that prefix writes one and [parse] accepts both prefixes.
 */
data class PairingUri(
    val url: String,
    val fingerprint: String?,
    val token: String,
    val name: String,
    val mode: TrustMode = TrustMode.PIN,
) {
    init {
        require(mode != TrustMode.PIN || fingerprint != null) { "pin mode needs a fingerprint" }
        require(mode != TrustMode.CA || url.startsWith("https://")) { "ca mode needs an https url" }
    }

    fun toUri(): String = toUri(PREFIX)

    /** This link under [prefix]: [ORBIT_PREFIX] (what [toUri] writes) or [WALKER_PREFIX]. */
    fun toUri(prefix: String): String = buildString {
        require(prefix in PREFIXES) { "unknown pairing prefix" }
        append("$prefix?u=${enc(url)}")
        fingerprint?.let { append("&fp=$it") }
        append("&t=${enc(token)}&n=${enc(name)}")
        if (mode != TrustMode.PIN) append("&m=${mode.wire}")
    }

    companion object {
        /** The Orbit Health app's scheme. */
        const val ORBIT_PREFIX = "orbit://pair"

        /** The OrbitWalker (AR glasses) app's scheme. */
        const val WALKER_PREFIX = "orbitwalker://pair"
        private const val PREFIX = ORBIT_PREFIX
        private val PREFIXES = listOf(ORBIT_PREFIX, WALKER_PREFIX)
        private val FP = Regex("[0-9a-f]{64}")
        private val KEYS = setOf("u", "fp", "t", "n", "m")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * Null for anything that is not exactly a pairing link; never throws. Accepts [ORBIT_PREFIX] and
         * [WALKER_PREFIX] (the result does not say which). Rejected: other prefixes, a missing,
         * empty or repeated key, malformed percent-encoding or UTF-8, a [url] that is not `https://host[:port][/path]`
         * (non-empty host, port 1–65535, no user info), a fingerprint that is not 64 lowercase hex digits, a trust
         * mode other than `pin` or `ca`, and a pin-mode link (the default) without a fingerprint.
         */
        fun parse(s: String): PairingUri? = try {
            parseOrNull(s)
        } catch (e: Exception) {
            null
        }

        private fun parseOrNull(s: String): PairingUri? {
            val prefix = PREFIXES.firstOrNull { s.startsWith("$it?") } ?: return null
            val params = HashMap<String, String>()
            for (part in s.substring(prefix.length + 1).split('&')) {
                val i = part.indexOf('=')
                if (i < 0) return null
                val key = decode(part.substring(0, i)) ?: return null
                val value = decode(part.substring(i + 1)) ?: return null
                if (key in KEYS && params.put(key, value) != null) return null // a repeated key is ambiguous: refuse
            }
            val url = params["u"] ?: return null
            val fp = params["fp"]
            val token = params["t"] ?: return null
            val name = params["n"] ?: return null
            val mode = params["m"]?.let { TrustMode.fromWire(it) ?: return null } ?: TrustMode.PIN
            if (!isHttpsUrl(url)) return null
            if (mode == TrustMode.PIN && fp == null) return null
            if (fp != null && !FP.matches(fp)) return null
            if (token.isEmpty()) return null
            return PairingUri(url, fp, token, name, mode)
        }

        private fun isHttpsUrl(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val u = try {
                URI(url)
            } catch (e: Exception) {
                return false
            }
            if (u.scheme != "https" || u.rawUserInfo != null) return false
            val host = u.host ?: return false // null when the authority does not parse as host[:port]
            if (host.isEmpty()) return false
            return u.port == -1 || u.port in 1..65535
        }

        /** Strict `application/x-www-form-urlencoded` decoding: bad `%` escapes or invalid UTF-8 give null. */
        private fun decode(s: String): String? {
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '+' -> { out.write(' '.code); i++ }
                    c == '%' -> {
                        if (i + 2 >= s.length) return null
                        val hi = Character.digit(s[i + 1], 16)
                        val lo = Character.digit(s[i + 2], 16)
                        if (hi < 0 || lo < 0) return null
                        out.write(hi * 16 + lo)
                        i += 3
                    }
                    else -> {
                        val cp = s.codePointAt(i)
                        out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                        i += Character.charCount(cp)
                    }
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}
