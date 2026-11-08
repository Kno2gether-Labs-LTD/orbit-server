package dev.avijit.orbit.server.agent.voice

import dev.avijit.orbit.server.MasterKey
import dev.avijit.orbit.server.Store
import org.slf4j.LoggerFactory

/** A configured provider. [toString] never shows the key. */
data class VoiceConfig(val kind: VoiceKind, val apiKey: String, val model: String, val baseUrl: String? = null) {
    override fun toString() = "VoiceConfig(kind=${kind.wire}, model=$model, baseUrl=$baseUrl, apiKey=***)"
}

/**
 * Voice (text-to-speech) settings in the `settings` table: per provider an API key, stored only as MasterKey
 * ciphertext and never returned by any endpoint, and an optional model; plus the default voice (`provider:voiceId`).
 * Any subset of providers may be configured.
 */
class VoiceSettings(private val store: Store, private val masterKey: MasterKey?) {

    /** Providers with a key that decrypts, in [VoiceKind] order. */
    fun configured(): List<VoiceConfig> = VoiceKind.entries.mapNotNull(::load)

    fun load(kind: VoiceKind): VoiceConfig? {
        val enc = store.getSetting(keyName(kind)) ?: return null
        val key = try {
            masterKey?.decrypt(enc) ?: run { log.warn("A {} voice key is stored but no master key is loaded", kind.wire); return null }
        } catch (e: Exception) {
            log.warn("The stored {} voice key cannot be decrypted ({}); enter it again", kind.wire, e::class.java.simpleName)
            return null
        }
        return VoiceConfig(kind, key, model(kind), baseUrl(kind))
    }

    fun hasKey(kind: VoiceKind): Boolean = store.getSetting(keyName(kind)) != null

    /** The stored model, or null for the provider's default. */
    fun storedModel(kind: VoiceKind): String? = store.getSetting(modelName(kind))

    fun model(kind: VoiceKind): String = storedModel(kind) ?: kind.defaultModel

    /** The stored base URL override, or null for the official endpoint (only providers with a [VoiceKind.defaultBaseUrl]). */
    fun storedBaseUrl(kind: VoiceKind): String? = if (kind.defaultBaseUrl == null) null else store.getSetting(baseUrlName(kind))

    fun baseUrl(kind: VoiceKind): String? = storedBaseUrl(kind) ?: kind.defaultBaseUrl

    /**
     * Saves [kind]. [apiKey]: null keeps the stored key, "" removes it, other text replaces it. [model]: blank means
     * the provider's default. Throws [IllegalArgumentException] (safe message) for an invalid model, and
     * [IllegalStateException] when a key must be stored but no master key is loaded.
     */
    fun save(kind: VoiceKind, apiKey: String?, model: String?, baseUrl: String? = null) {
        val m = model?.trim().orEmpty()
        require(m.isEmpty() || MODEL.matches(m)) { "invalid model name" }
        val url = baseUrl?.let(::normalizeBaseUrl)
        require(url == null || kind.defaultBaseUrl != null) { "${kind.displayName} has no configurable base URL" }
        val key = apiKey?.trim()
        require(key == null || key.length <= MAX_KEY_CHARS && key.none { it.isISOControl() }) { "invalid API key" }
        val enc = if (!key.isNullOrEmpty()) (masterKey ?: throw IllegalStateException("no master key loaded")).encrypt(key) else null
        when {
            enc != null -> store.putSetting(keyName(kind), enc)
            key != null -> store.deleteSetting(keyName(kind))
        }
        if (m.isEmpty()) store.deleteSetting(modelName(kind)) else store.putSetting(modelName(kind), m)
        if (kind.defaultBaseUrl != null) {
            if (url == null || url == kind.defaultBaseUrl) store.deleteSetting(baseUrlName(kind)) else store.putSetting(baseUrlName(kind), url)
        }
        log.info("Voice provider {} saved (key {}, model {})", kind.wire, if (hasKey(kind)) "set" else "none", model(kind))
    }

    /** The stored default voice id (`provider:voiceId`), or null. It may name a provider that is no longer configured. */
    fun defaultVoice(): String? = store.getSetting(DEFAULT)

    /** Sets the default voice; null or blank clears it. Throws [IllegalArgumentException] for a malformed id. */
    fun setDefaultVoice(id: String?) {
        if (id.isNullOrBlank()) {
            store.deleteSetting(DEFAULT)
            return
        }
        require(parseVoiceId(id) != null) { "invalid voice" }
        store.putSetting(DEFAULT, id)
    }

    companion object {
        private val log = LoggerFactory.getLogger(VoiceSettings::class.java)
        const val DEFAULT = "voice.default"
        private const val MAX_KEY_CHARS = 1_000
        private val MODEL = Regex("^[A-Za-z0-9._:/-]{1,100}$")
        /** Provider voice ids as providers issue them (slugs, ids, workspace-prefixed clones). */
        val VOICE = Regex("^[A-Za-z0-9._~@+:/-]{1,200}$")

        fun keyName(kind: VoiceKind) = "voice.${kind.wire}.api_key.enc"
        fun modelName(kind: VoiceKind) = "voice.${kind.wire}.model"
        fun baseUrlName(kind: VoiceKind) = "voice.${kind.wire}.base_url"

        /**
         * A base URL override: blank is null (the official endpoint); otherwise an `https://` URL without user info,
         * query or fragment, trailing slashes dropped. A full Inworld speech URL (`…/tts/v1/voice`) is cut to its base.
         * Throws [IllegalArgumentException] otherwise; the key would travel to whatever host this names.
         */
        fun normalizeBaseUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/').removeSuffix("/tts/v1/voice").trimEnd('/')
            if (t.isEmpty()) return null
            val u = try { java.net.URI(t) } catch (e: Exception) { throw IllegalArgumentException("base URL must be an https:// URL") }
            require(u.scheme.equals("https", true) && !u.host.isNullOrEmpty() && u.userInfo == null && u.rawQuery == null && u.rawFragment == null) {
                "base URL must be an https:// URL"
            }
            return t
        }

        /** `provider:voiceId` split into its provider and the provider's own id, or null when malformed. */
        fun parseVoiceId(id: String): Pair<VoiceKind, String>? {
            val kind = VoiceKind.fromWire(id.substringBefore(':', "")) ?: return null
            val voice = id.substringAfter(':')
            return if (VOICE.matches(voice)) kind to voice else null
        }

        fun voiceId(kind: VoiceKind, voice: String) = "${kind.wire}:$voice"
    }
}
