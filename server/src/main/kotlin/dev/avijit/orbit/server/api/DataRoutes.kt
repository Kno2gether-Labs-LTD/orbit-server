package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.AgentTokenRow
import dev.avijit.orbit.server.CountingAccessLog
import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.agent.Agg
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.ScopeDenied
import dev.avijit.orbit.server.agent.StoreDataTools
import dev.avijit.orbithealth.sync.SleepDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * REST for scripts (agent token, same scopes, limits and access log as MCP; it reuses the same [StoreDataTools]):
 *
 * - `GET /v1/data/samples?metric=&from=&to=&agg=`: `from`/`to` are epoch milliseconds, ISO dates (`to` includes the
 *   whole day), local date-times in the user's zone (the phone's, from its latest ingest), offset date-times, `now`, `today` or `yesterday`. Defaults:
 *   the last 24 hours, `agg=raw`. Raw results over [REST_MAX_POINTS] come back aggregated, with a note.
 * - `GET /v1/data/sleep?from=&to=`: nights by the date each began (ISO date, `today`, `yesterday`), with stage
 *   totals and raw segments. Default: the last 7 nights.
 *
 * 400 `bad_request` (with a `message`), 403 `scope_denied`, 401 `invalid_token`, 429 `rate_limited`. Every
 * authenticated request leaves an access-log row under the token's name (DENIED / ERROR rows read nothing).
 */
fun Route.dataRoutes(deps: ServerDeps, gate: AgentGate) {
    get("/v1/data/samples") {
        val token = gate.require(call, "data_samples")
        call.withData(deps, token, action = "get_metric") { data, parse, zone ->
            val q = call.request.queryParameters
            val metric = q["metric"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("metric is required")
            val to = q["to"]?.let { instant(parse, it, end = true) } ?: deps.clock.instant()
            val from = q["from"]?.let { instant(parse, it, end = false) } ?: to.minus(Duration.ofHours(24))
            val agg = q["agg"]?.let { Agg.fromWire(it) ?: throw IllegalArgumentException("unknown agg '${it.take(20)}'") } ?: Agg.RAW
            val s = data.getMetric(metric, from, to, agg)
            buildJsonObject {
                put("metric", s.metric); put("unit", s.unit); put("estimate", s.estimate); put("aggregation", s.agg)
                put("fromMs", s.fromMs); put("toMs", s.toMs); put("timezone", zone.id); put("rows", s.rows)
                s.note?.let { put("note", it) }
                putJsonArray("points") {
                    for (p in s.points) addJsonObject {
                        put("epochMs", p.epochMs); put("time", iso(zone, p.epochMs)); put("value", p.value); put("n", p.count)
                    }
                }
            }
        }
    }

    get("/v1/data/sleep") {
        val token = gate.require(call, "data_sleep")
        call.withData(deps, token, action = "get_sleep_range") { data, parse, zone ->
            val q = call.request.queryParameters
            val to = q["to"]?.takeIf { it.isNotBlank() }?.let(parse::parseDate) ?: parse.parseDate("yesterday")
            val from = q["from"]?.takeIf { it.isNotBlank() }?.let(parse::parseDate) ?: to.minusDays(6)
            val nights = data.getSleepRange(from, to)
            buildJsonObject {
                put("from", from.toString()); put("to", to.toString()); put("timezone", zone.id)
                putJsonArray("nights") {
                    for (n in nights) addJsonObject {
                        val s = n.summary
                        put("night", s.night); put("startMs", s.startMs); put("endMs", s.endMs)
                        put("start", iso(zone, s.startMs)); put("end", iso(zone, s.endMs))
                        put("inBedMin", s.inBedMin); put("asleepMin", s.asleepMin)
                        putJsonObject("stagesMin") { s.stagesMin.forEach { (k, v) -> put(k, v) } }
                        put("sessions", Json.encodeToJsonElement(ListSerializer(SleepDto.serializer()), n.sessions))
                    }
                }
            }
        }
    }
}

/** Larger than the agent's cap: scripts export data, models read summaries. */
const val REST_MAX_POINTS = 10_000

private fun instant(parse: AgentTools, s: String, end: Boolean): Instant {
    val t = s.trim()
    if (t.isNotEmpty() && t.length <= 15 && t.all(Char::isDigit)) return Instant.ofEpochMilli(t.toLong())
    return parse.parseTime(t, end)
}

private fun iso(zone: ZoneId, ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().toString()

/**
 * Runs [block] with tools bound to [token] and maps failures: scope → 403 (DataTools logged the DENIED row),
 * bad input → 400 with a row added here when nothing was logged yet.
 */
private suspend fun ApplicationCall.withData(
    deps: ServerDeps,
    token: AgentTokenRow,
    action: String,
    block: (StoreDataTools, AgentTools, ZoneId) -> JsonObject,
) {
    val counting = CountingAccessLog(deps.accessLog, deps.store, deps.clock)
    val zone = deps.userZone() // the phone's zone from its latest ingest, so "today" is the user's day
    val data = StoreDataTools(deps.store, counting, Caller(token.name, token.scopes), zone, deps.clock, maxPoints = REST_MAX_POINTS)
    val parse = AgentTools(data, zone, deps.clock)
    val (status, body) = try {
        HttpStatusCode.OK to block(data, parse, zone)
    } catch (e: ScopeDenied) {
        HttpStatusCode.Forbidden to buildJsonObject { put("error", "scope_denied") }
    } catch (e: IllegalArgumentException) {
        badRequest(deps, token, counting, action, e.message ?: "invalid parameters")
    } catch (e: DateTimeException) {
        badRequest(deps, token, counting, action, "invalid date or time")
    }
    respondText(body.toString(), ContentType.Application.Json, status)
}

private fun badRequest(deps: ServerDeps, token: AgentTokenRow, counting: CountingAccessLog, action: String, message: String): Pair<HttpStatusCode, JsonObject> {
    if (counting.count == 0) deps.accessLog.record(token.name, action, "ERROR bad_request", 0)
    return HttpStatusCode.BadRequest to buildJsonObject { put("error", "bad_request"); put("message", message.take(200)) }
}
