package dev.avijit.orbit.server.agent.voice

import dev.avijit.orbit.server.Secrets
import dev.avijit.orbithealth.sync.WalkerVoice
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Server voices for the walker: the configured providers' voice lists (cached [CACHE_MS] per provider, keyed by its
 * key and model so a settings change refetches) and speech with a chosen or the default voice.
 */
class VoiceService(private val settings: VoiceSettings, private val factory: VoiceProviderFactory, private val clock: Clock) {
    private class Cached(val fingerprint: String, val atMs: Long, val voices: List<Voice>)
    private val cache = ConcurrentHashMap<VoiceKind, Cached>()

    /** One configured provider's voice list, or the error code it failed with. */
    class ProviderVoices(val kind: VoiceKind, val voices: List<Voice>, val error: String?)

    sealed interface Outcome {
        class Spoken(val audio: Audio, val voice: String) : Outcome
        /** No provider is configured (or none has a usable voice). */
        data object NoVoice : Outcome
        data object UnknownVoice : Outcome
        class Failed(val kind: VoiceKind?, val code: String) : Outcome
    }

    class Catalog(val voices: List<WalkerVoice>, val defaultVoice: String?, val providers: List<ProviderVoices>)

    /** Voices of each configured provider, in [VoiceKind] order; a provider whose list fails is reported, not thrown. */
    suspend fun providers(): List<ProviderVoices> = settings.configured().map { cfg ->
        try {
            ProviderVoices(cfg.kind, voices(cfg), null)
        } catch (e: VoiceException) {
            log.warn("Voice list from {} failed: {}", cfg.kind.wire, e.code)
            ProviderVoices(cfg.kind, emptyList(), e.code)
        }
    }

    /**
     * Every usable voice, and the effective default: the stored default when it is among them, else the first voice,
     * else null.
     */
    suspend fun catalog(): Catalog {
        val providers = providers()
        val voices = providers.flatMap { p ->
            p.voices.map { WalkerVoice(VoiceSettings.voiceId(p.kind, it.id), it.name, p.kind.wire, p.kind.displayName) }
        }
        val stored = settings.defaultVoice()
        val default = stored?.takeIf { s -> voices.any { it.id == s } } ?: voices.firstOrNull()?.id
        return Catalog(voices, default, providers)
    }

    /** Speaks [text] with [voice] (`provider:voiceId`), or with the default voice when it is null. */
    suspend fun speak(text: String, voice: String?): Outcome {
        val configured = settings.configured()
        if (configured.isEmpty()) return Outcome.NoVoice
        val id = voice ?: run {
            val c = catalog()
            c.defaultVoice ?: return c.providers.firstOrNull { it.error != null }?.let { Outcome.Failed(it.kind, it.error!!) } ?: Outcome.NoVoice
        }
        val (kind, voiceId) = VoiceSettings.parseVoiceId(id) ?: return Outcome.UnknownVoice
        val cfg = configured.firstOrNull { it.kind == kind } ?: return Outcome.UnknownVoice
        return try {
            if (voices(cfg).none { it.id == voiceId }) return Outcome.UnknownVoice
            Outcome.Spoken(factory.create(cfg).speak(text, voiceId), id)
        } catch (e: VoiceException) {
            log.warn("Speech from {} failed: {}", kind.wire, e.code)
            Outcome.Failed(kind, e.code)
        }
    }

    /** Drops the cached voice lists (after a settings change). */
    fun invalidate() = cache.clear()

    private suspend fun voices(cfg: VoiceConfig): List<Voice> {
        val fp = Secrets.sha256Hex("${cfg.apiKey}\u0000${cfg.model}\u0000${cfg.baseUrl}")
        val now = clock.millis()
        cache[cfg.kind]?.let { if (it.fingerprint == fp && now - it.atMs < CACHE_MS) return it.voices }
        val list = factory.create(cfg).voices()
            .filter { VoiceSettings.VOICE.matches(it.id) }
            .distinctBy { it.id }
            .map { it.copy(name = it.name.take(MAX_NAME_CHARS)) }
        cache[cfg.kind] = Cached(fp, now, list)
        return list
    }

    companion object {
        private val log = LoggerFactory.getLogger(VoiceService::class.java)
        const val CACHE_MS = 10 * 60_000L
        private const val MAX_NAME_CHARS = 100
    }
}
