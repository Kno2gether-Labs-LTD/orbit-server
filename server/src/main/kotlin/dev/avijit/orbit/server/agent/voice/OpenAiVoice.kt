package dev.avijit.orbit.server.agent.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI text to speech: `POST https://api.openai.com/v1/audio/speech` with `Authorization: Bearer <key>` and
 * `{model, input, voice, response_format: "mp3"}`; the reply is the MP3 file. OpenAI has no voice-list endpoint, so
 * [voices] is the built-in set.
 */
class OpenAiVoice(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = ENDPOINT,
) : VoiceProvider {
    override val kind = VoiceKind.OPENAI

    override suspend fun voices(): List<Voice> = VOICES.map { Voice(it, it.replaceFirstChar(Char::uppercase)) }

    override suspend fun speak(text: String, voiceId: String): Audio {
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voiceId)
            put("response_format", "mp3")
        }.toString()
        val bytes = VoiceHttp.guard {
            val r = http.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, Audio.MPEG)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            VoiceHttp.body(r, VoiceHttp.MAX_AUDIO_BYTES)
        }
        return VoiceHttp.mp3(bytes)
    }

    companion object {
        const val ENDPOINT = "https://api.openai.com/v1/audio/speech"
        const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        /** The built-in voices of `/v1/audio/speech`. */
        val VOICES = listOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "fable", "marin", "nova", "onyx", "sage", "shimmer", "verse")
    }
}
