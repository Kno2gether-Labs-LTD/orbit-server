package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.agent.AgentSettings
import dev.avijit.orbit.server.agent.ModelProvider
import dev.avijit.orbit.server.agent.ModelReply
import dev.avijit.orbit.server.agent.Msg
import dev.avijit.orbit.server.agent.ProviderException
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbit.server.agent.ToolSpec
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.AskResponse
import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbit.server.agent.AnthropicProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRoutesTest {
    private val scripted = mapOf(AgentSettings.SCRIPTED_ENV to "1")

    private suspend fun ApiEnv.ask(token: String?, q: String = "What was my heart rate?", history: List<ChatTurn> = emptyList()) =
        postJson("/v1/agent/ask", testJson.encodeToString(AskRequest(q, history)), bearer = token)

    @Test fun askWithoutModelReturns409() = withApi {
        val dev = pairedDevice()
        val r = ask(dev.deviceToken)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("""{"error":"no_model"}""", r.bodyAsText())
        val row = store.accessLog(10).single()
        assertEquals("agent_ask", row.action)
        assertTrue(row.detail, row.detail.contains("outcome=no_model"))
    }

    @Test fun askRequiresADeviceToken() = withApi(env = scripted) {
        assertEquals(HttpStatusCode.Unauthorized, ask(null).status)
        assertEquals(HttpStatusCode.Unauthorized, ask("not-a-real-token-at-all-xxxxxxxx").status)
    }

    @Test fun askWithScriptedProviderAnswersWithProvenanceAndLogs() = withApi(env = scripted) {
        val dev = pairedDevice("Pixel 9")
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 64.0, "HISTORY"), SampleDto("HEART_RATE", T0 - 7_200_000, 66.0, "HISTORY")), null)
        val r = ask(dev.deviceToken)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        val body = testJson.decodeFromString<AskResponse>(r.bodyAsText())
        assertEquals("scripted", body.model)
        assertTrue(body.answer, body.answer.contains("65 bpm"))
        assertEquals("get_metric", body.provenance.single().tool)
        val log = store.accessLog(10)
        assertEquals(listOf("agent_ask", "get_metric"), log.map { it.action })
        assertTrue(log[0].detail, log[0].detail.contains("outcome=ok"))
        assertTrue(log.all { it.actor == "Pixel 9" })
        assertFalse(log.any { it.detail.contains("64") || it.detail.contains("65") })
    }

    @Test fun askUsesTheDeviceScopes() = withApi(env = scripted) {
        val dev = pairedDevice()
        store.updateDeviceScopes(dev.deviceId, setOf("sleep"))
        val r = ask(dev.deviceToken)
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("don't have access"))
        assertTrue(store.accessLog(10).any { it.action == "get_metric" && it.detail.startsWith("DENIED") })
    }

    @Test fun providerFailureIs502WithoutLeakingTheKey() {
        val failing = ProviderFactory { cfg ->
            object : ModelProvider {
                override val model = cfg.model
                override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply =
                    throw ProviderException("HTTP 401 from provider")
            }
        }
        withApi(providerFactory = failing) {
            deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-SECRET-KEY-42")
            val dev = pairedDevice()
            val r = ask(dev.deviceToken)
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals("""{"error":"model_error"}""", r.bodyAsText())
            val row = store.accessLog(10).single()
            assertEquals("agent_ask", row.action)
            assertTrue(row.detail, row.detail.contains("outcome=model_error"))
        }
    }

    @Test fun aSlowModelHitsTheAskDeadline() {
        val slow = ProviderFactory { cfg ->
            object : ModelProvider {
                override val model = cfg.model
                override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
                    kotlinx.coroutines.delay(60_000) // each step under a per-call timeout, the whole ask over the deadline
                    return ModelReply.Text("too late")
                }
            }
        }
        assertEquals("deadline sits below the phone's 180 s", 150_000L, dev.avijit.orbit.server.Limits().askTimeoutMs)
        withApi(limits = dev.avijit.orbit.server.Limits(askTimeoutMs = 300), providerFactory = slow) {
            deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-x")
            val dev = pairedDevice()
            val started = System.nanoTime()
            val r = ask(dev.deviceToken)
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals("""{"error":"model_error"}""", r.bodyAsText())
            assertTrue("answered at the deadline, not after the slow model", System.nanoTime() - started < 30_000_000_000L)
            val row = store.accessLog(10).single()
            assertTrue(row.detail, row.detail.contains("outcome=timeout"))
        }
    }

    @Test fun providerFailureLogsNeitherTheKeyNorTheProviderBody() {
        val key = "sk-ant-LOGLEAK-KEY-7777"
        val marker = "BODY-MARKER-resting-hr-58"
        val leakyAnthropic = ProviderFactory { cfg ->
            val http = HttpClient(MockEngine {
                respond("""{"type":"error","error":{"message":"bad key ${cfg.apiKey} $marker"}}""", HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            })
            AnthropicProvider(http, cfg.apiKey!!, cfg.model)
        }
        val captured = ByteArrayOutputStream()
        val origErr = System.err
        val origOut = System.out
        val tee = PrintStream(captured, true, "UTF-8")
        System.setErr(tee); System.setOut(tee)
        try {
            withApi(providerFactory = leakyAnthropic) {
                deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", key)
                val dev = pairedDevice()
                val r = ask(dev.deviceToken)
                assertEquals(HttpStatusCode.BadGateway, r.status)
                assertFalse(r.bodyAsText().contains(key))
            }
        } finally {
            System.setErr(origErr); System.setOut(origOut)
        }
        val log = captured.toString("UTF-8")
        assertTrue("the failure was logged (capture works): $log", log.contains("Agent provider anthropic failed"))
        assertTrue(log, log.contains("HTTP 401"))
        assertFalse("API key in logs", log.contains("LOGLEAK"))
        assertFalse("provider body in logs", log.contains(marker))
    }

    @Test fun badQuestionsAreRejectedAndHistoryIsAccepted() = withApi(env = scripted) {
        val dev = pairedDevice()
        assertEquals(HttpStatusCode.BadRequest, ask(dev.deviceToken, q = "   ").status)
        assertEquals(HttpStatusCode.BadRequest, ask(dev.deviceToken, q = "x".repeat(5_000)).status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/agent/ask", "{not json", bearer = dev.deviceToken).status)
        val long = (0 until 200).map { ChatTurn(if (it % 2 == 0) "user" else "assistant", "turn $it") }
        assertEquals(HttpStatusCode.OK, ask(dev.deviceToken, history = long).status)
    }

    @Test fun askIsRateLimitedPerDevice() = withApi(env = scripted) {
        val dev = pairedDevice()
        repeat(deps.limits.asksPerMinute) { assertEquals(HttpStatusCode.OK, ask(dev.deviceToken).status) }
        val r = ask(dev.deviceToken)
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        clock.advance(61_000)
        assertEquals(HttpStatusCode.OK, ask(dev.deviceToken).status)
    }
}
