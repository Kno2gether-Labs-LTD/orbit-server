package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.StoreDataTools
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.Scope
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

suspend fun ApiEnv.agentToken(name: String = "claude-code", scopes: Set<String> = Scope.ALL_WIRE): String =
    deps.agentTokens.create(name, scopes).token

suspend fun ApiEnv.mcp(token: String?, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
    client.post("/mcp") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        headers.forEach { (k, v) -> header(k, v) }
        setBody(body)
    }

fun rpc(id: Int, method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

fun toolCall(id: Int, name: String, args: String) = rpc(id, "tools/call", """{"name":"$name","arguments":$args}""")

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

class McpTest {
    private fun seedHeart(env: ApiEnv) = env.store.insertSamples(
        listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 64.0, "HISTORY"), SampleDto("HEART_RATE", T0 - 7_200_000, 66.0, "HISTORY")),
        null,
    )

    @Test fun mcpInitializeAndListTools() = withApi {
        val token = agentToken("claude-code")
        val init = mcp(token, rpc(1, "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1.0"}}"""))
        assertEquals(init.bodyAsText(), HttpStatusCode.OK, init.status)
        assertTrue(init.contentType()!!.match(ContentType.Application.Json))
        val r = init.json()
        assertEquals("2.0", r["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, r["id"]!!.jsonPrimitive.int)
        val result = r["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("orbit-server", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        // Stateless: no session is minted (sessions are optional, and removed in newer protocol revisions).
        assertNull(init.headers["Mcp-Session-Id"])

        // An unknown version gets the server's latest.
        val other = mcp(token, rpc(2, "initialize", """{"protocolVersion":"1999-01-01","capabilities":{},"clientInfo":{"name":"x","version":"1"}}"""))
        assertEquals(McpServer.LATEST_VERSION, other.json()["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        val note = mcp(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", mapOf("MCP-Protocol-Version" to "2025-06-18"))
        assertEquals(HttpStatusCode.Accepted, note.status)
        assertEquals("", note.bodyAsText())

        val list = mcp(token, rpc(3, "tools/list"), mapOf("MCP-Protocol-Version" to "2025-06-18"))
        assertEquals(HttpStatusCode.OK, list.status)
        val tools = list.json()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("get_metric", "get_sleep", "daily_summary", "compare_to_baseline", "list_metrics"), tools.map { it["name"]!!.jsonPrimitive.content })
        // One tool definition shared with the server agent.
        val specs = AgentTools(StoreDataTools(store, deps.accessLog, Caller("x", emptySet()), ZoneOffset.UTC, clock), ZoneOffset.UTC, clock).specs
        for ((tool, spec) in tools.zip(specs)) {
            assertEquals(spec.parameters, tool["inputSchema"])
            assertEquals(spec.description, tool["description"]!!.jsonPrimitive.content)
            assertTrue(tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        }

        val ping = mcp(token, rpc(4, "ping"))
        assertEquals(JsonObject(emptyMap()), ping.json()["result"])

        val log = store.accessLog(10)
        val connect = log.single { it.action == "mcp_initialize" && it.detail.contains("2025-06-18") }
        assertEquals("claude-code", connect.actor)
        assertTrue(connect.detail, connect.detail.contains("client=claude-code"))
    }

    @Test fun mcpToolCallReturnsData() = withApi {
        seedHeart(this)
        val token = agentToken("claude-code")
        val r = mcp(token, toolCall(7, "get_metric", """{"metric":"HEART_RATE","agg":"avg"}"""))
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        val result = r.json()["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        val content = result["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        val data = Json.parseToJsonElement(content["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("HEART_RATE", data["metric"]!!.jsonPrimitive.content)
        assertEquals(65.0, data["points"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.double, 0.0)

        val row = store.accessLog(10).single()
        assertEquals("claude-code", row.actor)
        assertEquals("get_metric", row.action)
        assertEquals(2, row.rows)
        assertFalse("no health values in the log", row.detail.contains("65") || row.detail.contains("64.0"))

        // Sleep through MCP too.
        val sleep = mcp(token, toolCall(8, "get_sleep", """{"night":"last"}"""))
        assertTrue(sleep.bodyAsText().contains("\\\"found\\\":false"))
    }

    @Test fun mcpScopeDenied() = withApi {
        seedHeart(this)
        val token = agentToken("sleep-bot", setOf("sleep"))
        val r = mcp(token, toolCall(1, "get_metric", """{"metric":"HEART_RATE","agg":"avg"}"""))
        assertEquals(HttpStatusCode.OK, r.status)
        val result = r.json()["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("scope_denied") && text.contains("heart"))
        assertFalse(text, text.contains("65"))
        val row = store.accessLog(10).single()
        assertEquals("sleep-bot", row.actor)
        assertEquals("get_metric", row.action)
        assertTrue(row.detail, row.detail.startsWith("DENIED"))
        assertEquals(0, row.rows)

        // Nothing shared at all: a neutral denial that names no scope.
        val none = agentToken("profile-only", setOf("profile"))
        val d = mcp(none, toolCall(2, "daily_summary", "{}")).json()["result"]!!.jsonObject
        assertTrue(d["isError"]!!.jsonPrimitive.boolean)
        assertTrue(d.toString(), d.toString().contains("No health data has been shared"))
        assertEquals("DENIED", store.accessLog(1).single().detail.substringBefore(' '))
    }

    @Test fun mcpRequiresAnAgentTokenAndRejectsForeignOrigins() = withApi {
        val init = rpc(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}""")
        val noAuth = mcp(null, init)
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        assertTrue(noAuth.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Bearer"))
        val device = pairedDevice()
        assertEquals("a device token is not an agent token", HttpStatusCode.Unauthorized, mcp(device.deviceToken, init).status)

        val token = agentToken()
        // DNS rebinding: a web page on a foreign name must not reach the LAN server.
        for (bad in listOf("https://evil.example", "http://orbit.attacker.net:8443", "null")) {
            assertEquals(bad, HttpStatusCode.Forbidden, mcp(token, init, mapOf(HttpHeaders.Origin to bad)).status)
        }
        for (ok in listOf("http://localhost:6274", "http://127.0.0.1:3000", "https://192.168.1.5:8443", "http://[::1]:5173")) {
            assertEquals(ok, HttpStatusCode.OK, mcp(token, init, mapOf(HttpHeaders.Origin to ok)).status)
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test fun mcpProtocolErrors() = withApi {
        val token = agentToken()
        val parse = mcp(token, "{not json")
        assertEquals(HttpStatusCode.BadRequest, parse.status)
        assertEquals(-32700, parse.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val invalid = mcp(token, """{"jsonrpc":"1.0","id":1,"method":"ping"}""")
        assertEquals(-32600, invalid.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val unknown = mcp(token, rpc(2, "resources/list"))
        assertEquals(-32601, unknown.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val noTool = mcp(token, toolCall(3, "delete_everything", "{}"))
        assertEquals(-32602, noTool.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        // Newer protocol revisions this server does not speak: the spec's negotiation error, so the client falls back.
        val newer = mcp(token, rpc(4, "tools/list"), mapOf("MCP-Protocol-Version" to "2026-07-28"))
        assertEquals(HttpStatusCode.BadRequest, newer.status)
        val err = newer.json()["error"]!!.jsonObject
        assertEquals(-32022, err["code"]!!.jsonPrimitive.int)
        assertTrue(err["data"]!!.jsonObject["supported"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("2025-06-18"))

        // Bad tool arguments are an error result, and the attempt is logged.
        val bad = mcp(token, toolCall(5, "get_metric", """{"metric":"HEART_RATE","agg":"weekly"}""")).json()["result"]!!.jsonObject
        assertTrue(bad["isError"]!!.jsonPrimitive.boolean)
        val row = store.accessLog(1).single()
        assertEquals("get_metric", row.action)
        assertTrue(row.detail, row.detail.startsWith("ERROR bad_arguments"))

        // Batches (2025-03-26) are answered element by element; notifications get no element.
        val batch = mcp(token, "[${rpc(10, "ping")},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},${rpc(11, "tools/list")}]")
        val arr = Json.parseToJsonElement(batch.bodyAsText()) as JsonArray
        assertEquals(listOf(10, 11), arr.map { it.jsonObject["id"]!!.jsonPrimitive.int })

        // Oversized bodies are refused.
        val huge = mcp(token, rpc(12, "ping", "{\"pad\":\"${"x".repeat(70_000)}\"}"))
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
    }

    @Test fun agentTokensAreRateLimited() = withApi(limits = Limits(agentRequestsPerMinute = 3)) {
        val token = agentToken()
        repeat(3) { assertEquals(HttpStatusCode.OK, mcp(token, rpc(it, "ping")).status) }
        assertEquals(HttpStatusCode.TooManyRequests, mcp(token, rpc(9, "ping")).status)
        assertEquals("REST shares the budget", HttpStatusCode.TooManyRequests,
            client.get("/v1/data/samples?metric=HEART_RATE") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        // Another token has its own budget.
        assertEquals(HttpStatusCode.OK, mcp(agentToken("other"), rpc(1, "ping")).status)
        clock.advance(60_001)
        assertEquals(HttpStatusCode.OK, mcp(token, rpc(10, "ping")).status)
    }

    @Test fun batchesAreCappedAndEachToolCallIsCharged() = withApi(limits = Limits(agentRequestsPerMinute = 5)) {
        seedHeart(this)
        val token = agentToken()
        // 11 messages: refused whole, nothing runs, nothing logged.
        val eleven = mcp(token, "[" + (1..11).joinToString(",") { rpc(it, "ping") } + "]")
        assertEquals(HttpStatusCode.BadRequest, eleven.status)
        assertEquals(-32600, eleven.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(store.accessLog(10).isEmpty())

        // Budget 5, one already spent on the request above. A batch of 8 tools/calls: the request pays for the
        // first, 3 more fit, the remaining 4 are rate_limited.
        val calls = (1..8).joinToString(",") { toolCall(100 + it, "get_metric", """{"metric":"HEART_RATE","agg":"avg"}""") }
        val r = mcp(token, "[$calls]")
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        val replies = (Json.parseToJsonElement(r.bodyAsText()) as JsonArray).map { it.jsonObject }
        assertEquals((101..108).toList(), replies.map { it["id"]!!.jsonPrimitive.int })
        val ok = replies.filter { it.containsKey("result") }
        val limited = replies.filter { it["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == -32000 }
        assertEquals(4, ok.size)
        assertEquals(4, limited.size)
        assertTrue(limited.all { it["error"]!!.jsonObject["message"]!!.jsonPrimitive.content == "rate_limited" })
        assertEquals("only calls that ran read data", 4, store.accessLog(20).count { it.action == "get_metric" })
        // Budget is spent: the next request is a 429.
        assertEquals(HttpStatusCode.TooManyRequests, mcp(token, rpc(1, "ping")).status)
    }

    @Test fun initializeInABatchIsChargedLikeToolsCall() = withApi(limits = Limits(agentRequestsPerMinute = 3)) {
        val token = agentToken()
        val init = """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}"""
        val r = mcp(token, "[" + (1..5).joinToString(",") { rpc(it, "initialize", init) } + "]")
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        val replies = (Json.parseToJsonElement(r.bodyAsText()) as JsonArray).map { it.jsonObject }
        assertEquals(3, replies.count { it.containsKey("result") })
        assertEquals(2, replies.count { it["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == -32000 })
        assertEquals("each initialize writes a log row, so each one pays", 3, store.accessLog(20).count { it.action == "mcp_initialize" })
        assertEquals(HttpStatusCode.TooManyRequests, mcp(token, rpc(9, "initialize", init)).status)
    }

    @Test fun revokedAgentTokenRejected() = withApi {
        seedHeart(this)
        val created = deps.agentTokens.create("script", Scope.ALL_WIRE)
        val token = created.token
        assertEquals(HttpStatusCode.OK, mcp(token, rpc(1, "ping")).status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/data/samples?metric=HEART_RATE") { header(HttpHeaders.Authorization, "Bearer $token") }.status)

        assertTrue(deps.agentTokens.revoke(created.row.id))
        assertEquals(HttpStatusCode.Unauthorized, mcp(token, rpc(2, "ping")).status)
        assertEquals(HttpStatusCode.Unauthorized, mcp(token, toolCall(3, "get_metric", """{"metric":"HEART_RATE"}""")).status)
        val rest = client.get("/v1/data/samples?metric=HEART_RATE") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, rest.status)
        assertFalse(rest.bodyAsText().contains("64"))
        // Use of a revoked token is itself recorded under its name, with no data read.
        val denied = store.accessLog(10).filter { it.actor == "script" && it.detail.contains("revoked") }
        assertEquals(3, denied.size)
        assertTrue(denied.all { it.rows == 0 })
        // Other tokens are unaffected.
        assertEquals(HttpStatusCode.OK, mcp(agentToken("other"), rpc(4, "ping")).status)
    }
}
