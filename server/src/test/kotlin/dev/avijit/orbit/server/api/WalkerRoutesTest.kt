package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.agent.Agent
import dev.avijit.orbit.server.agent.ModelProvider
import dev.avijit.orbit.server.agent.ModelReply
import dev.avijit.orbit.server.agent.Msg
import dev.avijit.orbit.server.agent.ProviderException
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbit.server.agent.ToolCall
import dev.avijit.orbit.server.agent.ToolSpec
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.WalkerAskRequest
import dev.avijit.orbithealth.sync.WalkerContinueRequest
import dev.avijit.orbithealth.sync.WalkerReply
import dev.avijit.orbithealth.sync.WalkerToolResult
import dev.avijit.orbithealth.sync.WalkerToolSpec
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model that plays back [replies] in order (repeating the last) and records what it was sent. */
private class QueueProvider(vararg replies: suspend (List<Msg>) -> ModelReply) : ModelProvider {
    override val model = "queue-model"
    private val queue = ArrayDeque(replies.toList())
    val seen = mutableListOf<Pair<List<Msg>, List<ToolSpec>>>()

    override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        seen += messages.toList() to tools
        val next = if (queue.size > 1) queue.removeFirst() else queue.first()
        return next(messages)
    }
}

private fun text(s: String): suspend (List<Msg>) -> ModelReply = { ModelReply.Text(s) }
private fun calls(vararg c: ToolCall, say: String? = null): suspend (List<Msg>) -> ModelReply = { ModelReply.ToolCalls(c.toList(), say) }
private fun call(id: String, name: String, args: JsonObject = JsonObject(emptyMap())) = ToolCall(id, name, args)

private val EMPTY_SCHEMA = buildJsonObject { put("type", "object"); putJsonObject("properties") {} }
private val OPEN_APP = WalkerToolSpec("open_app", "Open an app on the glasses by name.", buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { putJsonObject("app") { put("type", "string") } }
    putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("app")) }
})
private val CLOSE_SCREEN = WalkerToolSpec("close_screen", "Close the focused screen.", EMPTY_SCHEMA)

class WalkerRoutesTest {

    private fun withWalker(provider: ModelProvider, limits: Limits = Limits(), block: suspend ApiEnv.() -> Unit) =
        withApi(limits = limits, providerFactory = ProviderFactory { provider }) {
            deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-test")
            block()
        }

    private suspend fun ApiEnv.ask(
        token: String?, text: String = "open maps", tools: List<WalkerToolSpec> = listOf(OPEN_APP, CLOSE_SCREEN),
        context: JsonObject = buildJsonObject { put("focused", "home"); putJsonArray("apps") { add(kotlinx.serialization.json.JsonPrimitive("Maps")) } },
    ): HttpResponse = postJson("/v1/walker/ask", testJson.encodeToString(WalkerAskRequest(text, context = context, tools = tools, zone = "Europe/London")), bearer = token)

    private suspend fun ApiEnv.cont(token: String?, turn: String, vararg results: WalkerToolResult): HttpResponse =
        postJson("/v1/walker/continue", testJson.encodeToString(WalkerContinueRequest(turn, results.toList())), bearer = token)

    private suspend fun HttpResponse.reply(): WalkerReply {
        assertEquals(bodyAsText(), HttpStatusCode.OK, status)
        return testJson.decodeFromString(bodyAsText())
    }

    private fun ApiEnv.walkerLog() = store.accessLog(50).filter { it.action == "walker_ask" }

