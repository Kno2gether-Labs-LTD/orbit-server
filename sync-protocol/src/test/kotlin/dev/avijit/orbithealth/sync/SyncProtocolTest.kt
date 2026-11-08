package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.ring.Metric
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SyncProtocolTest {
    private val fp = "a".repeat(32) + "0123456789".repeat(3) + "ab"

    @Test fun pairingUriRoundTrip() {
        val p = PairingUri("https://192.168.1.5:8443/x?y=1&z=2", fp, "tok_-AbC123", "Sam's Mac & Co")
        val s = p.toUri()
        assertTrue(s.startsWith("orbit://pair?u="))
        assertFalse(s.substring("orbit://pair?u=".length).contains("https://"))
        assertEquals(p, PairingUri.parse(s))
    }

    @Test fun parseRejectsWrongScheme() {
        val ok = PairingUri("https://h:8443", fp, "t", "n").toUri()
        assertNotNull(PairingUri.parse(ok))
        assertNull(PairingUri.parse(ok.replace("orbit://", "http://")))
        assertNull(PairingUri.parse(ok.replace("pair?", "other?")))
        assertNull(PairingUri.parse("orbit://pair?fp=$fp&t=t&n=n"))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&fp=$fp&n=n"))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&fp=$fp&t=&n=n"))
        assertNull(PairingUri.parse("orbit://pair?u=http%3A%2F%2Fh&fp=$fp&t=t&n=n"))
        assertNull(PairingUri.parse("garbage"))
    }

    @Test fun parseRejectsBadFingerprint() {
        fun uri(f: String) = "orbit://pair?u=https%3A%2F%2Fh&fp=$f&t=t&n=n"
        assertNotNull(PairingUri.parse(uri(fp)))
        assertNull(PairingUri.parse(uri(fp.uppercase())))
        assertNull(PairingUri.parse(uri(fp.dropLast(1))))
        assertNull(PairingUri.parse(uri(fp + "a")))
        assertNull(PairingUri.parse(uri("g" + fp.drop(1))))
        assertNull(PairingUri.parse(uri(fp.chunked(2).joinToString(":"))))
    }

    @Test fun batchJsonRoundTrip() {
        val b = IngestBatch(
            listOf(SampleDto("HEART_RATE", 1000L, 61.5, "HISTORY")),
            listOf(SleepDto(1L, 2L, listOf(SegmentDto("DEEP", 1L, 600)))),
            DeviceInfoDto(7, "1.2.3", 80),
        )
        val j = Json.encodeToString(b)
        assertEquals(b, Json.decodeFromString<IngestBatch>(j))
        val none = IngestBatch(emptyList(), emptyList(), null)
        assertEquals(none, Json.decodeFromString<IngestBatch>(Json.encodeToString(none)))
        val ask = AskRequest("q")
        assertEquals(ask, Json.decodeFromString<AskRequest>("""{"question":"q"}"""))
    }

    @Test fun ingestResultSkippedCountsDefaultToZero() {
        val old = """{"samplesNew":1,"samplesDup":2,"sleepNew":3,"sleepDup":4}"""
        assertEquals(IngestResult(1, 2, 3, 4, samplesSkipped = 0, sleepSkipped = 0), Json.decodeFromString<IngestResult>(old))
        val withSkips = IngestResult(1, 0, 0, 0, samplesSkipped = 5, sleepSkipped = 1)
        assertEquals(withSkips, Json.decodeFromString<IngestResult>(Json.encodeToString(withSkips)))
        val scopes = ScopesUpdate(setOf("heart", "sleep"))
        assertEquals(scopes, Json.decodeFromString<ScopesUpdate>("""{"scopes":["heart","sleep"]}"""))
        assertEquals(Scope.SLEEP, Scope.fromWire("sleep"))
        assertNull(Scope.fromWire("SLEEP "))
        assertEquals(Scope.entries.map { it.wire }.toSet(), Scope.ALL_WIRE)
    }

    @Test fun everyMetricHasAScope() {
        Metric.entries.forEach { m -> assertNotNull(m.scope()) }
        assertEquals(Scope.HEART, Metric.HRV.scope())
        assertEquals(Scope.ACTIVITY, Metric.STEPS_DAY_TOTAL.scope())
        assertEquals(Scope.SPO2, Metric.SPO2.scope())
        assertEquals("heart", Scope.HEART.wire)
    }

    @Test fun parseNeverThrowsOnMalformedInput() {
        assertNull(PairingUri.parse(""))
        assertNull(PairingUri.parse("orbit://pair?"))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&fp=$fp&t=t&n=%E0%A4"))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&fp=$fp&t=t&n=%zz"))
        assertNull(PairingUri.parse("orbit://pair?u=%&fp=$fp&t=t&n=n"))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&fp=$fp&t=t&n"))
    }

    @Test fun parseValidatesTheServerUrl() {
        fun uri(u: String) = "orbit://pair?u=${java.net.URLEncoder.encode(u, "UTF-8")}&fp=$fp&t=t&n=n"
        assertNotNull(PairingUri.parse(uri("https://nas.local")))
        assertNotNull(PairingUri.parse(uri("https://10.0.2.2:8443")))
        assertNotNull(PairingUri.parse(uri("https://[fd00::1]:8443")))
        assertNull(PairingUri.parse(uri("https://")))
        assertNull(PairingUri.parse(uri("https://:8443")))
        assertNull(PairingUri.parse(uri("https://h:0")))
        assertNull(PairingUri.parse(uri("https://h:70000")))
        assertNull(PairingUri.parse(uri("https://h:port")))
        assertNull(PairingUri.parse(uri("https://h o st:8443")))
        assertNull(PairingUri.parse(uri("https://user:pw@h:8443")))
        assertNull(PairingUri.parse(uri("HTTPS-ish://h")))
    }

    @Test fun parseRejectsDuplicateKeys() {
        val ok = "orbit://pair?u=https%3A%2F%2Fh&fp=$fp&t=t&n=n"
        assertNotNull(PairingUri.parse(ok))
        assertNull(PairingUri.parse("$ok&u=https%3A%2F%2Fevil"))
        assertNull(PairingUri.parse("$ok&fp=$fp"))
        assertNull(PairingUri.parse("$ok&t=t2"))
    }

    @Test fun unicodeServerNameRoundTrips() {
        val p = PairingUri("https://h:8443", fp, "tok", "Sam\u2019s M\u00e4c \uD83C\uDF19 \u0938\u0930\u094D\u0935\u0930")
        assertEquals(p, PairingUri.parse(p.toUri()))
    }

    @Test fun optionalFieldsDefaultAndZoneTravels() {
        val b = testJson.decodeFromString<IngestBatch>("""{"samples":[],"sleep":[]}""")
        assertNull(b.device)
        assertNull(b.zone)
        val s = testJson.decodeFromString<StateResponse>("""{"lastEpochMsByMetric":{}}""")
        assertNull(s.lastSleepStartMs)
        val ask = AskRequest("q", zone = "Europe/London")
        assertEquals(ask, testJson.decodeFromString<AskRequest>(testJson.encodeToString(ask)))
        val withZone = IngestBatch(emptyList(), emptyList(), zone = "Asia/Kolkata")
        assertEquals(withZone, testJson.decodeFromString<IngestBatch>(testJson.encodeToString(withZone)))
    }

    @Test fun pinModeLinksAreUnchangedOnTheWire() {
        val p = PairingUri("https://h:8443", fp, "tok", "n")
        assertEquals(TrustMode.PIN, p.mode)
        assertEquals("orbit://pair?u=https%3A%2F%2Fh%3A8443&fp=$fp&t=tok&n=n", p.toUri())
        // An explicit m=pin is the same link.
        assertEquals(p, PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh%3A8443&fp=$fp&t=tok&n=n&m=pin"))
    }

    @Test fun caModeRoundTripsWithoutAFingerprint() {
        val p = PairingUri("https://orbit.example.com", null, "tok", "Home", TrustMode.CA)
        val s = p.toUri()
        assertTrue(s, s.contains("m=ca"))
        assertFalse(s, s.contains("fp="))
        val back = PairingUri.parse(s)!!
        assertEquals(p, back)
        assertEquals(TrustMode.CA, back.mode)
        assertNull(back.fingerprint)
        // A fingerprint may still travel in ca mode (it must then be well formed).
        val withFp = PairingUri("https://orbit.example.com", fp, "tok", "Home", TrustMode.CA)
        assertEquals(withFp, PairingUri.parse(withFp.toUri()))
        assertNull(PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&m=ca&fp=XYZ&t=t&n=n"))
    }

    @Test fun trustModeIsStrict() {
        val base = "orbit://pair?u=https%3A%2F%2Fh&t=t&n=n"
        assertNotNull(PairingUri.parse("$base&m=ca"))
        assertNull("pin needs fp", PairingUri.parse("$base&m=pin"))
        assertNull("default pin needs fp", PairingUri.parse(base))
        assertNull(PairingUri.parse("$base&m=CA"))
        assertNull(PairingUri.parse("$base&m=none"))
        assertNull(PairingUri.parse("$base&m="))
        assertNull("repeated m", PairingUri.parse("$base&m=ca&m=ca"))
        assertNull("still https only", PairingUri.parse("orbit://pair?u=http%3A%2F%2Fh&t=t&n=n&m=ca"))
        assertNull("still a token", PairingUri.parse("orbit://pair?u=https%3A%2F%2Fh&t=&n=n&m=ca"))
        try {
            PairingUri("https://h", null, "t", "n", TrustMode.PIN)
            fail("pin mode without a fingerprint must not be constructible")
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            PairingUri("http://h", null, "t", "n", TrustMode.CA)
            fail("ca mode needs an https url")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    private val testJson = Json { ignoreUnknownKeys = true }
}
