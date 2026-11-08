package dev.avijit.orbit.server.agent.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Inworld TTS, with `Authorization: Basic <key>` (the key is the Base64 credential the Inworld portal shows, used
 * as is; a pasted `Basic ` prefix is dropped):
 * - voices: `GET https://api.inworld.ai/voices/v1/voices?pageSize=500` (system voices and the workspace's own);
 * - speech: `POST https://api.inworld.ai/tts/v1/voice` with `{text, voiceId, modelId, audioConfig: {audioEncoding: "MP3"}}`;
 *   the reply is JSON with the audio as base64 in `audioContent`.
 */
class InworldVoice(
    private val http: HttpClient,
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val base: String = BASE,
) : VoiceProvider {
    override val kind = VoiceKind.INWORLD
    private val auth = "Basic " + apiKey.trim().removePrefix("Basic ").trim()

    override suspend fun voices(): List<Voice> {
        val bytes = VoiceHttp.guard {
            val r = http.get("$base/voices/v1/voices?pageSize=500") { header(HttpHeaders.Authorization, auth) }
            VoiceHttp.body(r, VoiceHttp.MAX_JSON_BYTES)
        }
        val voices = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject["voices"] as JsonArray
        } catch (e: Exception) {
            throw VoiceException("bad_reply")
        }
        return voices.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["voiceId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o["displayName"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
            val lang = (o["languageCode"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            Voice(id, if (lang != null) "$name ($lang)" else name)
        }
    }

    override suspend fun speak(text: String, voiceId: String): Audio {
        val body = buildJsonObject {
            put("text", text)
            put("voiceId", voiceId)
            put("modelId", model)
            putJsonObject("audioConfig") { put("audioEncoding", "MP3") }
        }.toString()
        val bytes = VoiceHttp.guard {
            val r = http.post("$base/tts/v1/voice") {
                header(HttpHeaders.Authorization, auth)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            VoiceHttp.body(r, VoiceHttp.MAX_JSON_BYTES)
        }
        val audio = try {
            val b64 = (Json.parseToJsonElement(bytes.decodeToString()).jsonObject["audioContent"] as JsonPrimitive).content
            Base64.getDecoder().decode(b64)
        } catch (e: Exception) {
            throw VoiceException("bad_reply")
        }
        return VoiceHttp.mp3(audio)
    }

    companion object {
        const val BASE = "https://api.inworld.ai"
        const val DEFAULT_MODEL = "inworld-tts-2"
    }
}
