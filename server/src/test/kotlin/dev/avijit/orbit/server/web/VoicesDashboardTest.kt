package dev.avijit.orbit.server.web

import dev.avijit.orbit.server.agent.ConversationStore
import dev.avijit.orbit.server.agent.voice.MP3
import dev.avijit.orbit.server.agent.voice.VoiceKind
import dev.avijit.orbit.server.agent.voice.VoiceSettings
import dev.avijit.orbit.server.api.ApiEnv
import dev.avijit.orbit.server.api.FakeVoices
import dev.avijit.orbit.server.api.withApi
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesDashboardTest {
    private fun withVoicesDashboard(block: suspend ApiEnv.(FakeVoices, Browser) -> Unit) {
        val fake = FakeVoices()
        withApi(voiceProviderFactory = fake) {
            setAdminPassword()
            val b = Browser(this)
            block(fake, b)
        }
    }

    @Test fun voicesPageNeedsLogin() = withVoicesDashboard { _, b ->
        assertEquals("/login", b.get("/voices").headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Forbidden, b.post("/voices/openai", mapOf("apiKey" to "sk-x"), csrf = "forged").status)
        assertEquals(HttpStatusCode.SeeOther, b.get("/voices/test.mp3").status)
        assertFalse(deps.voiceSettings.hasKey(VoiceKind.OPENAI))
    }

    @Test fun saveKeysDefaultAndTest() = withVoicesDashboard { fake, b ->
        b.login()
        val page = b.get("/voices")
        assertEquals(HttpStatusCode.OK, page.status)
        for (name in listOf("OpenAI", "ElevenLabs", "Inworld", "AssemblyAI")) assertTrue(name, page.bodyAsText().contains(name))

        assertEquals(HttpStatusCode.SeeOther, b.post("/voices/openai", mapOf("apiKey" to "sk-dash-secret", "model" to "")).status)
        assertEquals("sk-dash-secret", deps.voiceSettings.load(VoiceKind.OPENAI)!!.apiKey)
        val html = b.get("/voices").bodyAsText()
        assertFalse("the key is never shown", html.contains("sk-dash-secret"))
        assertTrue(html.contains("saved key"))
        assertTrue("default select lists the voices", html.contains("value=\"openai:coral\""))

        // A blank key keeps it; a model can be set.
        b.post("/voices/openai", mapOf("apiKey" to "", "model" to "tts-1"))
        assertEquals("sk-dash-secret", deps.voiceSettings.load(VoiceKind.OPENAI)!!.apiKey)
        assertEquals("tts-1", deps.voiceSettings.load(VoiceKind.OPENAI)!!.model)

        val bad = b.post("/voices/inworld", mapOf("apiKey" to "iw", "baseUrl" to "http://plain.example.com"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertFalse(deps.voiceSettings.hasKey(VoiceKind.INWORLD))
        assertEquals(HttpStatusCode.NotFound, b.post("/voices/nope", mapOf("apiKey" to "x")).status)

        assertEquals(HttpStatusCode.SeeOther, b.post("/voices/default", mapOf("voice" to "openai:coral")).status)
        assertEquals("openai:coral", deps.voiceSettings.defaultVoice())
        b.get("/voices")
        assertEquals(HttpStatusCode.BadRequest, b.post("/voices/default", mapOf("voice" to "openai:nope")).status)
        assertEquals("openai:coral", deps.voiceSettings.defaultVoice())

        val test = b.post("/voices/test", emptyMap())
        assertEquals(HttpStatusCode.OK, test.status)
        assertTrue(test.bodyAsText().contains("/voices/test.mp3"))
        assertEquals("coral", fake.spoken.last().third)
        val mp3 = b.get("/voices/test.mp3")
        assertEquals("audio/mpeg", mp3.headers[HttpHeaders.ContentType])
        assertArrayEquals(MP3, mp3.readRawBytes())

        b.post("/voices/openai", mapOf("removeKey" to "1"))
        assertFalse(deps.voiceSettings.hasKey(VoiceKind.OPENAI))
        assertNull(store.getSetting(VoiceSettings.keyName(VoiceKind.OPENAI)))
    }

    @Test fun conversationsCardDeletesHistory() = withVoicesDashboard { _, b ->
        val dev = pairedDevice("Glasses")
        deps.conversations.append(dev.deviceId, "c1", listOf(ConversationStore.USER to "hi", ConversationStore.ASSISTANT to "hello"), 1)
        b.login()
        val page = b.get("/agent").bodyAsText()
        assertTrue(page, page.contains("Conversations"))
        assertTrue(page.contains("2 stored messages"))
        assertEquals(HttpStatusCode.Forbidden, b.post("/agent/conversations/delete", emptyMap(), csrf = "forged").status)
        assertEquals(2, deps.conversations.count())
        val r = b.post("/agent/conversations/delete", emptyMap())
        assertEquals(HttpStatusCode.SeeOther, r.status)
        assertEquals("/agent?cleared=1", r.headers[HttpHeaders.Location])
        assertEquals(0, deps.conversations.count())
        assertTrue(b.get("/agent?cleared=1").bodyAsText().contains("Conversation history deleted."))
    }

    @Test fun revokingFromTheDashboardDeletesTheDevicesHistory() = withVoicesDashboard { _, b ->
        val dev = pairedDevice("Glasses")
        deps.conversations.append(dev.deviceId, "c1", listOf(ConversationStore.USER to "hi"), 1)
        b.login()
        b.get("/devices")
        assertEquals(HttpStatusCode.SeeOther, b.post("/devices/${dev.deviceId}/revoke", emptyMap()).status)
        assertEquals(0, deps.conversations.count())
    }
}
