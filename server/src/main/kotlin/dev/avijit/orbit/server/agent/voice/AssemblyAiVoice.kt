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
 * AssemblyAI TTS (model `assemblyai-tts-1`): `POST https://tts.us.assemblyai.com/v1/speech` with
 * `Authorization: <key>` and `{text, voice_id, model_id, encoding: "mp3", sample_rate: 24000, stream: false}`; the reply
 * is the MP3 file. AssemblyAI documents no voice-list endpoint, so [voices] is its published starter set.
 */
class AssemblyAiVoice(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = ENDPOINT,
) : VoiceProvider {
    override val kind = VoiceKind.ASSEMBLYAI

    override suspend fun voices(): List<Voice> = VOICES

    override suspend fun speak(text: String, voiceId: String): Audio {
        val body = buildJsonObject {
            put("text", text)
            put("voice_id", voiceId)
            put("model_id", model)
            put("encoding", "mp3")
            put("sample_rate", 24_000)
            put("stream", false)
        }.toString()
        val bytes = VoiceHttp.guard {
            val r = http.post(endpoint) {
                header(HttpHeaders.Authorization, apiKey)
                header(HttpHeaders.Accept, Audio.MPEG)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            VoiceHttp.body(r, VoiceHttp.MAX_AUDIO_BYTES)
        }
        return VoiceHttp.mp3(bytes)
    }

    companion object {
        const val ENDPOINT = "https://tts.us.assemblyai.com/v1/speech"
        const val DEFAULT_MODEL = "assemblyai-tts-1"

        /** The voices AssemblyAI lists as good starting points (its catalog has more; any of them is a slug like these). */
        val VOICES: List<Voice> = listOf(
            "aaliyah" to "Aaliyah (English, US)", "abdiel" to "Abdiel (English)", "annabelle" to "Annabelle (English, US)",
            "april" to "April (English, US)", "audrey" to "Audrey (English, US)", "buford" to "Buford (English, UK)",
            "carrie" to "Carrie (English, UK)", "cloyd" to "Cloyd (English, UK)", "dorsey" to "Dorsey (English)",
            "ernest" to "Ernest (English, US)", "ewald" to "Ewald (English, US)", "gunnar" to "Gunnar (English)",
            "hailey" to "Hailey (English, US)", "kyla" to "Kyla (English, US)", "lilla" to "Lilla (English, UK)",
            "nancy" to "Nancy (English, UK)", "percival" to "Percival (English, UK)", "peter" to "Peter (English, US)",
            "sara" to "Sara (English, US)", "steven" to "Steven (English, Australia)", "titus" to "Titus (English, US)",
            "troy" to "Troy (English, Scotland)", "yasmin" to "Yasmin (English, UK)",
            "antonioluis" to "Antonio Luis (Spanish, Mexico)", "constanza" to "Constanza (Spanish, Mexico)",
            "malvina" to "Malvina (Spanish, Mexico)", "oriol" to "Oriol (Spanish, Mexico)", "gunther" to "Günther (German)",
            "jorg" to "Jörg (German)", "magdalena" to "Magdalena (German)", "giada" to "Giada (Italian)",
            "donatien" to "Donatien (French)", "benicio" to "Benício (Portuguese)", "nelson" to "Nelson (Portuguese, Brazil)",
        ).map { (slug, name) -> Voice("aai-$slug", name) }
    }
}
