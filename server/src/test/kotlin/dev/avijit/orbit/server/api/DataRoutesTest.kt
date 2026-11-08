package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Secrets
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DataRoutesTest {
    private suspend fun ApiEnv.get(path: String, token: String?): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    @Test fun restSamplesRequiresAgentToken() = withApi {
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 64.0, "HISTORY"), SampleDto("HEART_RATE", T0 - 60_000, 70.0, "LIVE")), null)
        val path = "/v1/data/samples?metric=HEART_RATE&from=${T0 - 86_400_000}&to=$T0"

        val none = get(path, null)
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertTrue(none.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Bearer"))
        assertEquals("""{"error":"invalid_token"}""", none.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, get(path, "garbage-garbage-garbage-garbage").status)
        val device = pairedDevice()
        assertEquals("device tokens cannot read the data API", HttpStatusCode.Unauthorized, get(path, device.deviceToken).status)
        assertTrue("unauthenticated requests read nothing", store.accessLog(10).isEmpty())

        val token = agentToken("my-script")
        val ok = get(path, token)
        assertEquals(ok.bodyAsText(), HttpStatusCode.OK, ok.status)
        val body = ok.json()
        assertEquals("HEART_RATE", body["metric"]!!.jsonPrimitive.content)
        assertEquals("raw", body["aggregation"]!!.jsonPrimitive.content)
        val points = body["points"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(T0 - 3_600_000, T0 - 60_000), points.map { it["epochMs"]!!.jsonPrimitive.long })
        assertEquals(70.0, points[1]["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(Instant.ofEpochMilli(T0 - 60_000).atOffset(ZoneOffset.UTC).toString(), points[1]["time"]!!.jsonPrimitive.content)
        val row = store.accessLog(1).single()
        assertEquals("my-script", row.actor)
        assertEquals("get_metric", row.action)
        assertEquals(2, row.rows)

        // ISO times and aggregation work too.
        val avg = get("/v1/data/samples?metric=HEART_RATE&from=2026-05-27&to=2026-05-28&agg=avg", token).json()
        assertEquals(67.0, avg["points"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.double, 0.0)

        // Bad parameters are 400 and still logged.
        for (bad in listOf("metric=GLUCOSE", "metric=HEART_RATE&agg=weekly", "metric=HEART_RATE&from=yesterday-ish", "", "metric=HEART_RATE&from=$T0&to=${T0 - 1}")) {
            val r = get("/v1/data/samples?$bad", token)
            assertEquals(bad, HttpStatusCode.BadRequest, r.status)
        }
        assertTrue(store.accessLog(5).all { it.actor == "my-script" && it.detail.startsWith("ERROR") })

        // Out of scope: 403 and a DENIED row.
        val sleepOnly = agentToken("sleepy", setOf("sleep"))
        val denied = get(path, sleepOnly)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals("""{"error":"scope_denied"}""", denied.bodyAsText())
        val d = store.accessLog(1).single()
        assertEquals("sleepy", d.actor)
        assertTrue(d.detail.startsWith("DENIED"))
    }

    @Test fun restSleepReturnsNightsWithinScope() = withApi {
        val zone = ZoneOffset.UTC
        val night = LocalDate.of(2026, 5, 26)
        val start = night.atTime(23, 0).toInstant(zone).toEpochMilli()
        store.insertSleep(listOf(SleepDto(start, start + 8 * 3_600_000L, listOf(
            SegmentDto("LIGHT", start, 3_600), SegmentDto("DEEP", start + 3_600_000, 3_600), SegmentDto("AWAKE", start + 7_200_000, 600),
        ))), null)
        val token = agentToken("sleep-script", setOf("sleep"))
        val r = get("/v1/data/sleep?from=2026-05-20&to=2026-05-27", token)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        val nights = r.json()["nights"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, nights.size)
        assertEquals("2026-05-26", nights[0]["night"]!!.jsonPrimitive.content)
        assertEquals(120, nights[0]["asleepMin"]!!.jsonPrimitive.int)
        assertEquals(3, nights[0]["sessions"]!!.jsonArray.single().jsonObject["segments"]!!.jsonArray.size)
        val row = store.accessLog(1).single()
        assertEquals("sleep-script", row.actor)
        assertEquals("get_sleep_range", row.action)
        assertEquals(1, row.rows)

        val heartOnly = agentToken("heart-script", setOf("heart"))
        assertEquals(HttpStatusCode.Forbidden, get("/v1/data/sleep", heartOnly).status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/data/sleep?from=2026-05-27&to=2026-05-20", token).status)
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/data/sleep", null).status)
    }

    @Test fun agentTokensAreHashedScopedAndNamed() = withApi {
        val created = deps.agentTokens.create("  Claude\u0000 Code  ", setOf("heart", "sleep"))
        assertEquals("Claude Code", created.row.name)
        assertEquals(setOf("heart", "sleep"), created.row.scopes)
        assertTrue(Secrets.looksLikeToken(created.token))
        assertEquals(Secrets.sha256Hex(created.token), created.row.tokenHash)
        assertNotEquals(created.token, created.row.tokenHash)
        val dbBytes = Files.readAllBytes(store.dbPath) + (Paths.get("${store.dbPath}-wal").let { if (Files.exists(it)) Files.readAllBytes(it) else ByteArray(0) })
        assertFalse("the raw token is never stored", String(dbBytes, Charsets.ISO_8859_1).contains(created.token))

        assertThrows { deps.agentTokens.create("x", setOf("heart_rate")) }
        assertThrows { deps.agentTokens.create("x", emptySet()) }
        assertThrows { deps.agentTokens.create("   ", setOf("heart")) }
        assertThrows("an active token's name is unique (it is the access-log actor)") { deps.agentTokens.create("claude code", setOf("heart")) }

        assertEquals(listOf("Claude Code"), deps.agentTokens.list().map { it.name })
        assertTrue(deps.agentTokens.revoke(created.row.id))
        // Once revoked, the name may be reused.
        deps.agentTokens.create("Claude Code", Scope.ALL_WIRE)
        assertEquals(2, deps.agentTokens.list().size)
        assertEquals(created.row.id, deps.agentTokens.resolveId(created.row.id.take(8)))
    }

    private fun assertThrows(msg: String = "", block: () -> Unit) {
        val thrown = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(msg, thrown)
    }
}
