package dev.avijit.orbit.server.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI-compatible Chat Completions with function tools: `POST {baseUrl}/chat/completions`.
 * Works with Ollama (`http://localhost:11434/v1`), LM Studio (`http://localhost:1234/v1`) and most hosted
 * providers. [apiKey] is optional (local servers need none) and is sent only as `Authorization: Bearer`.
 */
class OpenAiCompatProvider(
    private val http: HttpClient,
    baseUrl: String,
    override val model: String,
    private val apiKey: String?,
) : ModelProvider {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        val body = requestBody(messages, tools).toString()
        val (status, text) = ProviderHttp.guard {
            val r = http.post(endpoint) {
                if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(TextContent(body, ContentType.Application.Json))
            }
            r.status to r.bodyAsText()
        }
        if (!status.isSuccess()) throw ProviderException("HTTP ${status.value} from provider")
        return parse(text)
    }

    internal fun requestBody(messages: List<Msg>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", false)
        putJsonArray("messages") {
            for (m in messages) addJsonObject {
                when (m) {
                    is Msg.System -> { put("role", "system"); put("content", m.text) }
                    is Msg.User -> { put("role", "user"); put("content", m.text) }
                    is Msg.Assistant -> {
                        put("role", "assistant")
                        put("content", m.text?.let(::JsonPrimitive) ?: JsonNull)
                        if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                            for (c in m.toolCalls) addJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", c.args.toString()) // a JSON *string*, per the API
                                }
                            }
                        }
                    }
                    is Msg.ToolResult -> { put("role", "tool"); put("tool_call_id", m.callId); put("content", m.content) }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (t in tools) addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }

    private fun parse(text: String): ModelReply {
        val message = try {
            Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        } catch (e: Exception) {
            throw ProviderException("unexpected reply from provider")
        }
        val content = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val fn = o["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapIndexedNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "call_$i"
            ToolCall(id, name, parseArgs(fn["arguments"]))
        }
        return if (calls.isNotEmpty()) ModelReply.ToolCalls(calls, content?.takeIf { it.isNotBlank() })
        else ModelReply.Text(content.orEmpty())
    }

    /** Arguments are a JSON string per the spec; some local servers send an object. Garbage becomes `{}`. */
    private fun parseArgs(el: JsonElement?): JsonObject = when {
        el is JsonObject -> el
        el is JsonPrimitive && el.isString -> try {
            Json.parseToJsonElement(el.content.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        else -> JsonObject(emptyMap())
    }
}
