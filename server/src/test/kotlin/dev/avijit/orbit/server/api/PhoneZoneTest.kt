package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.agent.ModelProvider
import dev.avijit.orbit.server.agent.ModelReply
import dev.avijit.orbit.server.agent.Msg
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbit.server.agent.ToolSpec
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.IngestBatch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The phone sends its IANA zone so "today" and "last night" are the user's local days, not the server's. */
class PhoneZoneTest {
    private val prompts = mutableListOf<String>()
    private val capturing = ProviderFactory { cfg ->
        object : ModelProvider {
            override val model = cfg.model
            override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
                prompts += messages.filterIsInstance<Msg.System>().single().text
                return ModelReply.Text("ok")
            }
        }
    }

    private suspend fun ApiEnv.configured(): String {
        deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-test")
        return pairedDevice().deviceToken
    }

    private suspend fun ApiEnv.ask(token: String, zone: String?) {
        val r = postJson("/v1/agent/ask", testJson.encodeToString(AskRequest("How did I sleep?", zone = zone)), bearer = token)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
    }

    @Test fun askUsesThePhoneZone() = withApi(providerFactory = capturing) {
        val token = configured()
        ask(token, "Asia/Tokyo")
        assertTrue(prompts.last(), prompts.last().contains("(Asia/Tokyo)"))
    }

    @Test fun invalidOrMissingZoneFallsBackToTheServerZone() = withApi(providerFactory = capturing) {
        val token = configured()
        ask(token, "Mars/Olympus_Mons")
        assertTrue(prompts.last(), prompts.last().contains("(Z)"))
        ask(token, "x".repeat(500))
        assertTrue(prompts.last(), prompts.last().contains("(Z)"))
        ask(token, null)
        assertTrue(prompts.last(), prompts.last().contains("(Z)"))
    }

    @Test fun ingestRemembersThePhoneZoneForLaterAsks() = withApi(providerFactory = capturing) {
        val token = configured()
        val r = postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Asia/Kolkata")), bearer = token)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        ask(token, null)
        assertTrue(prompts.last(), prompts.last().contains("(Asia/Kolkata)"))
        // A bad zone in a later batch is ignored, not stored.
        postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Nope/Nowhere")), bearer = token)
        ask(token, null)
        assertTrue(prompts.last(), prompts.last().contains("(Asia/Kolkata)"))
        // The ask's own zone wins over the remembered one.
        ask(token, "America/New_York")
        assertTrue(prompts.last(), prompts.last().contains("(America/New_York)"))
    }

    @Test fun ingestStoresTheZoneOnTheDeviceRow() = withApi(providerFactory = capturing) {
        val paired = pairedDevice()
        postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Asia/Kolkata")), bearer = paired.deviceToken)
        assertEquals("Asia/Kolkata", store.device(paired.deviceId)!!.zone)
        // An invalid zone leaves the remembered one alone.
        postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Nope/Nowhere")), bearer = paired.deviceToken)
        assertEquals("Asia/Kolkata", store.device(paired.deviceId)!!.zone)
    }

    @Test fun dataApiAndMcpUseTheLastIngestedPhoneZone() = withApi(providerFactory = capturing) {
        val agent = agentToken("script")
        val before = client.get("/v1/data/samples?metric=HEART_RATE") { header(HttpHeaders.Authorization, "Bearer $agent") }.json()
        assertEquals("Z", before["timezone"]!!.jsonPrimitive.content)

        val phone = pairedDevice()
        postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Asia/Kolkata")), bearer = phone.deviceToken)
        val data = client.get("/v1/data/samples?metric=HEART_RATE") { header(HttpHeaders.Authorization, "Bearer $agent") }.json()
        assertEquals("Asia/Kolkata", data["timezone"]!!.jsonPrimitive.content)

        val r = mcp(agent, toolCall(1, "get_metric", """{"metric":"HEART_RATE","agg":"avg"}"""))
        val text = r.json()["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("\"timezone\":\"Asia/Kolkata\""))

        // A second phone's newer ingest wins.
        val other = pairedDevice("Tablet")
        clock.advance(60_000)
        postJson("/v1/ingest", testJson.encodeToString(IngestBatch(emptyList(), emptyList(), zone = "Europe/London")), bearer = other.deviceToken)
        assertEquals("Europe/London", deps.userZone().id)
    }
}
