package dev.avijit.orbit.server.agent

import dev.avijit.orbithealth.sync.AskResponse
import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.ToolUse
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The tool-using loop: ask the model, run the tools it calls, feed the results back, until it answers in text or
 * [maxSteps] model calls have been made. Every tool call is recorded as provenance (tool name and arguments).
 * The model never sees raw tables to do sums on: tools return computed values.
 */
class Agent(
    private val provider: ModelProvider,
    private val tools: AgentTools,
    private val maxSteps: Int = 6,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun ask(question: String, history: List<ChatTurn>): AskResponse {
        val messages = mutableListOf<Msg>(Msg.System(systemPrompt()))
        messages += history
            .filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY)
            .dropWhile { it.role != "user" } // the conversation starts with the user
            .map { t -> t.text.take(MAX_TURN_CHARS).let { if (t.role == "user") Msg.User(it) else Msg.Assistant(it) } }
        messages += Msg.User(question)

        val provenance = mutableListOf<ToolUse>()
        repeat(maxSteps) {
            when (val reply = provider.chat(messages, tools.specs)) {
                is ModelReply.Text -> return AskResponse(reply.text.trim().ifEmpty { EMPTY_ANSWER }, provenance, provider.model)
                is ModelReply.ToolCalls -> {
                    // Every call gets a result (providers reject unanswered calls); past the cap the result is an error.
                    messages += Msg.Assistant(reply.text, reply.calls, reply.raw)
                    reply.calls.forEachIndexed { i, c ->
                        if (i < MAX_CALLS_PER_STEP) {
                            provenance += ToolUse(c.name, c.args.toStringArgs())
                            val out = tools.call(c.name, c.args)
                            messages += Msg.ToolResult(c.id, c.name, out.content, out.isError)
                        } else {
                            messages += Msg.ToolResult(c.id, c.name, TOO_MANY_CALLS, isError = true)
                        }
                    }
                }
            }
        }
        return AskResponse(STEP_LIMIT_ANSWER, provenance, provider.model)
    }

    private fun systemPrompt(): String {
        val now = ZonedDateTime.now(clock.withZone(zone))
        return """
            You are Orbit, the assistant inside a person's self-hosted Orbit Server. You answer questions about their own
            smart-ring health data (heart rate, HRV, SpO2, stress, blood pressure, respiratory rate, steps, sleep) using the tools.

            - Take every number from a tool result. Do not do arithmetic yourself: for an average, total, minimum, maximum,
              daily series or comparison, call a tool with that aggregation and report what it returns.
            - Be concise and honest. Say which data you used (metric and dates).
            - If data is missing or a tool says it is not shared, say so plainly. Never guess or invent values.
            - Blood pressure and stress are estimates from the ring; always call them estimates.
            - You do not diagnose or give medical advice. For symptoms or worrying values, suggest speaking to a clinician.

            Now: ${now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))} (${zone.id}). "Last night" is the night that began yesterday.
        """.trimIndent()
    }

    companion object {
        const val MAX_HISTORY = 20
        const val MAX_TURN_CHARS = 4_000
        const val MAX_CALLS_PER_STEP = 8
        const val TOO_MANY_CALLS =
            """{"error":"too_many_calls","message":"too many tool calls in one step; ask fewer (at most $MAX_CALLS_PER_STEP)"}"""
        const val EMPTY_ANSWER = "Sorry, I don't have an answer for that."
        const val STEP_LIMIT_ANSWER =
            "Sorry, I couldn't finish working that out within my step limit. Try asking a narrower question."
    }
}
