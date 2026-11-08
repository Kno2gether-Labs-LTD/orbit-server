package dev.avijit.orbit.server.agent.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** A voice of one provider; [id] is the provider's own id (e.g. `coral`), [name] what a person reads. */
data class Voice(val id: String, val name: String)

/** Generated speech. Every provider here is asked for MP3, so [contentType] is `audio/mpeg`. */
class Audio(val bytes: ByteArray, val contentType: String = MPEG) {
    companion object {
        const val MPEG = "audio/mpeg"
    }
}

/** A text-to-speech service. Implementations never log the API key or the text. */
interface VoiceProvider {
    val kind: VoiceKind
    val id: String get() = kind.wire
    val displayName: String get() = kind.displayName

    /** The voices this account can use (a fixed list for providers without a list endpoint). */
    suspend fun voices(): List<Voice>

    /** [text] spoken by [voiceId] (one of [voices]) as MP3. */
    suspend fun speak(text: String, voiceId: String): Audio
}

/**
 * A provider call failed. [code] is a short, safe word for logs and the dashboard: `auth`, `quota`, `rate_limited`,
 * `bad_request`, `not_found`, `unavailable`, `timeout`, `network`, `bad_reply`, `too_large` or `http_error`. Never
 * contains the response body, the text or the key.
 */
class VoiceException(val code: String) : RuntimeException(code)

/** The supported providers, their wire ids (the part before `:` in a voice id) and default models. */
enum class VoiceKind(val wire: String, val displayName: String, val defaultModel: String, val defaultBaseUrl: String? = null) {
    OPENAI("openai", "OpenAI", OpenAiVoice.DEFAULT_MODEL),
    ELEVENLABS("elevenlabs", "ElevenLabs", ElevenLabsVoice.DEFAULT_MODEL),
    /** The only provider with a configurable base URL (e.g. a regional or proxy endpoint). */
    INWORLD("inworld", "Inworld", InworldVoice.DEFAULT_MODEL, InworldVoice.BASE),
    ASSEMBLYAI("assemblyai", "AssemblyAI", AssemblyAiVoice.DEFAULT_MODEL);

    companion object {
        fun fromWire(s: String?): VoiceKind? = entries.firstOrNull { it.wire == s }
    }
}

/** Builds a provider for a configured key and model. Tests substitute fakes. */
fun interface VoiceProviderFactory {
    fun create(config: VoiceConfig): VoiceProvider
}

/** Real providers over one shared HTTP client. */
class DefaultVoiceProviderFactory(private val http: () -> HttpClient) : VoiceProviderFactory {
    override fun create(config: VoiceConfig): VoiceProvider = when (config.kind) {
        VoiceKind.OPENAI -> OpenAiVoice(http(), config.apiKey, config.model)
        VoiceKind.ELEVENLABS -> ElevenLabsVoice(http(), config.apiKey, config.model)
        VoiceKind.INWORLD -> InworldVoice(http(), config.apiKey, config.model, config.baseUrl ?: InworldVoice.BASE)
        VoiceKind.ASSEMBLYAI -> AssemblyAiVoice(http(), config.apiKey, config.model)
    }
}

object VoiceHttp {
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 20_000L
    /** Far above any 1500-character clip; a larger reply is refused rather than buffered. */
    const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    /** JSON replies (voice lists, base64 audio). */
    const val MAX_JSON_BYTES = 16 * 1024 * 1024

    fun client(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /** Runs an HTTP call, turning transport errors into a [VoiceException] (`timeout` or `network`). */
    suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: VoiceException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        if (e::class.simpleName?.contains("Timeout") == true) throw VoiceException("timeout") else throw e
    } catch (e: Exception) {
        throw VoiceException(if (e::class.simpleName?.contains("Timeout") == true) "timeout" else "network")
    }

    /** The error code for a non-success [status]. */
    fun statusCode(status: HttpStatusCode): String = when (status.value) {
        401, 403 -> "auth"
        402 -> "quota"
        404 -> "not_found"
        400, 413, 422 -> "bad_request"
        429 -> "rate_limited"
        408, 504 -> "timeout"
        in 500..599 -> "unavailable"
        else -> "http_error"
    }

    /** The body (at most [max] bytes), or a [VoiceException] for an error status or an oversized body. */
    suspend fun body(r: HttpResponse, max: Int): ByteArray {
        if (!r.status.isSuccess()) throw VoiceException(statusCode(r.status))
        val bytes = r.bodyAsChannel().readRemaining(max + 1L).readByteArray()
        if (bytes.size > max) throw VoiceException("too_large")
        return bytes
    }

    /** [bytes] as MP3 audio; refuses anything that does not start like an MP3 file (ID3 tag or a frame sync). */
    fun mp3(bytes: ByteArray): Audio {
        if (!looksLikeMp3(bytes)) throw VoiceException("bad_reply")
        return Audio(bytes)
    }

    fun looksLikeMp3(b: ByteArray): Boolean = b.size >= 3 && (
        (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) ||
            ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0)
        )
}
