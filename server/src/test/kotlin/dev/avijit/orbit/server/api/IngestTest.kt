package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.AccessLog
import dev.avijit.orbit.server.Limits
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.IngestResult
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.ScopesUpdate
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import dev.avijit.orbithealth.sync.StateResponse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestTest {
    private val hr = (0 until 5).map { SampleDto("HEART_RATE", T0 - 3_600_000 + it * 60_000L, 60.0 + it, "HISTORY") }
    private val steps = listOf(SampleDto("STEPS", T0 - 1_800_000, 120.0, "HISTORY"), SampleDto("STEPS", T0 - 3_600_000, 300.0, "HISTORY"))
    private val spo2 = SampleDto("SPO2", T0 - 600_000, 97.0, "MEASURED")
    private val night = SleepDto(T0 - 30_000_000, T0 - 3_000_000, listOf(
        SegmentDto("LIGHT", T0 - 30_000_000, 3_600), SegmentDto("DEEP", T0 - 26_400_000, 1_800),
    ))
    private val batch = IngestBatch(hr + steps + spo2, listOf(night), DeviceInfoDto(42, "1.0.7", 81))

    private suspend fun ApiEnv.ingest(b: IngestBatch, token: String): HttpResponse =
        postJson("/v1/ingest", testJson.encodeToString(b), bearer = token)

    private suspend fun ApiEnv.ingestOk(b: IngestBatch, token: String): IngestResult {
        val r = ingest(b, token)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        return testJson.decodeFromString(r.bodyAsText())
    }

    private suspend fun ApiEnv.getAuthed(path: String, token: String) =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun ApiEnv.setScopes(token: String, vararg scopes: String): HttpResponse =
        client.patch("/v1/devices/me/scopes") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ScopesUpdate(scopes.toSet())))
        }

    @Test fun ingestIsIdempotent() = withApi {
        val dev = pairedDevice()
        val first = ingestOk(batch, dev.deviceToken)
        assertEquals(IngestResult(samplesNew = 8, samplesDup = 0, sleepNew = 1, sleepDup = 0), first)
        val countsAfterFirst = store.metricCounts()
        val sleepAfterFirst = store.sleepSessions(0, Long.MAX_VALUE)

        val second = ingestOk(batch, dev.deviceToken)
        assertEquals(IngestResult(samplesNew = 0, samplesDup = 8, sleepNew = 0, sleepDup = 1), second)
        assertEquals(countsAfterFirst, store.metricCounts())
        assertEquals(sleepAfterFirst, store.sleepSessions(0, Long.MAX_VALUE))
        assertEquals(mapOf("HEART_RATE" to 5, "STEPS" to 2, "SPO2" to 1), store.metricCounts())

        val info = store.deviceInfo(dev.deviceId)!!
        assertEquals(42, info.ringId)
        assertEquals("1.0.7", info.firmware)
        assertEquals(81, info.battery)
        // Ingest counts as the device being seen.
        assertEquals(T0, store.device(dev.deviceId)!!.lastSeenMs)
        // Ingest is not an agent read: nothing in the access log.
        assertTrue(store.accessLog(10).isEmpty())
    }

    @Test fun ingestDropsOutOfScopeMetrics() = withApi {
        val dev = pairedDevice()
        assertEquals(HttpStatusCode.OK, setScopes(dev.deviceToken, "activity").status)
        val r = ingestOk(batch, dev.deviceToken)
        // Only the 2 STEPS samples are in scope; heart, spo2, sleep and device info are dropped.
        assertEquals(IngestResult(samplesNew = 2, samplesDup = 0, sleepNew = 0, sleepDup = 0, samplesSkipped = 6, sleepSkipped = 1), r)
        assertEquals(mapOf("STEPS" to 2), store.metricCounts())
        assertTrue(store.sleepSessions(0, Long.MAX_VALUE).isEmpty())
        assertNull("device info needs the profile scope", store.deviceInfo(dev.deviceId))
    }

    @Test fun ingestSkipsUnknownAndInvalidItems() = withApi {
        val dev = pairedDevice()
        val bad = IngestBatch(
            samples = listOf(
                SampleDto("GLUCOSE", T0 - 1_000, 5.0, "HISTORY"),            // unknown metric
                SampleDto("heart_rate", T0 - 1_000, 60.0, "HISTORY"),        // wrong case is unknown
                SampleDto("HEART_RATE", T0 + 2 * 86_400_000L, 60.0, "LIVE"), // far future would poison the watermark
                SampleDto("HEART_RATE", -5, 60.0, "LIVE"),                    // before the epoch
                SampleDto("HEART_RATE", T0 - 1_000, 60.0, "SPOOFED"),         // unknown source
                SampleDto("HEART_RATE", T0 - 2_000, 61.0, "LIVE"),            // the only good one
            ),
            sleep = listOf(
                SleepDto(T0 - 1_000, T0 - 2_000, emptyList()),                 // ends before it starts
                SleepDto(T0 - 100_000_000, T0 - 1_000, emptyList()),           // longer than 24 h
            ),
            device = DeviceInfoDto(1, "x".repeat(500), 250),                   // invalid info is ignored
        )
        val r = ingestOk(bad, dev.deviceToken)
        assertEquals(IngestResult(samplesNew = 1, samplesDup = 0, sleepNew = 0, sleepDup = 0, samplesSkipped = 5, sleepSkipped = 2), r)
        assertNull(store.deviceInfo(dev.deviceId))
    }

    @Test fun invalidSegmentsAreDroppedButTheSessionIsKept() = withApi {
        val dev = pairedDevice()
        val s = night.copy(segments = night.segments + SegmentDto("NAP", T0 - 20_000_000, 60) + SegmentDto("REM", T0 - 19_000_000, -1))
        val r = ingestOk(IngestBatch(emptyList(), listOf(s), null), dev.deviceToken)
        assertEquals(1, r.sleepNew)
        assertEquals(2, store.sleepSessions(0, Long.MAX_VALUE).single().segments.size)
    }

    @Test fun ingestIsRateLimitedPerDevice() = withApi(Limits(ingestsPerMinute = 3)) {
        assertEquals(60, Limits().ingestsPerMinute)
        val a = pairedDevice("A")
        val b = pairedDevice("B")
        val empty = IngestBatch(emptyList(), emptyList(), null)
        repeat(3) { assertEquals(HttpStatusCode.OK, ingest(empty, a.deviceToken).status) }
        val limited = ingest(empty, a.deviceToken)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("""{"error":"rate_limited"}""", limited.bodyAsText())
        assertEquals("another device has its own budget", HttpStatusCode.OK, ingest(empty, b.deviceToken).status)
        clock.advance(61_000)
        assertEquals(HttpStatusCode.OK, ingest(empty, a.deviceToken).status)
    }

    @Test fun oversizeBatchesAreRejectedWith413() = withApi(Limits(maxSamples = 100)) {
        val dev = pairedDevice()
        val many = IngestBatch((0..100).map { SampleDto("HEART_RATE", T0 - 1_000_000 + it, 60.0, "LIVE") }, emptyList(), null)
        assertEquals(HttpStatusCode.PayloadTooLarge, ingest(many, dev.deviceToken).status)
        assertTrue(store.metricCounts().isEmpty())
    }

    @Test fun bodiesOverTwoMegabytesAreRejectedWith413() = withApi {
        val dev = pairedDevice()
        val padding = " ".repeat(2 * 1024 * 1024)
        val r = postJson("/v1/ingest", """{"samples":[],"sleep":[],"device":null}$padding""", bearer = dev.deviceToken)
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test fun chunkedBodiesWithoutContentLengthAreCappedWith413() = withApi(Limits(maxIngestBytes = 4 * 1024)) {
        val dev = pairedDevice()
        val r = client.post("/v1/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${dev.deviceToken}")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength: Long? = null // chunked transfer: no Content-Length to pre-check
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8("""{"samples":[],"sleep":[],"device":null}""")
                    repeat(16) { channel.writeStringUtf8(" ".repeat(1024)); channel.flush() }
                }
            })
        }
        assertNull("no Content-Length was sent", r.request.headers[HttpHeaders.ContentLength])
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test fun agentTokensAreRejectedOnDeviceRoutes() = withApi {
        val agent = deps.agentTokens.create("claude-code", dev.avijit.orbithealth.sync.Scope.ALL_WIRE).token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state") { header(HttpHeaders.Authorization, "Bearer $agent") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/access-log") { header(HttpHeaders.Authorization, "Bearer $agent") }.status)
        assertEquals(HttpStatusCode.Unauthorized, postJson("/v1/ingest", """{"samples":[],"sleep":[],"device":null}""", bearer = agent).status)
        assertEquals(HttpStatusCode.Unauthorized, postJson("/v1/agent/ask", """{"question":"hi"}""", bearer = agent).status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/devices/me") { header(HttpHeaders.Authorization, "Bearer $agent") }.status)
    }

    @Test fun malformedIngestIs400() = withApi {
        val dev = pairedDevice()
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/ingest", "[]", bearer = dev.deviceToken).status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/ingest", """{"samples":[{"metric":"HEART_RATE"}],"sleep":[],"device":null}""", bearer = dev.deviceToken).status)
    }

    @Test fun ingestRequiresADeviceToken() = withApi {
        assertEquals(HttpStatusCode.Unauthorized, postJson("/v1/ingest", testJson.encodeToString(batch)).status)
        assertEquals(HttpStatusCode.Unauthorized, postJson("/v1/ingest", testJson.encodeToString(batch), bearer = "x".repeat(43)).status)
        assertTrue(store.metricCounts().isEmpty())
    }

    @Test fun revokedDeviceGets401() = withApi {
        val dev = pairedDevice()
        val other = pairedDevice("Tablet")
        assertEquals(HttpStatusCode.OK, getAuthed("/v1/state", dev.deviceToken).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/devices/me") { header(HttpHeaders.Authorization, "Bearer ${dev.deviceToken}") }.status)
        assertTrue(store.device(dev.deviceId)!!.revoked)
        assertEquals(HttpStatusCode.Unauthorized, getAuthed("/v1/state", dev.deviceToken).status)
        assertEquals(HttpStatusCode.Unauthorized, ingest(batch, dev.deviceToken).status)
        assertEquals(HttpStatusCode.Unauthorized, getAuthed("/v1/access-log", dev.deviceToken).status)
        assertEquals(HttpStatusCode.Unauthorized, setScopes(dev.deviceToken, "heart").status)
        // Revocation is per device.
        assertEquals(HttpStatusCode.OK, getAuthed("/v1/state", other.deviceToken).status)
        // Revoked out of band (dashboard) works the same way.
        store.revokeDevice(other.deviceId)
        assertEquals(HttpStatusCode.Unauthorized, getAuthed("/v1/state", other.deviceToken).status)
    }

    @Test fun stateReportsWatermarks() = withApi {
        val dev = pairedDevice()
        val empty = testJson.decodeFromString<StateResponse>(getAuthed("/v1/state", dev.deviceToken).bodyAsText())
        assertEquals(StateResponse(emptyMap(), null), empty)

        ingestOk(batch, dev.deviceToken)
        val r = getAuthed("/v1/state", dev.deviceToken)
        assertEquals(HttpStatusCode.OK, r.status)
        val state = testJson.decodeFromString<StateResponse>(r.bodyAsText())
        assertEquals(mapOf("HEART_RATE" to hr.maxOf { it.epochMs }, "STEPS" to steps.maxOf { it.epochMs }, "SPO2" to spo2.epochMs),
            state.lastEpochMsByMetric)
        assertEquals(night.startMs, state.lastSleepStartMs)

        // A device only sees watermarks for its own scopes.
        setScopes(dev.deviceToken, "activity")
        val narrowed = testJson.decodeFromString<StateResponse>(getAuthed("/v1/state", dev.deviceToken).bodyAsText())
        assertEquals(StateResponse(mapOf("STEPS" to steps.maxOf { it.epochMs }), null), narrowed)
    }

    @Test fun scopesCanBeChangedAndAreValidated() = withApi {
        val dev = pairedDevice()
        val r = setScopes(dev.deviceToken, "heart", "sleep")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ScopesUpdate(setOf("heart", "sleep")), testJson.decodeFromString<ScopesUpdate>(r.bodyAsText()))
        assertEquals(setOf("heart", "sleep"), store.device(dev.deviceId)!!.scopes)

        assertEquals(HttpStatusCode.BadRequest, setScopes(dev.deviceToken, "heart", "everything").status)
        assertEquals(HttpStatusCode.BadRequest, setScopes(dev.deviceToken, "heart,sleep").status)
        assertEquals(setOf("heart", "sleep"), store.device(dev.deviceId)!!.scopes)

        // Empty is allowed: it pauses sync without unpairing.
        assertEquals(HttpStatusCode.OK, setScopes(dev.deviceToken).status)
        assertEquals(IngestResult(0, 0, 0, 0, samplesSkipped = 8, sleepSkipped = 1), ingestOk(batch, dev.deviceToken))
    }

    @Test fun accessLogIsNewestFirstAndLimited() = withApi {
        val dev = pairedDevice()
        val log = AccessLog(store, clock)
        repeat(600) { i ->
            clock.nowMs = T0 + i * 1_000L
            log.record(actor = "claude-code", action = "get_metric", detail = "HEART_RATE day $i", rows = i)
        }
        val r = getAuthed("/v1/access-log?limit=3", dev.deviceToken)
        assertEquals(HttpStatusCode.OK, r.status)
        val entries = testJson.decodeFromString<List<AccessLogEntry>>(r.bodyAsText())
        assertEquals(listOf(599, 598, 597), entries.map { it.rows })
        assertEquals("claude-code", entries.first().actor)

        val dflt = testJson.decodeFromString<List<AccessLogEntry>>(getAuthed("/v1/access-log", dev.deviceToken).bodyAsText())
        assertEquals(100, dflt.size)
        val max = testJson.decodeFromString<List<AccessLogEntry>>(getAuthed("/v1/access-log?limit=500", dev.deviceToken).bodyAsText())
        assertEquals(500, max.size)
        assertEquals(HttpStatusCode.BadRequest, getAuthed("/v1/access-log?limit=501", dev.deviceToken).status)
        assertEquals(HttpStatusCode.BadRequest, getAuthed("/v1/access-log?limit=0", dev.deviceToken).status)
        assertEquals(HttpStatusCode.BadRequest, getAuthed("/v1/access-log?limit=abc", dev.deviceToken).status)
    }

    @Test fun accessLogSanitisesDetail() = withApi {
        val log = AccessLog(store, clock)
        log.record("agent\nname", "get_metric", "line1\nline2\u0000" + "y".repeat(1_000), 1)
        val e = store.accessLog(1).single()
        assertEquals("agent name", e.actor)
        assertTrue(e.detail.startsWith("line1 line2"))
        assertTrue(e.detail.length <= AccessLog.MAX_DETAIL)
    }
}
