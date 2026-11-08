package dev.avijit.orbithealth.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkerVoiceProtocolTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test fun conversationIsOptionalBothWays() {
        assertNull(Json.decodeFromString<WalkerAskRequest>("""{"text":"hi"}""").conversation)
        assertEquals("c-1_A", Json.decodeFromString<WalkerAskRequest>("""{"text":"hi","conversation":"c-1_A"}""").conversation)
        val reply = WalkerReply(WalkerReply.DONE, answer = "ok", model = "m", conversation = "abc")
        assertEquals(
            """{"status":"done","answer":"ok","turn":null,"calls":[],"say":null,"provenance":[],"model":"m","conversation":"abc"}""",
            json.encodeToString(reply),
        )
        assertEquals(reply, Json.decodeFromString<WalkerReply>(json.encodeToString(reply)))
        // An older server's reply (no conversation) still decodes.
        assertNull(Json.decodeFromString<WalkerReply>("""{"status":"done","answer":"ok","model":"m"}""").conversation)
    }

    @Test fun voiceDtosHaveTheDocumentedShape() {
        val r = WalkerVoicesResponse(listOf(WalkerVoice("openai:coral", "Coral", "openai", "OpenAI")), "openai:coral")
        assertEquals(
            """{"voices":[{"id":"openai:coral","name":"Coral","provider":"openai","providerName":"OpenAI"}],"default":"openai:coral"}""",
            json.encodeToString(r),
        )
        assertEquals("""{"voices":[],"default":null}""", json.encodeToString(WalkerVoicesResponse(emptyList(), null)))
        assertEquals(WalkerSpeakRequest("hello", null), Json.decodeFromString<WalkerSpeakRequest>("""{"text":"hello"}"""))
        assertEquals("elevenlabs:abc", Json.decodeFromString<WalkerSpeakRequest>("""{"text":"x","voice":"elevenlabs:abc"}""").voice)
    }
}
