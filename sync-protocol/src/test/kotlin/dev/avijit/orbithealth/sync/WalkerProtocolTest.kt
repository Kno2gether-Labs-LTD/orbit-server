package dev.avijit.orbithealth.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WalkerProtocolTest {
    private val fp = "ab".repeat(32)

    @Test fun defaultOutputIsUnchanged() {
        val pin = PairingUri("https://192.168.1.5:8443", fp, "tok", "Box")
        assertEquals("orbit://pair?u=https%3A%2F%2F192.168.1.5%3A8443&fp=$fp&t=tok&n=Box", pin.toUri())
        assertEquals(pin.toUri(), pin.toUri(PairingUri.ORBIT_PREFIX))
        val ca = PairingUri("https://orbit.example.com", null, "tok", "Box", TrustMode.CA)
        assertEquals("orbit://pair?u=https%3A%2F%2Forbit.example.com&t=tok&n=Box&m=ca", ca.toUri())
    }

    @Test fun walkerPrefixRoundTrips() {
        for (p in listOf(
            PairingUri("https://192.168.1.5:8443/x?y=1", fp, "tok_-A", "Sam's Mac & Co"),
            PairingUri("https://orbit.example.com", null, "tok", "Box", TrustMode.CA),
        )) {
            val w = p.toUri(PairingUri.WALKER_PREFIX)
            assertTrue(w, w.startsWith("orbitwalker://pair?u="))
            assertEquals(p.toUri().removePrefix("orbit://pair"), w.removePrefix("orbitwalker://pair"))
            assertEquals(p, PairingUri.parse(w))
            assertEquals(p, PairingUri.parse(p.toUri()))
        }
    }

    @Test fun otherPrefixesAreStillRejected() {
        val w = PairingUri("https://h:8443", fp, "t", "n").toUri(PairingUri.WALKER_PREFIX)
        assertNull(PairingUri.parse(w.replace("orbitwalker://", "orbitwalk://")))
        assertNull(PairingUri.parse(w.replace("orbitwalker://pair?", "orbitwalker://pairx?")))
        assertNull(PairingUri.parse("orbitwalker://pair"))
        try {
            PairingUri("https://h:8443", fp, "t", "n").toUri("evil://pair")
            fail("unknown prefix accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun walkerDtosHaveTheDocumentedShape() {
        val req = Json.decodeFromString<WalkerAskRequest>("""{"text":"open maps"}""")
        assertEquals(WalkerAskRequest("open maps"), req)
        assertTrue(req.context.isEmpty() && req.tools.isEmpty() && req.history.isEmpty() && req.zone == null)
        val cont = Json.decodeFromString<WalkerContinueRequest>("""{"turn":"t","results":[{"id":"c1","content":"ok"}]}""")
        assertEquals(WalkerToolResult("c1", "ok", isError = false), cont.results.single())
        val reply = WalkerReply(
            WalkerReply.TOOLS, turn = "t", calls = listOf(WalkerCall("c1", "open_app", buildJsonObject { put("app", "Maps") })),
            say = "Opening Maps.", model = "m",
        )
        val json = Json { encodeDefaults = true; explicitNulls = true }
        assertEquals(
            """{"status":"tools","answer":null,"turn":"t","calls":[{"id":"c1","name":"open_app","args":{"app":"Maps"}}],"say":"Opening Maps.","provenance":[],"model":"m"}""",
            json.encodeToString(reply),
        )
        assertEquals(reply, Json.decodeFromString<WalkerReply>(json.encodeToString(reply)))
        assertEquals(JsonPrimitive("Maps"), reply.calls.single().args["app"])
    }
}
