package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.agent.voice.Audio
import dev.avijit.orbit.server.agent.voice.MP3
import dev.avijit.orbit.server.agent.voice.Voice
import dev.avijit.orbit.server.agent.voice.VoiceConfig
import dev.avijit.orbit.server.agent.voice.VoiceException
import dev.avijit.orbit.server.agent.voice.VoiceKind
import dev.avijit.orbit.server.agent.voice.VoiceProvider
import dev.avijit.orbit.server.agent.voice.VoiceProviderFactory
import dev.avijit.orbit.server.agent.voice.VoiceService
import dev.avijit.orbithealth.sync.WalkerSpeakRequest
import dev.avijit.orbithealth.sync.WalkerVoice
import dev.avijit.orbithealth.sync.WalkerVoicesResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake providers: fixed voices per kind; [failVoices] / [failSpeak] make a kind fail with that code. */
class FakeVoices : VoiceProviderFactory {
    val voices = mutableMapOf(
        VoiceKind.OPENAI to listOf(Voice("alloy", "Alloy"), Voice("coral", "Coral")),
        VoiceKind.ELEVENLABS to listOf(Voice("abc123", "Rachel")),
        VoiceKind.INWORLD to listOf(Voice("Ashley", "Ashley")),
        VoiceKind.ASSEMBLYAI to listOf(Voice("aai-kyla", "Kyla")),
    )
    val failVoices = mutableMapOf<VoiceKind, String>()
    val failSpeak = mutableMapOf<VoiceKind, String>()
    val listCalls = mutableMapOf<VoiceKind, Int>()
    val spoken = mutableListOf<Triple<VoiceConfig, String, String>>()

    override fun create(config: VoiceConfig): VoiceProvider = object : VoiceProvider {
        override val kind = config.kind
        override suspend fun voices(): List<Voice> {
            listCalls.merge(kind, 1, Int::plus)
            failVoices[kind]?.let { throw VoiceException(it) }
            return voices.getValue(kind)
        }
        override suspend fun speak(text: String, voiceId: String): Audio {
            failSpeak[kind]?.let { throw VoiceException(it) }
            spoken += Triple(config, text, voiceId)
            return Audio(MP3)
        }
    }
}

class WalkerVoiceRoutesTest {
    private fun withVoices(limits: Limits = Limits(), block: suspend ApiEnv.(FakeVoices) -> Unit) {
        val fake = FakeVoices()
        withApi(limits = limits, voiceProviderFactory = fake) { block(fake) }
    }

    private suspend fun ApiEnv.voices(token: String?): HttpResponse = client.get("/v1/walker/voices") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun ApiEnv.speak(token: String?, text: String, voice: String? = null): HttpResponse =
        postJson("/v1/walker/speak", testJson.encodeToString(WalkerSpeakRequest(text, voice)), token)

    private suspend fun HttpResponse.error(): String = bodyAsText().also { assertTrue(it, it.startsWith("{\"error\":")) }

    @Test fun needsADeviceToken() = withVoices { _ ->
        assertEquals(HttpStatusCode.Unauthorized, voices(null).status)
        assertEquals(HttpStatusCode.Unauthorized, speak(null, "hi").status)
        assertEquals(HttpStatusCode.Unauthorized, speak("not-a-token", "hi").status)
    }

