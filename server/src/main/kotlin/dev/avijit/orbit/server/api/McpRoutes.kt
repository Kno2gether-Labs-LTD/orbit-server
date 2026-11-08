package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.AgentTokenRow
import dev.avijit.orbit.server.CountingAccessLog
import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.StoreDataTools
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.api.Mcp")

/**
 * MCP over Streamable HTTP, JSON responses only (no SSE, no server-initiated messages), stateless: no
 * `Mcp-Session-Id` is minted and any sent is ignored (sessions are optional in 2025-03-26..2025-11-25 and gone in
 * later revisions). Methods: `initialize`, `ping`, `tools/list`, `tools/call`; notifications and client responses
 * get 202. Batches (a 2025-03-26 feature) are answered element by element.
 *
 * Tools are the server agent's own [AgentTools] (same names, descriptions and JSON schemas), run read-only under the
 * agent token's name and scopes; every call, allowed, denied or malformed, leaves an access-log row.
 */
class McpServer(private val deps: ServerDeps) {

    private val toolList: JsonArray by lazy {
        val specs = AgentTools(StoreDataTools(deps.store, deps.accessLog, Caller("-", emptySet()), deps.zone, deps.clock), deps.zone, deps.clock).specs
        JsonArray(specs.map { spec ->
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("inputSchema", spec.parameters)
                putJsonObject("annotations") {
                    put("readOnlyHint", true); put("destructiveHint", false); put("idempotentHint", true); put("openWorldHint", false)
                }
            }
        })
    }
    private val toolNames: Set<String> by lazy { toolList.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet() }

    /**
     * The reply to one message, or null when none is due (a notification or a client response). [charge] is asked
     * before an `initialize` or `tools/call` runs; false means the token's budget is spent and the call is refused with
     * [RATE_LIMITED] (no data read, nothing logged, like an HTTP 429).
     */
    fun handle(msg: JsonElement, token: AgentTokenRow, charge: () -> Boolean = { true }): JsonObject? {
        if (msg !is JsonObject) return error(JsonNull, INVALID_REQUEST, "Invalid Request")
        val rawId = msg["id"]
        val idOk = rawId is JsonPrimitive && rawId !is JsonNull && (rawId.isString || rawId.content.toDoubleOrNull() != null)
        val id: JsonElement = if (idOk) rawId!! else JsonNull
        if ((msg["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") return error(id, INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"")
        val method = (msg["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // A response to a server request (we never send any) is acknowledged and dropped.
            return if (rawId != null && (msg.containsKey("result") || msg.containsKey("error"))) null else error(id, INVALID_REQUEST, "Invalid Request")
        }
        if (rawId == null) return null // notification: notifications/initialized, notifications/cancelled, ...
        if (!idOk) return error(JsonNull, INVALID_REQUEST, "Invalid Request: id must be a string or number")
        val params = when (val p = msg["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return error(id, INVALID_PARAMS, "params must be an object")
        }
        return try {
            when (method) {
                // initialize writes an access-log row, so it pays like tools/call (a batch of them cannot flood the log).
                "initialize" -> if (charge()) result(id, initialize(params, token)) else error(id, RATE_LIMITED, "rate_limited")
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", toolList) })
                "tools/call" -> if (charge()) callTool(id, params, token) else error(id, RATE_LIMITED, "rate_limited")
                else -> error(id, METHOD_NOT_FOUND, "Method not found: ${method.take(60)}")
            }
        } catch (e: Exception) {
            log.error("MCP {} failed: {}", method.take(40), e::class.java.simpleName)
            error(id, INTERNAL_ERROR, "Internal error")
        }
    }

    private fun initialize(params: JsonObject, token: AgentTokenRow): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else LATEST_VERSION
        val client = params["clientInfo"] as? JsonObject
        val clientName = (client?.get("name") as? JsonPrimitive)?.contentOrNull?.take(40) ?: "unknown"
        val clientVersion = (client?.get("version") as? JsonPrimitive)?.contentOrNull?.take(20).orEmpty()
        deps.accessLog.record(token.name, "mcp_initialize", "client=$clientName $clientVersion protocol=$version", 0)
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") { put("name", SERVER_NAME); put("title", "Orbit Server"); put("version", SERVER_VERSION) }
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject, token: AgentTokenRow): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return error(id, INVALID_PARAMS, "tools/call needs a tool name")
        if (name !in toolNames) {
            deps.accessLog.record(token.name, "mcp_tools_call", "ERROR unknown_tool", 0)
            return error(id, INVALID_PARAMS, "Unknown tool: ${name.take(40)}")
        }
        val args = when (val a = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> return error(id, INVALID_PARAMS, "arguments must be an object")
        }
        val counting = CountingAccessLog(deps.accessLog, deps.store, deps.clock)
        val zone = deps.userZone() // the phone's zone from its latest ingest
        val data = StoreDataTools(deps.store, counting, Caller(token.name, token.scopes), zone, deps.clock)
        val out = AgentTools(data, zone, deps.clock).call(name, args)
        if (out.isError && counting.count == 0) {
            // Rejected before any data was touched (bad arguments): still one row for the attempt.
            val code = runCatching { Json.parseToJsonElement(out.content).jsonObject["error"]!!.jsonPrimitive.content }.getOrDefault("error")
            deps.accessLog.record(token.name, name, "ERROR $code", 0)
        }
        return result(id, buildJsonObject {
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", out.content) }) }
            put("isError", out.isError)
        })
    }

    companion object {
        const val LATEST_VERSION = "2025-11-25"
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        const val SERVER_NAME = "orbit-server"
        const val SERVER_VERSION = "0.1.0"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val UNSUPPORTED_VERSION = -32022
        /** Implementation-defined server error: the token's per-minute budget is spent. */
        const val RATE_LIMITED = -32000

        private const val INSTRUCTIONS =
            "Read-only access to the user's Orbit Health ring data (heart rate, HRV, SpO2, sleep, activity, stress). " +
                "Only the metric groups this token was granted are visible; every call is logged for the user. " +
                "Blood pressure and stress are estimates. Do the maths with the tools, not by hand."

        fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String, data: JsonElement? = null) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            putJsonObject("error") { put("code", code); put("message", message); data?.let { put("data", it) } }
        }
    }
}