    @Test fun textReplyIsDoneInOne() {
        val p = QueueProvider(text("  Maps is open.  "))
        withWalker(p) {
            val dev = pairedDevice("Glasses")
            val r = ask(dev.deviceToken).reply()
            assertEquals(WalkerReply.DONE, r.status)
            assertEquals("Maps is open.", r.answer)
            assertNull(r.turn)
            assertTrue(r.calls.isEmpty())
            assertEquals("queue-model", r.model)

            val (messages, tools) = p.seen.single()
            val system = (messages.first() as Msg.System).text
            assertTrue(system, system.contains("OrbitWalker"))
            assertTrue(system, system.contains("Glasses right now:"))
            assertTrue(system, system.contains("\"focused\": \"home\""))
            assertTrue(system, system.contains("(Europe/London)"))
            assertEquals("open maps", (messages.last() as Msg.User).text)
            val names = tools.map { it.name }
            assertTrue(names.containsAll(listOf("get_metric", "get_sleep", "list_metrics", "open_app", "close_screen")))

            val log = walkerLog().single()
            assertEquals("Glasses", log.actor)
            assertEquals("outcome=ok model=queue-model tool_calls=0 client_calls=0", log.detail)
        }
    }

    @Test fun healthToolThenText() {
        val p = QueueProvider(calls(call("h1", "get_metric", buildJsonObject { put("metric", "HEART_RATE"); put("agg", "avg") })), text("About 65."))
        withWalker(p) {
            val dev = pairedDevice("Glasses")
            store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 64.0, "HISTORY"), SampleDto("HEART_RATE", T0 - 7_200_000, 66.0, "HISTORY")), null)
            val r = ask(dev.deviceToken, text = "what's my heart rate").reply()
            assertEquals(WalkerReply.DONE, r.status)
            assertEquals("get_metric", r.provenance.single().tool)
            val result = p.seen[1].first.last() as Msg.ToolResult
            assertEquals("h1", result.callId)
            assertFalse(result.isError)
            assertTrue(result.content, result.content.contains("65"))
            val log = store.accessLog(10)
            assertEquals(listOf("walker_ask", "get_metric"), log.map { it.action })
            assertTrue(log.all { it.actor == "Glasses" })
            assertTrue(log[0].detail, log[0].detail.endsWith("tool_calls=1 client_calls=0"))
            assertFalse("no words or values in the log", log.any { it.detail.contains("heart rate") || it.detail.contains("65") })
        }
    }

    @Test fun clientToolPausesAndContinues() {
        val p = QueueProvider(calls(call("c1", "open_app", buildJsonObject { put("app", "Maps") }), say = "Opening Maps."), text("Maps is open."))
        withWalker(p) {
            val dev = pairedDevice("Glasses")
            val r = ask(dev.deviceToken).reply()
            assertEquals(WalkerReply.TOOLS, r.status)
            assertNull(r.answer)
            assertEquals("Opening Maps.", r.say)
            val turn = r.turn!!
            assertTrue(turn, Regex("[A-Za-z0-9_-]{22}").matches(turn))
            assertEquals("c1", r.calls.single().id)
            assertEquals("open_app", r.calls.single().name)
            assertEquals("Maps", (r.calls.single().args["app"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertTrue("a paused turn is not logged yet", walkerLog().isEmpty())

            val done = cont(dev.deviceToken, turn, WalkerToolResult("c1", """{"opened":"Maps"}""")).reply()
            assertEquals(WalkerReply.DONE, done.status)
            assertEquals("Maps is open.", done.answer)
            val sent = p.seen[1].first
            val res = sent.last() as Msg.ToolResult
            assertEquals("c1", res.callId); assertEquals("open_app", res.name); assertEquals("""{"opened":"Maps"}""", res.content)
            assertEquals(listOf("c1"), (sent[sent.size - 2] as Msg.Assistant).toolCalls.map { it.id })
            assertEquals("open_app and close_screen are offered again", 2, p.seen[1].second.count { it.name in setOf("open_app", "close_screen") })
            assertEquals("outcome=ok model=queue-model tool_calls=0 client_calls=1", walkerLog().single().detail)

            // Single use.
            val again = cont(dev.deviceToken, turn, WalkerToolResult("c1", "x"))
            assertEquals(HttpStatusCode.NotFound, again.status)
            assertEquals("""{"error":"unknown_turn"}""", again.bodyAsText())
        }
    }

    @Test fun mixedServerAndClientCallsKeepCallOrder() {
        val p = QueueProvider(
            calls(call("c1", "open_app", buildJsonObject { put("app", "Maps") }), call("h1", "list_metrics"), call("c2", "close_screen")),
            text("Done."),
        )
        withWalker(p) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken).reply()
            assertEquals(listOf("c1", "c2"), r.calls.map { it.id })
            assertEquals("list_metrics", r.provenance.single().tool)
            // Results in any order in the request; the model gets them in call order.
            cont(dev.deviceToken, r.turn!!, WalkerToolResult("c2", "closed"), WalkerToolResult("c1", "boom", isError = true)).reply()
            val results = p.seen[1].first.filterIsInstance<Msg.ToolResult>()
            assertEquals(listOf("c1", "h1", "c2"), results.map { it.callId })
            assertTrue(results[0].isError)
            assertFalse(results[2].isError)
            assertEquals("outcome=ok model=queue-model tool_calls=1 client_calls=2", walkerLog().single().detail)
        }
    }

    @Test fun callsPastThePerStepCapGetTheTooManyCallsResult() {
        val many = (1..10).map { call("c$it", "close_screen") }.toTypedArray()
        val p = QueueProvider(calls(*many), text("ok"))
        withWalker(p) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken).reply()
            assertEquals((1..Agent.MAX_CALLS_PER_STEP).map { "c$it" }, r.calls.map { it.id })
            cont(dev.deviceToken, r.turn!!, *r.calls.map { WalkerToolResult(it.id, "ok") }.toTypedArray()).reply()
            val results = p.seen[1].first.filterIsInstance<Msg.ToolResult>()
            assertEquals(many.map { it.id }, results.map { it.callId })
            assertEquals(Agent.TOO_MANY_CALLS, results[9].content)
            assertTrue(results[8].isError && results[9].isError)
        }
    }

    @Test fun theTurnHasAModelCallBudget() {
        val p = QueueProvider(calls(call("h", "list_metrics")))
        withWalker(p) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken).reply()
            assertEquals(WalkerReply.DONE, r.status)
            assertEquals(Agent.STEP_LIMIT_ANSWER, r.answer)
            assertEquals(8, p.seen.size)
        }
    }

    @Test fun theBudgetSpansPauses() {
        val p = QueueProvider(calls(call("c", "close_screen")))
        withWalker(p) {
            val dev = pairedDevice()
            var r = ask(dev.deviceToken).reply()
            var rounds = 1
            while (r.status == WalkerReply.TOOLS) {
                r = cont(dev.deviceToken, r.turn!!, WalkerToolResult(r.calls.single().id, "ok")).reply()
                rounds++
            }
            assertEquals(Agent.STEP_LIMIT_ANSWER, r.answer)
            assertEquals(8, p.seen.size)
            assertEquals(9, rounds) // 8 pauses, and the continue that found the budget spent
            assertEquals("outcome=ok model=queue-model tool_calls=0 client_calls=8", walkerLog().single().detail)
        }
    }

    @Test fun anotherDeviceCannotContinue() {
        val p = QueueProvider(calls(call("c1", "close_screen")), text("Closed."))
        withWalker(p) {
            val glasses = pairedDevice("Glasses")
            val other = pairedDevice("Other")
            val r = ask(glasses.deviceToken).reply()
            val stolen = cont(other.deviceToken, r.turn!!, WalkerToolResult("c1", "ok"))
            assertEquals(HttpStatusCode.NotFound, stolen.status)
            assertEquals("""{"error":"unknown_turn"}""", stolen.bodyAsText())
            assertEquals("the owner can still continue", WalkerReply.DONE, cont(glasses.deviceToken, r.turn!!, WalkerToolResult("c1", "ok")).reply().status)
            assertEquals(HttpStatusCode.Unauthorized, cont(null, "x", WalkerToolResult("c1", "ok")).status)
            assertEquals(HttpStatusCode.Unauthorized, ask(null).status)
        }
    }

    @Test fun resultsMustMatchThePendingCalls() {
        val p = QueueProvider(calls(call("c1", "open_app"), call("c2", "close_screen")), text("ok"))
        withWalker(p) {
            val dev = pairedDevice()
            val turn = ask(dev.deviceToken).reply().turn!!
            for (bad in listOf(
                arrayOf(WalkerToolResult("c1", "ok")),
                arrayOf(WalkerToolResult("c1", "ok"), WalkerToolResult("c3", "ok")),
                arrayOf(WalkerToolResult("c1", "ok"), WalkerToolResult("c2", "ok"), WalkerToolResult("c3", "ok")),
                arrayOf(WalkerToolResult("c1", "ok"), WalkerToolResult("c1", "ok")),
                arrayOf(),
            )) {
                val r = cont(dev.deviceToken, turn, *bad)
                assertEquals(HttpStatusCode.BadRequest, r.status)
                assertEquals("""{"error":"bad_results"}""", r.bodyAsText())
            }
            // A big result is cut, not refused, and the turn survived the bad attempts.
            cont(dev.deviceToken, turn, WalkerToolResult("c2", "y".repeat(40_000)), WalkerToolResult("c1", "ok")).reply()
            val results = p.seen[1].first.filterIsInstance<Msg.ToolResult>()
            assertEquals(MAX_RESULT_CHARS, results[1].content.length)
            assertEquals(HttpStatusCode.NotFound, cont(dev.deviceToken, "nope", WalkerToolResult("c1", "ok")).status)
        }
    }

    @Test fun anExpiredTurnIs410() {
        val p = QueueProvider(calls(call("c1", "close_screen")), text("ok"))
        withWalker(p) {
            val dev = pairedDevice()
            val turn = ask(dev.deviceToken).reply().turn!!
            clock.advance(120_001)
            val r = cont(dev.deviceToken, turn, WalkerToolResult("c1", "ok"))
            assertEquals(HttpStatusCode.Gone, r.status)
            assertEquals("""{"error":"turn_expired"}""", r.bodyAsText())
            assertEquals("outcome=expired model=- tool_calls=0 client_calls=1", walkerLog().single().detail)
            assertEquals(HttpStatusCode.NotFound, cont(dev.deviceToken, turn, WalkerToolResult("c1", "ok")).status)
            assertEquals(1, p.seen.size)
        }
    }

    @Test fun badClientToolsAre400() {
        val p = QueueProvider(text("ok"))
        withWalker(p) {
            val dev = pairedDevice()
            val cases = listOf(
                listOf(WalkerToolSpec("get_metric", "collides with a health tool", EMPTY_SCHEMA)),
                listOf(WalkerToolSpec("Open_app", "upper case", EMPTY_SCHEMA)),
                listOf(WalkerToolSpec("1app", "starts with a digit", EMPTY_SCHEMA)),
                listOf(WalkerToolSpec("a".repeat(49), "too long", EMPTY_SCHEMA)),
                listOf(WalkerToolSpec("open-app", "dash", EMPTY_SCHEMA)),
                listOf(OPEN_APP, OPEN_APP),
                listOf(WalkerToolSpec("x", "d".repeat(1_001), EMPTY_SCHEMA)),
                (0..40).map { WalkerToolSpec("t$it", "tool", EMPTY_SCHEMA) },
                (0..30).map { WalkerToolSpec("t$it", "d".repeat(1_000), EMPTY_SCHEMA) }, // over 32 KB of JSON
            )
            for (tools in cases) {
                val r = ask(dev.deviceToken, tools = tools)
                assertEquals(tools.first().name, HttpStatusCode.BadRequest, r.status)
                assertEquals("""{"error":"bad_tools"}""", r.bodyAsText())
            }
            assertEquals("limits are inclusive", WalkerReply.DONE, ask(dev.deviceToken, tools = listOf(
                WalkerToolSpec("a" + "b".repeat(47), "d".repeat(1_000), EMPTY_SCHEMA),
            ) + (1 until 40).map { WalkerToolSpec("t$it", "tool", EMPTY_SCHEMA) }).reply().status)
            assertEquals(WalkerReply.DONE, ask(dev.deviceToken, tools = emptyList()).reply().status)
            assertTrue("rejected before the model", p.seen.size == 2)
        }
    }

    @Test fun badTextIs400AndBigBodiesAre413() {
        withWalker(QueueProvider(text("ok"))) {
            val dev = pairedDevice()
            assertEquals(HttpStatusCode.BadRequest, ask(dev.deviceToken, text = "  ").status)
            assertEquals("""{"error":"bad_question"}""", ask(dev.deviceToken, text = "x".repeat(2_001)).bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, postJson("/v1/walker/ask", "{nope", bearer = dev.deviceToken).status)
            val big = buildJsonObject { put("blob", "z".repeat(130 * 1024)) }
            assertEquals(HttpStatusCode.PayloadTooLarge, ask(dev.deviceToken, context = big).status)
            // A large context is fine up to the body limit and is cut in the prompt.
            assertEquals(WalkerReply.DONE, ask(dev.deviceToken, context = buildJsonObject { put("blob", "z".repeat(100 * 1024)) }).reply().status)
        }
    }

    @Test fun noModelIs409() = withApi {
        val dev = pairedDevice()
        val r = ask(dev.deviceToken)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("""{"error":"no_model"}""", r.bodyAsText())
        assertEquals("outcome=no_model model=- tool_calls=0 client_calls=0", walkerLog().single().detail)
    }

    @Test fun providerFailureIs502AndLogged() {
        val failing = object : ModelProvider {
            override val model = "m"
            override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply = throw ProviderException("HTTP 500 from provider")
        }
        withWalker(failing) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken)
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals("""{"error":"model_error"}""", r.bodyAsText())
            assertTrue(walkerLog().single().detail.startsWith("outcome=model_error"))
        }
    }

    @Test fun theDeadlineIsPerHttpCall() {
        // Each call takes ~600 ms against a 1 s deadline: the whole turn (two calls) is longer, yet succeeds.
        val p = QueueProvider(
            { kotlinx.coroutines.delay(600); ModelReply.ToolCalls(listOf(call("c1", "close_screen"))) },
            { kotlinx.coroutines.delay(600); ModelReply.Text("ok") },
        )
        withWalker(p, Limits(askTimeoutMs = 1_000)) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken).reply()
            assertEquals(WalkerReply.DONE, cont(dev.deviceToken, r.turn!!, WalkerToolResult("c1", "ok")).reply().status)
        }
        val slow = QueueProvider({ kotlinx.coroutines.delay(5_000); ModelReply.Text("late") })
        withWalker(slow, Limits(askTimeoutMs = 300)) {
            val dev = pairedDevice()
            val r = ask(dev.deviceToken)
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertTrue(walkerLog().single().detail.startsWith("outcome=timeout"))
        }
    }

    @Test fun walkerHasItsOwnRateLimit() {
        val p = QueueProvider(calls(call("c1", "close_screen")), text("ok"))
        withWalker(p, Limits(walkerCallsPerMinute = 3, asksPerMinute = 2)) {
            val dev = pairedDevice()
            val turn = ask(dev.deviceToken).reply().turn!!
            cont(dev.deviceToken, turn, WalkerToolResult("c1", "ok")).reply() // continue counts too
            assertEquals(HttpStatusCode.OK, ask(dev.deviceToken).status)
            assertEquals(HttpStatusCode.TooManyRequests, ask(dev.deviceToken).status)
            assertEquals(HttpStatusCode.TooManyRequests, cont(dev.deviceToken, "x", WalkerToolResult("c1", "ok")).status)

            // The ring app's ask budget is untouched, and using it does not free walker calls.
            val q = testJson.encodeToString(AskRequest("hr?"))
            repeat(2) { assertEquals(HttpStatusCode.OK, postJson("/v1/agent/ask", q, bearer = dev.deviceToken).status) }
            assertEquals(HttpStatusCode.TooManyRequests, postJson("/v1/agent/ask", q, bearer = dev.deviceToken).status)
            assertEquals(HttpStatusCode.TooManyRequests, ask(dev.deviceToken).status)
            assertEquals("default walker budget", 60, Limits().walkerCallsPerMinute)

            clock.advance(61_000)
            assertEquals(HttpStatusCode.OK, ask(dev.deviceToken).status)
        }
    }
}
