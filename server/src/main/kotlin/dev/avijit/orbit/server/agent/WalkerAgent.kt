package dev.avijit.orbit.server.agent

import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.ToolUse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Where a walker turn stands between HTTP calls: the conversation so far, the server-side [provenance], how many
 * model calls were used, and (while paused) the results of the last step in call order, some still waiting for the
 * phone. Single use: a paused state is resumed once.
 */
class WalkerState internal constructor(
    internal val messages: MutableList<Msg>,
    val provenance: MutableList<ToolUse> = mutableListOf(),
    var modelCalls: Int = 0,
    var serverCalls: Int = 0,
    var clientCalls: Int = 0,
) {
    /** The paused step's results in the model's call order; empty when not paused. */
    internal var pending: List<Slot> = emptyList()

    /** Client calls the phone must answer, in call order. */
    val expectedCalls: List<ToolCall> get() = pending.filterIsInstance<Slot.Client>().map { it.call }

    internal sealed interface Slot {
        data class Ready(val result: Msg.ToolResult) : Slot
        data class Client(val call: ToolCall) : Slot
    }
}

sealed interface WalkerOutcome {
    data class Done(val answer: String, val model: String) : WalkerOutcome

    /** The phone must run [calls] (in this order) and continue; [say] is text the model sent with them, if any. */
    data class Paused(val calls: List<ToolCall>, val say: String?, val model: String) : WalkerOutcome
}

/** The phone's answer to one client call. */
data class ClientResult(val content: String, val isError: Boolean)

/**
 * The OrbitWalker voice loop. Like [Agent], but some tools are client tools: declared by the phone, run on the
 * phone. Health tools ([healthTools]) run here at once; when a step calls any client tool the loop pauses
 * ([WalkerOutcome.Paused]) and [resume] continues it with the phone's results. Every call of a step gets a result,
 * in the model's call order, before the next model call. At most [maxModelCalls] model calls per whole turn and
 * [Agent.MAX_CALLS_PER_STEP] tool calls per step (the rest get [Agent.TOO_MANY_CALLS]).
 */
class WalkerAgent(
    private val provider: ModelProvider,
    private val healthTools: AgentTools,
    private val clientTools: List<ToolSpec>,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxModelCalls: Int = MAX_MODEL_CALLS,
) {
    private val clientNames = clientTools.map { it.name }.toSet()
    private val specs = healthTools.specs + clientTools

    /** A fresh turn: system prompt with [context], trimmed [history] (as [Agent] trims it), then [text]. */
    fun start(text: String, history: List<ChatTurn>, context: JsonObject): WalkerState {
        val messages = mutableListOf<Msg>(Msg.System(systemPrompt(context)))
        messages += history
            .filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() }
            .takeLast(Agent.MAX_HISTORY)
            .dropWhile { it.role != "user" }
            .map { t -> t.text.take(Agent.MAX_TURN_CHARS).let { if (t.role == "user") Msg.User(it) else Msg.Assistant(it) } }
        messages += Msg.User(text)
        return WalkerState(messages)
    }

    /** Runs [state] until the model answers, a step needs the phone, or the model-call budget is spent. */
    suspend fun run(state: WalkerState): WalkerOutcome {
        check(state.pending.isEmpty()) { "turn is paused" }
        while (state.modelCalls < maxModelCalls) {
            state.modelCalls++
            when (val reply = provider.chat(state.messages, specs)) {
                is ModelReply.Text -> return WalkerOutcome.Done(reply.text.trim().ifEmpty { Agent.EMPTY_ANSWER }, provider.model)
                is ModelReply.ToolCalls -> {
                    state.messages += Msg.Assistant(reply.text, reply.calls, reply.raw)
                    val slots = reply.calls.mapIndexed { i, c ->
                        when {
                            i >= Agent.MAX_CALLS_PER_STEP -> WalkerState.Slot.Ready(Msg.ToolResult(c.id, c.name, Agent.TOO_MANY_CALLS, isError = true))
                            c.name in clientNames -> {
                                state.clientCalls++
                                WalkerState.Slot.Client(c)
                            }
                            else -> {
                                state.serverCalls++
                                state.provenance += ToolUse(c.name, c.args.toStringArgs())
                                val out = healthTools.call(c.name, c.args)
                                WalkerState.Slot.Ready(Msg.ToolResult(c.id, c.name, out.content, out.isError))
                            }
                        }
                    }
                    if (slots.any { it is WalkerState.Slot.Client }) {
                        state.pending = slots
                        return WalkerOutcome.Paused(state.expectedCalls, reply.text?.trim()?.takeIf { it.isNotEmpty() }, provider.model)
                    }
                    slots.forEach { state.messages += (it as WalkerState.Slot.Ready).result }
                }
            }
        }
        return WalkerOutcome.Done(Agent.STEP_LIMIT_ANSWER, provider.model)
    }

    /**
     * Fills the paused step with [results] (one per [WalkerState.expectedCalls] id; the caller checks that) and
     * runs on. Results are added in the model's call order, interleaved with the server results of that step.
     */
    suspend fun resume(state: WalkerState, results: Map<String, ClientResult>): WalkerOutcome {
        val slots = state.pending
        require(slots.isNotEmpty()) { "turn is not paused" }
        for (s in slots) {
            state.messages += when (s) {
                is WalkerState.Slot.Ready -> s.result
                is WalkerState.Slot.Client -> {
                    val r = requireNotNull(results[s.call.id]) { "missing result" }
                    Msg.ToolResult(s.call.id, s.call.name, r.content, r.isError)
                }
            }
        }
        state.pending = emptyList()
        return run(state)
    }

    private fun systemPrompt(context: JsonObject): String {
        val now = ZonedDateTime.now(clock.withZone(zone))
        val glasses = PRETTY.encodeToString(JsonObject.serializer(), context).let {
            if (it.length > MAX_CONTEXT_CHARS) it.take(MAX_CONTEXT_CHARS) + "\n…(truncated)" else it
        }
        return """
            |You are Orbit, the voice assistant inside the user's AR glasses (OrbitWalker app). Replies are spoken aloud and
            |shown as a caption: one or two short sentences, plain words, no markdown, no lists, no emoji. Act with the
            |glasses tools rather than describing how. The `context` JSON describes what is open right now (screens, which is
            |focused, apps installed, recording state). Refer to apps by their names. If a request is ambiguous, pick the most
            |likely meaning and do it; only ask when acting could lose something. Never claim you did something a tool did
            |not confirm. For health questions use the health tools and the same honesty rules as the health assistant
            |(numbers only from tools; estimates called estimates; no diagnosis).
            |
            |Now: ${now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))} (${zone.id}). "Last night" is the night that began yesterday.
            |
            |Glasses right now:
            |
        """.trimMargin() + "\n" + glasses
    }

    companion object {
        const val MAX_MODEL_CALLS = 8
        const val MAX_CONTEXT_CHARS = 12 * 1024
        private val PRETTY = Json { prettyPrint = true }
    }
}
