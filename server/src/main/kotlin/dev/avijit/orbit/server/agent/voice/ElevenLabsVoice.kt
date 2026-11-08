package dev.avijit.orbit.server.agent.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendEncodedPathSegments
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLParameter
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * ElevenLabs, with the `xi-api-key` header:
 * - voices: `GET https://api.elevenlabs.io/v2/voices?page_size=100` (the account's voices, including the default ones);
 * - speech: `POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128` with
 *   `{text, model_id}`; the reply is the MP3 file.
 */
class ElevenLabsVoice(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val base: String = BASE,
) : VoiceProvider {
    override val kind = VoiceKind.ELEVENLABS

    override suspend fun voices(): List<Voice> {
        val bytes = VoiceHttp.guard {
            val r = http.get("$base/v2/voices?page_size=100") { header(KEY_HEADER, apiKey) }
            VoiceHttp.body(r, VoiceHttp.MAX_JSON_BYTES)
        }
        val voices = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject["voices"] as JsonArray
        } catch (e: Exception) {
            throw VoiceException("bad_reply")
        }
        return voices.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["voice_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Voice(id, (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id)
        }
    }

    override suspend fun speak(text: String, voiceId: String): Audio {
        if (voiceId.isEmpty() || voiceId == "." || voiceId == "..") throw VoiceException("bad_request")
        val url = URLBuilder(base).apply {
            // One path segment whatever it holds ('/' included), never a way to reach another endpoint.
            appendPathSegments("v1", "text-to-speech")
            appendEncodedPathSegments(voiceId.encodeURLParameter())
            parameters.append("output_format", OUTPUT_FORMAT)
        }.buildString()
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model)
        }.toString()
        val bytes = VoiceHttp.guard {
            val r = http.post(url) {
                header(KEY_HEADER, apiKey)
                header(HttpHeaders.Accept, Audio.MPEG)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            VoiceHttp.body(r, VoiceHttp.MAX_AUDIO_BYTES)
        }
        return VoiceHttp.mp3(bytes)
    }

    companion object {
        const val BASE = "https://api.elevenlabs.io"
        const val KEY_HEADER = "xi-api-key"
        /** Low latency and multilingual, suited to a voice assistant. */
        const val DEFAULT_MODEL = "eleven_flash_v2_5"
        const val OUTPUT_FORMAT = "mp3_44100_128"
    }
}