    @Test fun nothingConfigured() = withVoices { _ ->
        val dev = pairedDevice("Glasses")
        val r = voices(dev.deviceToken)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"voices":[],"default":null}""", r.bodyAsText())
        val s = speak(dev.deviceToken, "hello")
        assertEquals(HttpStatusCode.NotFound, s.status)
        assertEquals("""{"error":"no_voice"}""", s.error())
    }

    @Test fun listsConfiguredProvidersAndTheDefault() = withVoices { fake ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.OPENAI, "sk-o", null)
        deps.voiceSettings.save(VoiceKind.ELEVENLABS, "xi", null)
        var v = testJson.decodeFromString<WalkerVoicesResponse>(voices(dev.deviceToken).bodyAsText())
        assertEquals(
            listOf(
                WalkerVoice("openai:alloy", "Alloy", "openai", "OpenAI"), WalkerVoice("openai:coral", "Coral", "openai", "OpenAI"),
                WalkerVoice("elevenlabs:abc123", "Rachel", "elevenlabs", "ElevenLabs"),
            ),
            v.voices,
        )
        assertEquals("no stored default: the first voice", "openai:alloy", v.defaultVoice)
        deps.voiceSettings.setDefaultVoice("elevenlabs:abc123")
        v = testJson.decodeFromString(voices(dev.deviceToken).bodyAsText())
        assertEquals("elevenlabs:abc123", v.defaultVoice)

        // A provider whose list fails is left out; the stored default then falls back to an available voice.
        fake.failVoices[VoiceKind.ELEVENLABS] = "auth"
        deps.voices.invalidate()
        v = testJson.decodeFromString(voices(dev.deviceToken).bodyAsText())
        assertEquals(listOf("openai:alloy", "openai:coral"), v.voices.map { it.id })
        assertEquals("openai:alloy", v.defaultVoice)
    }

    @Test fun voiceListsAreCachedForTenMinutes() = withVoices { fake ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.ELEVENLABS, "xi", null)
        repeat(3) { voices(dev.deviceToken) }
        speak(dev.deviceToken, "hi", "elevenlabs:abc123")
        assertEquals(1, fake.listCalls[VoiceKind.ELEVENLABS])
        clock.advance(VoiceService.CACHE_MS)
        voices(dev.deviceToken)
        assertEquals(2, fake.listCalls[VoiceKind.ELEVENLABS])
        // A new key refetches at once.
        deps.voiceSettings.save(VoiceKind.ELEVENLABS, "xi-2", null)
        voices(dev.deviceToken)
        assertEquals(3, fake.listCalls[VoiceKind.ELEVENLABS])
    }

    @Test fun speakReturnsMp3() = withVoices { fake ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.OPENAI, "sk-o", "tts-1")
        deps.voiceSettings.save(VoiceKind.INWORLD, "iw", null)
        deps.voiceSettings.setDefaultVoice("inworld:Ashley")

        val r = speak(dev.deviceToken, "  Turn left at the corner.  ", "openai:coral")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("audio/mpeg", r.headers[HttpHeaders.ContentType])
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        assertArrayEquals(MP3, r.readRawBytes())
        val (cfg, text, voice) = fake.spoken.last()
        assertEquals(VoiceKind.OPENAI, cfg.kind)
        assertEquals("sk-o", cfg.apiKey)
        assertEquals("tts-1", cfg.model)
        assertEquals("Turn left at the corner.", text)
        assertEquals("coral", voice)

        assertEquals(HttpStatusCode.OK, speak(dev.deviceToken, "Default please").status)
        assertEquals(VoiceKind.INWORLD to "Ashley", fake.spoken.last().let { it.first.kind to it.third })
        assertTrue("never stored", store.accessLog(50).none { it.detail.contains("Default please") })
    }

    @Test fun speakErrors() = withVoices(Limits(maxSpeakChars = 20)) { fake ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.OPENAI, "sk-o", null)
        for (t in listOf("", "   ", "x".repeat(21))) {
            val r = speak(dev.deviceToken, t)
            assertEquals(t, HttpStatusCode.BadRequest, r.status)
            assertEquals("""{"error":"bad_request"}""", r.error())
        }
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/walker/speak", """{"voice":"openai:coral"}""", dev.deviceToken).status)
        for (v in listOf("openai:nope", "elevenlabs:abc123", "nope:coral", "coral", "openai:../x")) {
            val r = speak(dev.deviceToken, "hi", v)
            assertEquals(v, HttpStatusCode.BadRequest, r.status)
            assertEquals("""{"error":"unknown_voice"}""", r.error())
        }
        fake.failSpeak[VoiceKind.OPENAI] = "timeout"
        val r = speak(dev.deviceToken, "hi", "openai:coral")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertEquals("""{"error":"voice_error"}""", r.error())
        assertTrue(fake.spoken.isEmpty())
    }

    @Test fun failingDefaultProviderIsAVoiceError() = withVoices { fake ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.ELEVENLABS, "xi", null)
        fake.failVoices[VoiceKind.ELEVENLABS] = "unavailable"
        assertEquals(HttpStatusCode.BadGateway, speak(dev.deviceToken, "hi").status)
        assertEquals(HttpStatusCode.BadGateway, speak(dev.deviceToken, "hi", "elevenlabs:abc123").status)
    }

    @Test fun speakIsRateLimitedPerDevice() = withVoices(Limits(walkerSpeakPerMinute = 3)) { _ ->
        val a = pairedDevice("A")
        val b = pairedDevice("B")
        deps.voiceSettings.save(VoiceKind.OPENAI, "sk-o", null)
        voices(a.deviceToken)
        repeat(2) { assertEquals(HttpStatusCode.OK, speak(a.deviceToken, "hi").status) }
        val limited = speak(a.deviceToken, "hi")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("""{"error":"rate_limited"}""", limited.error())
        assertEquals(HttpStatusCode.TooManyRequests, voices(a.deviceToken).status)
        assertEquals(HttpStatusCode.OK, speak(b.deviceToken, "hi").status)
        clock.advance(60_001)
        assertEquals(HttpStatusCode.OK, speak(a.deviceToken, "hi").status)
    }

    @Test fun oversizedBodyIs413() = withVoices { _ ->
        val dev = pairedDevice("Glasses")
        deps.voiceSettings.save(VoiceKind.OPENAI, "sk-o", null)
        assertEquals(HttpStatusCode.PayloadTooLarge, speak(dev.deviceToken, "x".repeat(20_000)).status)
    }
}
