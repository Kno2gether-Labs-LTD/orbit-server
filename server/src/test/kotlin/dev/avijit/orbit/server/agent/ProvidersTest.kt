package dev.avijit.orbit.server.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProvidersTest {
    private val specs = listOf(
        ToolSpec("get_metric", "Get a metric.", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("metric", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("metric"))))
        }),
    )

    /** Canned replies served in order; every request is recorded. */
    private class Mock(vararg replies: Pair<HttpStatusCode, String>) {
        val requests = mutableListOf<HttpRequestData>()
        private val queue = ArrayDeque(replies.toList())
        val client = HttpClient(MockEngine { req ->
            requests += req
            val (status, body) = queue.removeFirst()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        fun body(i: Int): JsonObject = Json.parseToJsonElement((requests[i].body as TextContent).text).jsonObject
    }

    private val ok = HttpStatusCode.OK

    // ---- OpenAI-compatible ----

    private val oaToolCall = """{"id":"x","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
        "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_metric","arguments":"{\"metric\":\"HEART_RATE\",\"agg\":\"avg\"}"}}]}}]}"""
    private val oaText = """{"id":"y","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Your average was 70 bpm."}}]}"""

    @Test fun openAiCompatRequestShape() = runBlocking {
        val m = Mock(ok to oaToolCall, ok to oaText)
        val p = OpenAiCompatProvider(m.client, "http://localhost:11434/v1/", "llama3.1", "sk-test-123")
        val first = p.chat(listOf(Msg.System("be brief"), Msg.User("heart rate?")), specs)

        val req = m.requests[0]
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("http://localhost:11434/v1/chat/completions", req.url.toString())
        assertEquals("Bearer sk-test-123", req.headers[HttpHeaders.Authorization])
        val b = m.body(0)
        assertEquals("llama3.1", b["model"]!!.jsonPrimitive.content)
        assertEquals("auto", b["tool_choice"]!!.jsonPrimitive.content)
        val msgs = b["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be brief", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        val tool = b["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        val fn = tool["function"]!!.jsonObject
        assertEquals("get_metric", fn["name"]!!.jsonPrimitive.content)
        assertEquals("object", fn["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        first as ModelReply.ToolCalls
        val call = first.calls.single()
        assertEquals("call_1", call.id)
        assertEquals("get_metric", call.name)
        assertEquals("HEART_RATE", call.args["metric"]!!.jsonPrimitive.content)

        // Second turn replays the tool call and sends the result as a `tool` message.
        val second = p.chat(listOf(
            Msg.System("be brief"), Msg.User("heart rate?"), first.asAssistant(),
            Msg.ToolResult("call_1", "get_metric", """{"value":70}""", isError = false),
        ), specs)
        val msgs2 = m.body(1)["messages"]!!.jsonArray
        val asst = msgs2[2].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        val tc = asst["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        val args = tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive
        assertTrue("arguments are a JSON string", args.isString)
        assertEquals("HEART_RATE", Json.parseToJsonElement(args.content).jsonObject["metric"]!!.jsonPrimitive.content)
        val toolMsg = msgs2[3].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"value":70}""", toolMsg["content"]!!.jsonPrimitive.content)
        assertEquals("Your average was 70 bpm.", (second as ModelReply.Text).text)
    }

    @Test fun openAiCompatWithoutKeySendsNoAuthAndAcceptsObjectArguments() = runBlocking {
        val objArgs = """{"choices":[{"message":{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"get_metric","arguments":{"metric":"SPO2"}}}]}}]}"""
        val m = Mock(ok to objArgs)
        val reply = OpenAiCompatProvider(m.client, "http://127.0.0.1:1234/v1", "local", null).chat(listOf(Msg.User("spo2")), specs)
        assertNull(m.requests[0].headers[HttpHeaders.Authorization])
        val call = (reply as ModelReply.ToolCalls).calls.single()
        assertEquals("SPO2", call.args["metric"]!!.jsonPrimitive.content)
        assertTrue("a missing id is generated", call.id.isNotBlank())
    }

    // ---- Anthropic ----

    private val anToolUse = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
        "content":[{"type":"thinking","thinking":"","signature":"sig-abc"},
                   {"type":"tool_use","id":"toolu_1","name":"get_metric","input":{"metric":"HEART_RATE"}}],
        "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}"""
    private val anText = """{"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-5-5",
        "content":[{"type":"text","text":"About 70 bpm "},{"type":"text","text":"(get_metric)."}],"stop_reason":"end_turn","usage":{}}"""

    @Test fun anthropicRequestShape() = runBlocking {
        val m = Mock(ok to anToolUse, ok to anText)
        val p = AnthropicProvider(m.client, "sk-ant-test-123")
        assertEquals("claude-sonnet-5-5", p.model)
        val first = p.chat(listOf(Msg.System("be brief"), Msg.User("old q"), Msg.Assistant("old a"), Msg.User("heart rate?")), specs)

        val req = m.requests[0]
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
        assertEquals("sk-ant-test-123", req.headers["x-api-key"])
        assertEquals(AnthropicProvider.API_VERSION, req.headers["anthropic-version"])
        assertNull("no bearer auth for Anthropic", req.headers[HttpHeaders.Authorization])
        val b = m.body(0)
        assertEquals("claude-sonnet-5-5", b["model"]!!.jsonPrimitive.content)
        assertEquals("be brief", b["system"]!!.jsonPrimitive.content)
        assertTrue(b["max_tokens"]!!.jsonPrimitive.content.toInt() > 0)
        assertFalse("sampling params are not sent", b.containsKey("temperature"))
        val msgs = b["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val tool = b["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_metric", tool["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        first as ModelReply.ToolCalls
        assertEquals("toolu_1", first.calls.single().id)
        assertEquals("HEART_RATE", first.calls.single().args["metric"]!!.jsonPrimitive.content)

        val second = p.chat(listOf(
            Msg.System("be brief"), Msg.User("heart rate?"), first.asAssistant(),
            Msg.ToolResult("toolu_1", "get_metric", """{"value":70}""", isError = false),
        ), specs)
        val msgs2 = m.body(1)["messages"]!!.jsonArray
        // The assistant turn is replayed exactly as received, thinking block included.
        val asstContent = msgs2[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", asstContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-abc", asstContent[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("tool_use", asstContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        val result = msgs2[2].jsonObject
        assertEquals("user", result["role"]!!.jsonPrimitive.content)
        val block = result["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", block["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("""{"value":70}""", block["content"]!!.jsonPrimitive.content)
        assertEquals("About 70 bpm (get_metric).", (second as ModelReply.Text).text)
    }

    @Test fun anthropicRefusalBecomesAPlainAnswer() = runBlocking {
        val refusal = """{"id":"m","type":"message","role":"assistant","content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"bio"}}"""
        val reply = AnthropicProvider(Mock(ok to refusal).client, "k").chat(listOf(Msg.User("q")), specs)
        assertTrue((reply as ModelReply.Text).text.isNotBlank())
    }

    @Test fun providerErrorsNeverCarryKeyOrBody() = runBlocking {
        val leaky = """{"error":{"message":"invalid x-api-key sk-ant-SECRET-999, health value 123"}}"""
        for (p in listOf<ModelProvider>(
            AnthropicProvider(Mock(HttpStatusCode.Unauthorized to leaky).client, "sk-ant-SECRET-999"),
            OpenAiCompatProvider(Mock(HttpStatusCode.InternalServerError to leaky).client, "http://h/v1", "m", "sk-ant-SECRET-999"),
            OpenAiCompatProvider(Mock(ok to "not json sk-ant-SECRET-999").client, "http://h/v1", "m", "sk-ant-SECRET-999"),
        )) {
            try {
                p.chat(listOf(Msg.User("q")), specs)
                fail("expected ProviderException")
            } catch (e: ProviderException) {
                val text = e.toString() + (e.cause?.toString() ?: "")
                assertFalse(text, text.contains("SECRET"))
                assertFalse(text, text.contains("123"))
            }
        }
    }
}
