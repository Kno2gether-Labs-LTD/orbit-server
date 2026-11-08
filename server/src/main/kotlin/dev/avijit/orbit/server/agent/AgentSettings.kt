package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.MasterKey
import dev.avijit.orbit.server.Secrets
import dev.avijit.orbit.server.Store
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock
import java.time.ZoneId

enum class ProviderKind(val wire: String) {
    ANTHROPIC("anthropic"), OPENAI_COMPAT("openai_compat"), SCRIPTED("scripted");

    companion object {
        fun fromWire(s: String?): ProviderKind? = entries.firstOrNull { it.wire == s }
    }
}

/** The configured model. [toString] never shows the key. */
data class ProviderConfig(val kind: ProviderKind, val baseUrl: String?, val model: String, val apiKey: String?) {
    override fun toString() = "ProviderConfig(kind=${kind.wire}, baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey == null) "none" else "***"})"
}

/**
 * Agent provider settings in the `settings` table. The API key is stored only as MasterKey ciphertext (AES-GCM)
 * and is never returned by any endpoint; [hasApiKey] is what a UI may show.
 *
 * `scripted` is a debug provider: it can be saved or loaded only when the process runs with
 * `ORBIT_SCRIPTED_AGENT=1`, and it is then also the default when nothing else is configured (end-to-end runs).
 */
class AgentSettings(private val store: Store, private val masterKey: MasterKey?, env: Map<String, String>) {
    val scriptedAllowed: Boolean = env[SCRIPTED_ENV] == "1"

    fun selectableKinds(): List<ProviderKind> = ProviderKind.entries.filter { it != ProviderKind.SCRIPTED || scriptedAllowed }

    /**
     * The current config, or null when no usable model is configured. A stored openai_compat base URL that [save]
     * would refuse today (plain http to a host outside the local network, or none at all) is never used: see
     * [loadRefusal].
     */
    fun load(): ProviderConfig? {
        val kind = ProviderKind.fromWire(store.getSetting(PROVIDER))
            ?: return if (scriptedAllowed) ProviderConfig(ProviderKind.SCRIPTED, null, ScriptedProvider.MODEL, null) else null
        if (kind == ProviderKind.SCRIPTED && !scriptedAllowed) return null
        if (loadRefusal() != null) {
            val url = store.getSetting(BASE_URL).orEmpty()
            // Once per process per distinct URL (load() runs on every ask); the URL itself is not logged.
            if (warnedUrls.add(Secrets.sha256Hex(url))) {
                log.warn("The stored openai_compat base URL is refused (unsupported, or plain http to a non-local host); configure the model again")
            }
            return null
        }
        val apiKey = store.getSetting(API_KEY)?.let { enc ->
            try {
                masterKey?.decrypt(enc) ?: run { log.warn("A model API key is stored but no master key is loaded"); return null }
            } catch (e: Exception) {
                log.warn("The stored model API key cannot be decrypted ({}); configure the model again", e::class.java.simpleName)
                return null
            }
        }
        val model = store.getSetting(MODEL) ?: return null
        val cfg = ProviderConfig(kind, store.getSetting(BASE_URL), model, apiKey)
        if (kind == ProviderKind.ANTHROPIC && cfg.apiKey.isNullOrBlank()) return null
        return cfg
    }

    fun hasApiKey(): Boolean = store.getSetting(API_KEY) != null

    /** Why the stored provider is not used even though one is saved (for a dashboard notice), or null. */
    fun loadRefusal(): String? {
        if (ProviderKind.fromWire(store.getSetting(PROVIDER)) != ProviderKind.OPENAI_COMPAT) return null
        val url = store.getSetting(BASE_URL) ?: return "The saved OpenAI-compatible provider has no base URL, so it is not used. Save the provider again."
        if (!isHttpUrl(url)) return "The saved base URL is an unsupported or invalid agent URL, so it is not used. Save it again with an https:// URL."
        if (cleartextAllowed(url)) return null
        val host = URI(url).host
        return "The saved base URL uses plain http to $host, which is outside your local network, so it is not used " +
            "(your API key and health data would travel unencrypted). Save it again with an https:// URL."
    }