/** `POST /mcp` (agent token). GET and DELETE are 405: there is no SSE stream and no session to end. */
fun Route.mcpRoutes(deps: ServerDeps, gate: AgentGate) {
    val server = McpServer(deps)
    route("/mcp") {
        post {
            if (!OriginPolicy.allowed(call.request.headers[HttpHeaders.Origin], deps.originHosts)) {
                return@post call.rpc(HttpStatusCode.Forbidden, McpServer.error(JsonNull, McpServer.INVALID_REQUEST, "Forbidden origin"))
            }
            val token = gate.require(call, "mcp")
            val text = call.receiveTextLimited(deps.limits.maxMcpBytes)
            val body = try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return@post call.rpc(HttpStatusCode.BadRequest, McpServer.error(JsonNull, McpServer.PARSE_ERROR, "Parse error"))
            }
            val headerVersion = call.request.headers["MCP-Protocol-Version"]?.trim()
            if (headerVersion != null && headerVersion !in McpServer.SUPPORTED_VERSIONS && !isInitialize(body)) {
                val id = ((body as? JsonObject)?.get("id") as? JsonPrimitive) ?: JsonNull
                val data = buildJsonObject {
                    put("supported", JsonArray(McpServer.SUPPORTED_VERSIONS.map(::JsonPrimitive)))
                    put("requested", headerVersion.take(40))
                }
                return@post call.rpc(HttpStatusCode.BadRequest, McpServer.error(id, McpServer.UNSUPPORTED_VERSION, "Unsupported protocol version", data))
            }
            when (body) {
                is JsonArray -> {
                    if (body.isEmpty()) return@post call.rpc(HttpStatusCode.BadRequest, McpServer.error(JsonNull, McpServer.INVALID_REQUEST, "Invalid Request: empty batch"))
                    if (body.size > deps.limits.maxMcpBatch) {
                        return@post call.rpc(HttpStatusCode.BadRequest, McpServer.error(JsonNull, McpServer.INVALID_REQUEST,
                            "Invalid Request: batch larger than ${deps.limits.maxMcpBatch} messages"))
                    }
                    // The HTTP request already paid for one tools/call (gate.require); every further one pays its own.
                    var prepaid = true
                    val charge = { if (prepaid) { prepaid = false; true } else gate.tryCharge(token) }
                    val replies = body.mapNotNull { server.handle(it, token, charge) }
                    if (replies.isEmpty()) call.respond(HttpStatusCode.Accepted) else call.rpc(HttpStatusCode.OK, JsonArray(replies))
                }
                else -> {
                    val reply = server.handle(body, token)
                    if (reply == null) {
                        call.respond(HttpStatusCode.Accepted)
                    } else {
                        val code = (reply["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content?.toIntOrNull()
                        call.rpc(if (code == McpServer.INVALID_REQUEST) HttpStatusCode.BadRequest else HttpStatusCode.OK, reply)
                    }
                }
            }
        }
        get { call.methodNotAllowed() }
        delete { call.methodNotAllowed() }
    }
}

private fun isInitialize(body: JsonElement) = ((body as? JsonObject)?.get("method") as? JsonPrimitive)?.contentOrNull == "initialize"

private suspend fun ApplicationCall.rpc(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.methodNotAllowed() {
    response.header(HttpHeaders.Allow, "POST")
    respond(HttpStatusCode.MethodNotAllowed)
}
