package dev.avijit.orbit.server.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * A deterministic stand-in model for tests and the debug end-to-end run (enabled only with `ORBIT_SCRIPTED_AGENT=1`).
 *
 * For a new question it calls one tool: `get_sleep(last night)` when the question mentions sleep, otherwise
 * `get_metric(HEART_RATE, last 24 h, avg)`. Once the tool result arrives it answers with the numbers from that
 * result, word for word, and never computes anything itself.
 */
class ScriptedProvider(private val clock: Clock, private val zone: ZoneId) : ModelProvider {
    override val model = MODEL

    override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        val lastUser = messages.indexOfLast { it is Msg.User }
        val result = messages.drop(lastUser + 1).filterIsInstance<Msg.ToolResult>().lastOrNull()
        if (result != null) return ModelReply.Text(answer(result))

        val question = (messages.getOrNull(lastUser) as? Msg.User)?.text.orEmpty().lowercase()
        val call = if (SLEEP_WORDS.any { it in question }) {
            ToolCall("scripted_1", "get_sleep", buildJsonObject { put("night", LocalDate.now(clock.withZone(zone)).minusDays(1).toString()) })
        } else {
            val now = LocalDateTime.now(clock.withZone(zone))
            ToolCall("scripted_1", "get_metric", buildJsonObject {
                put("metric", "HEART_RATE")
                put("from", now.minus(Duration.ofHours(24)).format(MINUTES))
                put("to", now.format(MINUTES))
                put("agg", "avg")
            })
        }
        return ModelReply.ToolCalls(listOf(call))
    }

    private fun answer(r: Msg.ToolResult): String {
        val o = try { Json.parseToJsonElement(r.content) as? JsonObject } catch (e: Exception) { null }
            ?: return "Sorry, I couldn't read your data just now."
        if (r.isError) {
            val scope = o.s("scope")
            return if (o.s("error") == "scope_denied" && scope != null) {
                "I don't have access to your $scope data. You can share it in the app under Agent & sync."
            } else if (o.s("error") == "scope_denied") {
                "No health data has been shared with me. You can share it in the app under Agent & sync."
            } else {
                "Sorry, I couldn't read your data just now."
            }
        }
        return when (r.name) {
            "get_sleep" -> sleepAnswer(o)
            "get_metric" -> metricAnswer(o)
            else -> "I looked at your data (${r.name}) but have nothing to add."
        }
    }

    private fun sleepAnswer(o: JsonObject): String {
        val night = o.s("night") ?: "last night"
        if (o.s("found") != "true") return "I couldn't find any sleep recorded for the night of $night."
        val asleep = o.s("asleep_min")?.toLongOrNull() ?: 0
        val stages = (o["stages_min"] as? JsonObject).orEmpty().entries
            .sortedBy { STAGE_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
            .joinToString(", ") { (k, v) -> "${k.lowercase()} ${(v as? JsonPrimitive)?.content} min" }
        val start = o.s("start")?.substringAfter('T')
        val end = o.s("end")?.substringAfter('T')
        return "On the night of $night you slept ${asleep / 60} h ${asleep % 60} min ($stages), " +
            "from $start to $end. Source: get_sleep."
    }

    private fun metricAnswer(o: JsonObject): String {
        val point = (o["points"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return "I couldn't find any heart-rate readings in the last 24 hours."
        val value = (point["value"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return "I couldn't find any heart-rate readings in the last 24 hours."
        val n = point.s("n") ?: "?"
        return "Your average heart rate over the last 24 hours was ${fmt(value)} ${o.s("unit") ?: "bpm"}, from $n readings. Source: get_metric."
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content

    private fun fmt(v: Double): String = if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else v.toString()

    companion object {
        const val MODEL = "scripted"
        private val SLEEP_WORDS = listOf("sleep", "slept", "night", "bed")
        private val STAGE_ORDER = listOf("DEEP", "LIGHT", "REM", "AWAKE")
        private val MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}
