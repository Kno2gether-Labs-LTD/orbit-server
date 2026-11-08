package dev.avijit.orbithealth.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire contract between the OrbitWalker glasses app and the Orbit server (`/v1/walker/*`). Keep plain and stable.

/** A tool the phone runs itself. [parameters] is a JSON Schema object. */
@Serializable data class WalkerToolSpec(val name: String, val description: String, val parameters: JsonObject)

/**
 * `POST /v1/walker/ask`. [context] describes the glasses right now (screens, focus, installed apps, recording
 * state); [tools] are the phone's client tools; [zone] is the phone's IANA time zone id.
 *
 * [conversation] (optional, 1–64 of `[A-Za-z0-9_-]`) is the id from a previous [WalkerReply.conversation]: the
 * server then uses that conversation's stored messages as the model's history and ignores [history]. Without it (or
 * when it is unknown, another device's, or idle for 6 hours) the server starts a new conversation and the model sees
 * [history], as before.
 */
@Serializable data class WalkerAskRequest(
    val text: String,
    val history: List<ChatTurn> = emptyList(),
    val context: JsonObject = JsonObject(emptyMap()),
    val tools: List<WalkerToolSpec> = emptyList(),
    val zone: String? = null,
    val conversation: String? = null,
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
 * (health) tool calls made so far in this turn. [conversation] is the conversation this turn belongs to: send it
 * with the next ask to continue it.
 */
@Serializable data class WalkerReply(
    val status: String,
    val answer: String? = null,
    val turn: String? = null,
    val calls: List<WalkerCall> = emptyList(),
    val say: String? = null,
    val provenance: List<ToolUse> = emptyList(),
    val model: String,
    /** Left out of the JSON when null, so the reply's shape is unchanged for anything that never sets it. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val conversation: String? = null,
) {
    companion object {
        const val DONE = "done"
        const val TOOLS = "tools"
    }
}

/** One server voice. [id] is `provider:voiceId` (e.g. `openai:coral`), what [WalkerSpeakRequest.voice] takes. */
@Serializable data class WalkerVoice(val id: String, val name: String, val provider: String, val providerName: String)

/** `GET /v1/walker/voices`: voices of every configured provider; [defaultVoice] is used when a speak names none. */
@Serializable data class WalkerVoicesResponse(
    val voices: List<WalkerVoice>,
    @kotlinx.serialization.SerialName("default") val defaultVoice: String? = null,
)

/** `POST /v1/walker/speak`: 1–1500 characters of [text]; [voice] null means the default. Answer: MP3 bytes. */
@Serializable data class WalkerSpeakRequest(val text: String, val voice: String? = null)
