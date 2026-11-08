package dev.avijit.orbit.server.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages API with client tools: `POST https://api.anthropic.com/v1/messages`.
 *
 * - Assistant turns are replayed exactly as received ([Msg.Assistant.raw]), so thinking blocks and their signatures
 *   go back unchanged, as the API requires.
 * - Consecutive tool results are sent together in one user message.
 * - `stop_reason: "refusal"` becomes a short plain answer instead of an error.
 * - Current models (see [FALLBACK_MODELS]) opt into server-side refusal fallback (`fallbacks: "default"`).
 *   Effort is set only for models known to accept it, so older or custom model ids still work.
 */
class AnthropicProvider(
    private val http: HttpClient,
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL,
) : ModelProvider {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"

    override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        val body = requestBody(messages, tools).toString()
        val fallback = model in FALLBACK_MODELS
        val (status, text) = ProviderHttp.guard {
            val r = http.post(endpoint) {
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                if (fallback) header("anthropic-beta", FALLBACK_BETA)
                setBody(TextContent(body, ContentType.Application.Json))
            }
            r.status to r.bodyAsText()
        }
        if (!status.isSuccess()) throw ProviderException("HTTP ${status.value} from provider")
        return parse(text)
    }

    internal fun requestBody(messages: List<Msg>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        val system = messages.filterIsInstance<Msg.System>().joinToString("\n\n") { it.text }
        if (system.isNotEmpty()) put("system", system)
        if (tools.isNotEmpty()) putJsonArray("tools") {
            for (t in tools) addJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", t.parameters)
            }
        }
        if (model in EFFORT_MODELS) putJsonObject("output_config") { put("effort", EFFORT) }
        if (model in FALLBACK_MODELS) put("fallbacks", "default")
        putJsonArray("messages") {
            val pendingResults = mutableListOf<Msg.ToolResult>()
            fun flushResults() {
                if (pendingResults.isEmpty()) return
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        for (r in pendingResults) addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", r.callId)
                            put("content", r.content)
                            if (r.isError) put("is_error", true)
                        }
                    }
                }
                pendingResults.clear()
            }
            for (m in messages) {
                if (m is Msg.ToolResult) { pendingResults += m; continue }
                flushResults()
                when (m) {
                    is Msg.System -> Unit
                    is Msg.User -> addJsonObject { put("role", "user"); put("content", m.text) }
                    is Msg.Assistant -> addJsonObject {
                        put("role", "assistant")
                        when {
                            m.raw is JsonArray -> put("content", m.raw)
                            m.toolCalls.isEmpty() -> put("content", m.text.orEmpty())
                            else -> putJsonArray("content") {
                                if (!m.text.isNullOrBlank()) addJsonObject { put("type", "text"); put("text", m.text) }
                                for (c in m.toolCalls) addJsonObject {
                                    put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", c.args)
                                }
                            }
                        }
                    }
                    is Msg.ToolResult -> Unit
                }
            }
            flushResults()
        }
    }

    private fun parse(text: String): ModelReply {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProviderException("unexpected reply from provider")
        }
        val stop = (root["stop_reason"] as? JsonPrimitive)?.content
        if (stop == "refusal") return ModelReply.Text(REFUSAL_ANSWER)
        val content = root["content"] as? JsonArray ?: throw ProviderException("unexpected reply from provider")
        val blocks = content.mapNotNull { it as? JsonObject }
        val textOut = blocks.filter { (it["type"] as? JsonPrimitive)?.content == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.content.orEmpty() }
        val calls = blocks.filter { (it["type"] as? JsonPrimitive)?.content == "tool_use" }.mapNotNull { b ->
            val id = (b["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (b["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            ToolCall(id, name, b["input"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        return if (calls.isNotEmpty() && stop != "max_tokens") ModelReply.ToolCalls(calls, textOut.ifBlank { null }, content)
        else ModelReply.Text(textOut, content)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        /** Current Messages API version (removes sampling parameters; we send none). */
        const val API_VERSION = "2026-10-21"
        const val MAX_TOKENS = 4096
        private const val EFFORT = "medium"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val FALLBACK_MODELS = setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1")
        private val EFFORT_MODELS = FALLBACK_MODELS + setOf("claude-sonnet-5", "claude-haiku-5-5", "claude-fable-5", "claude-opus-4-8", "claude-opus-4-7")
        const val REFUSAL_ANSWER = "I can't help with that request."
    }
}
