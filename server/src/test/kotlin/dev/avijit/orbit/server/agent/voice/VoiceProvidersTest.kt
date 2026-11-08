package dev.avijit.orbit.server.agent.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** A fake MP3 (ID3 header). */
val MP3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 1, 2, 3)

class VoiceProvidersTest {
    private class Mock(vararg replies: Triple<HttpStatusCode, ByteArray, String>) {
        val requests = mutableListOf<HttpRequestData>()
        private val queue = ArrayDeque(replies.toList())
        val client = HttpClient(MockEngine { req ->
            requests += req
            val (status, body, type) = queue.removeFirst()
            respond(body, status, headersOf(HttpHeaders.ContentType, type))
        })
        fun body(i: Int): JsonObject = Json.parseToJsonElement((requests[i].body as TextContent).text).jsonObject
    }

    private fun audio(b: ByteArray = MP3) = Triple(HttpStatusCode.OK, b, "audio/mpeg")
    private fun json(s: String, status: HttpStatusCode = HttpStatusCode.OK) = Triple(status, s.toByteArray(), "application/json")

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: VoiceException) {
            assertEquals(code, e.code)
        }
    }

    @Test fun openAiRequestShape() = runBlocking {
        val m = Mock(audio())
        val p = OpenAiVoice(m.client, "sk-test", "gpt-4o-mini-tts")
        val a = p.speak("Hello there", "coral")
        assertArrayEquals(MP3, a.bytes)
        assertEquals("audio/mpeg", a.contentType)
        val r = m.requests.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://api.openai.com/v1/audio/speech", r.url.toString())
        assertEquals("Bearer sk-test", r.headers[HttpHeaders.Authorization])
        val b = m.body(0)
        assertEquals("gpt-4o-mini-tts", b["model"]!!.jsonPrimitive.content)
        assertEquals("Hello there", b["input"]!!.jsonPrimitive.content)
        assertEquals("coral", b["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", b["response_format"]!!.jsonPrimitive.content)
        val voices = p.voices()
        assertTrue(voices.contains(Voice("coral", "Coral")))
        assertEquals(OpenAiVoice.VOICES.size, voices.size)
        assertTrue("no request for the fixed list", m.requests.size == 1)
    }

    @Test fun errorsMapToCleanCodes() = runBlocking {
        for ((status, code) in listOf(
            HttpStatusCode.Unauthorized to "auth", HttpStatusCode.Forbidden to "auth", HttpStatusCode.PaymentRequired to "quota",
            HttpStatusCode.TooManyRequests to "rate_limited", HttpStatusCode.BadRequest to "bad_request",
            HttpStatusCode.UnprocessableEntity to "bad_request", HttpStatusCode.InternalServerError to "unavailable",
            HttpStatusCode.ServiceUnavailable to "unavailable", HttpStatusCode.NotFound to "not_found",
        )) {
            val m = Mock(json("""{"error":{"message":"sk-test is wrong"}}""", status))
            expectCode(code) { OpenAiVoice(m.client, "sk-test").speak("hi", "coral") }
        }
        // A 200 that is not audio (e.g. a JSON error page) is refused.
        expectCode("bad_reply") { OpenAiVoice(Mock(json("""{"ok":true}""")).client, "k").speak("hi", "coral") }
        val boom = HttpClient(MockEngine { throw java.io.IOException("connection reset") })
        expectCode("network") { OpenAiVoice(boom, "k").speak("hi", "coral") }
    }

    @Test fun exceptionMessagesNeverCarryKeyOrText() = runBlocking {
        val m = Mock(json("""{"detail":"bad key sk-secret for text Private words"}""", HttpStatusCode.Unauthorized))
        try {
            ElevenLabsVoice(m.client, "sk-secret").speak("Private words", "v1")
            fail()
        } catch (e: VoiceException) {
            assertEquals("auth", e.message)
        }
    }

    @Test fun elevenLabsVoicesAndSpeech() = runBlocking {
        val m = Mock(
            json("""{"voices":[{"voice_id":"abc123","name":"Rachel","category":"premade"},{"voice_id":"def456","name":"My clone"},{"name":"no id"}],"has_more":false}"""),
            audio(),
        )
        val p = ElevenLabsVoice(m.client, "xi-test")
        assertEquals(listOf(Voice("abc123", "Rachel"), Voice("def456", "My clone")), p.voices())
        val list = m.requests[0]
        assertEquals(HttpMethod.Get, list.method)
        assertEquals("https://api.elevenlabs.io/v2/voices?page_size=100", list.url.toString())
        assertEquals("xi-test", list.headers["xi-api-key"])

        assertArrayEquals(MP3, p.speak("Hi", "abc123").bytes)
        val r = m.requests[1]
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/abc123?output_format=mp3_44100_128", r.url.toString())
        assertEquals("xi-test", r.headers["xi-api-key"])
        assertEquals("Hi", m.body(1)["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_flash_v2_5", m.body(1)["model_id"]!!.jsonPrimitive.content)
    }

    @Test fun elevenLabsVoiceIdIsAPathSegment() = runBlocking {
        val m = Mock(audio())
        ElevenLabsVoice(m.client, "k").speak("Hi", "../v1/user")
        assertTrue(m.requests[0].url.toString(), m.requests[0].url.encodedPath.startsWith("/v1/text-to-speech/..%2Fv1%2Fuser"))
    }

    @Test fun inworldVoicesAndSpeech() = runBlocking {
        val b64 = Base64.getEncoder().encodeToString(MP3)
        val m = Mock(
            json("""{"voices":[{"voiceId":"Ashley","displayName":"Ashley","languageCode":"en-US","source":"SYSTEM"},{"voiceId":"ws__clone","displayName":"Mine"}],"nextPageToken":""}"""),
            json("""{"audioContent":"$b64","usage":{"processedCharactersCount":2,"modelId":"inworld-tts-2"}}"""),
        )
        // A pasted "Basic " prefix is not doubled.
        val p = InworldVoice(m.client, "Basic QUJDOmRlZg==")
        assertEquals(listOf(Voice("Ashley", "Ashley (en-US)"), Voice("ws__clone", "Mine")), p.voices())
        val list = m.requests[0]
        assertEquals("https://api.inworld.ai/voices/v1/voices?pageSize=500", list.url.toString())
        assertEquals("Basic QUJDOmRlZg==", list.headers[HttpHeaders.Authorization])

        assertArrayEquals(MP3, p.speak("Hi", "Ashley").bytes)
        val r = m.requests[1]
        assertEquals("https://api.inworld.ai/tts/v1/voice", r.url.toString())
        assertEquals("Basic QUJDOmRlZg==", r.headers[HttpHeaders.Authorization])
        val b = m.body(1)
        assertEquals("Hi", b["text"]!!.jsonPrimitive.content)
        assertEquals("Ashley", b["voiceId"]!!.jsonPrimitive.content)
        assertEquals("inworld-tts-2", b["modelId"]!!.jsonPrimitive.content)
        assertEquals("MP3", b["audioConfig"]!!.jsonObject["audioEncoding"]!!.jsonPrimitive.content)

        expectCode("bad_reply") { InworldVoice(Mock(json("""{"usage":{}}""")).client, "k").speak("Hi", "Ashley") }
    }

    @Test fun inworldBaseUrlOverride() = runBlocking {
        val m = Mock(json("""{"audioContent":"${Base64.getEncoder().encodeToString(MP3)}"}"""))
        InworldVoice(m.client, "k", base = "https://inworld.example.com").speak("Hi", "Ashley")
        assertEquals("https://inworld.example.com/tts/v1/voice", m.requests[0].url.toString())
        assertEquals("Basic k", m.requests[0].headers[HttpHeaders.Authorization])
    }

    @Test fun assemblyAiRequestShape() = runBlocking {
        val m = Mock(audio())
        val p = AssemblyAiVoice(m.client, "aai-key")
        assertArrayEquals(MP3, p.speak("Hello", "aai-kyla").bytes)
        val r = m.requests.single()
        assertEquals("https://tts.us.assemblyai.com/v1/speech", r.url.toString())
        assertEquals("aai-key", r.headers[HttpHeaders.Authorization])
        val b = m.body(0)
        assertEquals("Hello", b["text"]!!.jsonPrimitive.content)
        assertEquals("aai-kyla", b["voice_id"]!!.jsonPrimitive.content)
        assertEquals("assemblyai-tts-1", b["model_id"]!!.jsonPrimitive.content)
        assertEquals("mp3", b["encoding"]!!.jsonPrimitive.content)
        assertEquals(24_000, b["sample_rate"]!!.jsonPrimitive.int)
        assertEquals(false, b["stream"]!!.jsonPrimitive.boolean)
        assertTrue(p.voices().any { it.id == "aai-kyla" })
    }

    @Test fun mp3Sniffing() {
        assertTrue(VoiceHttp.looksLikeMp3(MP3))
        assertTrue(VoiceHttp.looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())))
        assertTrue(!VoiceHttp.looksLikeMp3("{\"a\":1}".toByteArray()))
        assertTrue(!VoiceHttp.looksLikeMp3(ByteArray(0)))
    }
}
