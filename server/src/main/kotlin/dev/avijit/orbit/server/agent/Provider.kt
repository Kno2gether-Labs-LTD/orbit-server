package dev.avijit.orbit.server.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One turn of a provider-neutral conversation. */
sealed interface Msg {
    data class System(val text: String) : Msg
    data class User(val text: String) : Msg

    /**
     * A model turn. [raw] is the provider's own content as received (e.g. Anthropic content blocks with thinking
     * signatures); a provider that understands it replays it unchanged, others ignore it.
     */
    data class Assistant(val text: String?, val toolCalls: List<ToolCall> = emptyList(), val raw: JsonElement? = null) : Msg

    data class ToolResult(val callId: String, val name: String, val content: String, val isError: Boolean) : Msg
}

data class ToolCall(val id: String, val name: String, val args: JsonObject)

/** A tool the model may call; [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

sealed interface ModelReply {
    val raw: JsonElement?

    data class Text(val text: String, override val raw: JsonElement? = null) : ModelReply

    data class ToolCalls(val calls: List<ToolCall>, val text: String? = null, override val raw: JsonElement? = null) : ModelReply

    /** This reply as a conversation turn to send back with the tool results. */
    fun asAssistant(): Msg.Assistant = when (this) {
        is Text -> Msg.Assistant(text, emptyList(), raw)
        is ToolCalls -> Msg.Assistant(text, calls, raw)
    }
}

interface ModelProvider {
    /** Model id reported in [dev.avijit.orbithealth.sync.AskResponse.model]. */
    val model: String

    suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply
}

/**
 * A provider call failed (HTTP error, network, unparseable reply). The message is safe to log: it never contains
 * the response body, request content or API key, only a status code or an exception type.
 */
class ProviderException(message: String) : RuntimeException(message)

/** Builds a provider for the configured settings. Tests substitute their own. */
fun interface ProviderFactory {
    fun create(config: ProviderConfig): ModelProvider
}

object ProviderHttp {
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 60_000L

    /** CIO client with the agent's timeouts. One per server; it is thread-safe. */
    fun client(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /** Runs a provider HTTP call, turning transport errors into a [ProviderException] that names only the type. */
    suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: ProviderException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Ktor's request timeout is a CancellationException subclass; a real cancellation is rethrown as is.
        if (e::class.simpleName?.contains("Timeout") == true) throw ProviderException("provider timed out") else throw e
    } catch (e: Exception) {
        throw ProviderException("provider request failed: ${e::class.simpleName}")
    }
}

/** Tool arguments as plain strings for provenance (`AskResponse.provenance[].args`). */
fun JsonObject.toStringArgs(): Map<String, String> = mapValues { (_, v) ->
    when (v) {
        is JsonPrimitive -> v.content
        is JsonArray, is JsonObject -> v.toString()
        else -> v.toString()
    }
}
