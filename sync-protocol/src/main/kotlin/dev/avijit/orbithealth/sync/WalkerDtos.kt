package dev.avijit.orbithealth.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire contract between the OrbitWalker glasses app and the Orbit server (`/v1/walker/*`). Keep plain and stable.

/** A tool the phone runs itself. [parameters] is a JSON Schema object. */
@Serializable data class WalkerToolSpec(val name: String, val description: String, val parameters: JsonObject)

/**
 * `POST /v1/walker/ask`. [context] describes the glasses right now (screens, focus, installed apps, recording
 * state); [tools] are the phone's client tools; [zone] is the phone's IANA time zone id.
 */
@Serializable data class WalkerAskRequest(
    val text: String,
    val history: List<ChatTurn> = emptyList(),
    val context: JsonObject = JsonObject(emptyMap()),
    val tools: List<WalkerToolSpec> = emptyList(),
    val zone: String? = null,
)

/** The phone's result for one client call; [id] is [WalkerCall.id]. */
@Serializable data class WalkerToolResult(val id: String, val content: String, val isError: Boolean = false)

/** `POST /v1/walker/continue`: results for every call of a paused [turn]. */
@Serializable data class WalkerContinueRequest(val turn: String, val results: List<WalkerToolResult>)

/** A client tool call the phone must run and answer through `/v1/walker/continue`. */
@Serializable data class WalkerCall(val id: String, val name: String, val args: JsonObject)

/**
 * Reply to both walker routes. [status] is [DONE] ([answer] set) or [TOOLS] ([turn] and [calls] set; [say] is any
 * text the model gave with the calls, for the phone to speak while it works). [provenance] lists the server-side
 * (health) tool calls made so far in this turn.
 */
@Serializable data class WalkerReply(
    val status: String,
    val answer: String? = null,
    val turn: String? = null,
    val calls: List<WalkerCall> = emptyList(),
    val say: String? = null,
    val provenance: List<ToolUse> = emptyList(),
    val model: String,
) {
    companion object {
        const val DONE = "done"
        const val TOOLS = "tools"
    }
}