    /**
     * Saves the provider. [apiKey]: "" removes the key, any other text replaces it, and null keeps the stored key
     * only for the same provider kind (and, for openai_compat, the same base-URL host). A key is never carried to
     * another provider or host, where it would be sent to whoever runs that endpoint.
     * Throws [IllegalArgumentException] (safe message, no key) for an invalid combination.
     */
    fun save(kind: ProviderKind, baseUrl: String?, model: String, apiKey: String?) {
        require(kind != ProviderKind.SCRIPTED || scriptedAllowed) { "the scripted provider is only available with $SCRIPTED_ENV=1" }
        val m = model.trim()
        val url = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val keyAfter = when {
            apiKey == null -> store.getSetting(API_KEY)?.takeIf { sameEndpoint(kind, url) }
            apiKey.isBlank() -> null
            else -> apiKey.trim()
        }
        require(m.length <= 200 && m.none { it.isISOControl() }) { "invalid model name" }
        when (kind) {
            ProviderKind.ANTHROPIC -> require(keyAfter != null) { "an API key is required for Anthropic" }
            ProviderKind.OPENAI_COMPAT -> {
                require(url != null && isHttpUrl(url)) { "base URL must be an http(s) URL, e.g. http://localhost:11434/v1" }
                require(cleartextAllowed(url)) {
                    "use https for this host: plain http is only allowed for this computer (localhost), private network " +
                        "addresses (10.x, 172.16–31.x, 192.168.x, fc00::/7) and .local names, because the API key and your " +
                        "health data would travel unencrypted"
                }
                require(m.isNotEmpty()) { "model is required" }
            }
            ProviderKind.SCRIPTED -> Unit
        }
        val newEnc = if (apiKey != null && keyAfter != null) {
            (masterKey ?: throw IllegalStateException("no master key loaded")).encrypt(keyAfter)
        } else null

        store.putSetting(PROVIDER, kind.wire)
        store.putSetting(MODEL, when {
            m.isNotEmpty() -> m
            kind == ProviderKind.ANTHROPIC -> AnthropicProvider.DEFAULT_MODEL
            else -> ScriptedProvider.MODEL
        })
        if (url != null && kind == ProviderKind.OPENAI_COMPAT) store.putSetting(BASE_URL, url) else store.deleteSetting(BASE_URL)
        when {
            newEnc != null -> store.putSetting(API_KEY, newEnc)
            keyAfter == null -> store.deleteSetting(API_KEY)
        }
        log.info("Agent provider set to {} (model {})", kind.wire, store.getSetting(MODEL))
    }

    fun clear() {
        for (k in listOf(PROVIDER, BASE_URL, MODEL, API_KEY)) store.deleteSetting(k)
    }

    /** True when the stored provider is [kind] and, for openai_compat, its base URL has the same scheme, host and port. */
    private fun sameEndpoint(kind: ProviderKind, url: String?): Boolean {
        if (ProviderKind.fromWire(store.getSetting(PROVIDER)) != kind) return false
        if (kind != ProviderKind.OPENAI_COMPAT) return true
        val old = store.getSetting(BASE_URL) ?: return false
        return try {
            val a = URI(old); val b = URI(url ?: return false)
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port
        } catch (e: Exception) {
            false
        }
    }

    private fun isHttpUrl(s: String): Boolean = try {
        val u = URI(s)
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty() && u.userInfo == null
    } catch (e: Exception) {
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentSettings::class.java)
        private val warnedUrls: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /**
         * https is always fine; plain http only to a host that never leaves the local network: localhost (and
         * `*.localhost`), loopback, RFC 1918 private IPv4, IPv6 unique-local (fc00::/7), and mDNS `*.local` names.
         * Names are judged as written; nothing is resolved.
         */
        fun cleartextAllowed(url: String): Boolean {
            val u = try { URI(url) } catch (e: Exception) { return false }
            if (u.scheme.equals("https", true)) return true
            if (!u.scheme.equals("http", true)) return false
            val host = u.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
            val literal = IPV4.matches(host) || host.contains(':')
            if (!literal) return false
            val addr = try { java.net.InetAddress.getByName(host) } catch (e: Exception) { return false } // literal: no DNS
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress) return true
            val b = addr.address
            return addr is java.net.Inet6Address && (b[0].toInt() and 0xFE) == 0xFC
        }
        const val SCRIPTED_ENV = "ORBIT_SCRIPTED_AGENT"
        const val PROVIDER = "agent.provider"
        const val BASE_URL = "agent.base_url"
        const val MODEL = "agent.model"
        const val API_KEY = "agent.api_key.enc"
    }
}

/** Builds real providers over one shared HTTP client. */
class DefaultProviderFactory(private val http: () -> HttpClient, private val clock: Clock, private val zone: ZoneId) : ProviderFactory {
    override fun create(config: ProviderConfig): ModelProvider = when (config.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(http(), config.apiKey!!, config.model)
        ProviderKind.OPENAI_COMPAT -> OpenAiCompatProvider(http(), config.baseUrl!!, config.model, config.apiKey)
        ProviderKind.SCRIPTED -> ScriptedProvider(clock, zone)
    }
}
