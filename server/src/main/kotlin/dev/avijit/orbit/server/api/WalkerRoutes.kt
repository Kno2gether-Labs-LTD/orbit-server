package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.DeviceRow
import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.ClientResult
import dev.avijit.orbit.server.agent.ProviderConfig
import dev.avijit.orbit.server.agent.ProviderException
import dev.avijit.orbit.server.agent.StoreDataTools
import dev.avijit.orbit.server.agent.ToolSpec
import dev.avijit.orbit.server.agent.WalkerAgent
import dev.avijit.orbit.server.agent.WalkerOutcome
import dev.avijit.orbit.server.agent.WalkerState
import dev.avijit.orbit.server.agent.WalkerTurns
import dev.avijit.orbithealth.sync.WalkerAskRequest
import dev.avijit.orbithealth.sync.WalkerCall
import dev.avijit.orbithealth.sync.WalkerContinueRequest
import dev.avijit.orbithealth.sync.WalkerReply
import dev.avijit.orbithealth.sync.WalkerToolSpec
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.time.ZoneId

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.api.Walker")

/**
 * The OrbitWalker (AR glasses) voice agent, for device tokens. A turn may span several HTTP calls:
 *
 * - `POST /v1/walker/ask {text, history?, context?, tools?, zone?}` starts a turn. The model sees the health tools
 *   plus the phone's client [WalkerAskRequest.tools]. Health tools run here, with the device's own scopes.
 * - When the model calls a client tool the reply is `{status:"tools", turn, calls, say?}`: the phone runs the calls
 *   and posts `POST /v1/walker/continue {turn, results}` (one result per call id, any order) within 120 s.
 * - `{status:"done", answer}` ends the turn.
 *
 * Errors: 400 `bad_question` (empty or over-long text), 400 `bad_tools` (see [validateTools]), 400 `bad_results`
 * (ids do not match the pending calls; the turn stays pending), 404 `unknown_turn` (unknown, used, or another
 * device's), 410 `turn_expired`, 409 `no_model`, 502 `model_error` (provider failure, or one HTTP call over
 * [dev.avijit.orbit.server.Limits.askTimeoutMs]), 503 `busy` (too many paused turns), 429 `rate_limited` past
 * [dev.avijit.orbit.server.Limits.walkerCallsPerMinute] per device (ask and continue together; separate from
 * `/v1/agent/ask`). Bodies over [dev.avijit.orbit.server.Limits.maxWalkerBytes] are 413.
 *
 * Each finished or failed turn writes one `walker_ask` access-log row with its outcome and call counts, never the
 * user's words or tool arguments. Health tool reads are logged per call under the device's name, as for the agent.
 */
fun Route.walkerRoutes(deps: ServerDeps) {
    val limiter = RateLimiter(deps.limits.walkerCallsPerMinute, 60_000, deps.clock)
    val turns = WalkerTurns(deps.clock) { t -> record(deps, t.deviceName, "abandoned", "-", t.state) }

    post("/v1/walker/ask") {
        val device = call.device
        if (!limiter.tryAcquire(device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val req = call.receiveLimited(WalkerAskRequest.serializer(), deps.limits.maxWalkerBytes)
        val text = req.text.trim()
        if (text.isEmpty() || text.length > deps.limits.maxQuestionChars) throw ApiError(HttpStatusCode.BadRequest, "bad_question")
        val zone = deps.userZone(req.zone)
        val health = healthTools(deps, device, zone)
        val clientTools = validateTools(req.tools, health.specs.map { it.name }.toSet())
            ?: throw ApiError(HttpStatusCode.BadRequest, "bad_tools")

        val turn = Turn(deps, device)
        try {
            val config = turn.config()
            val agent = WalkerAgent(deps.providerFactory.create(config), health, clientTools, deps.clock, zone)
            val state = agent.start(text, req.history, req.context)
            turn.state = state
            val outcome = turn.step(config) { agent.run(state) }
            call.respond(turn.reply(outcome, turns, clientTools, zone))
        } finally {
            turn.logUnlessPaused()
        }
    }

    post("/v1/walker/continue") {
        val device = call.device
        if (!limiter.tryAcquire(device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val req = call.receiveLimited(WalkerContinueRequest.serializer(), deps.limits.maxWalkerBytes)
        val ids = req.results.map { it.id }
        if (ids.toSet().size != ids.size) throw ApiError(HttpStatusCode.BadRequest, "bad_results")
        val pending = when (val c = turns.claim(req.turn, device.id) { t -> t.state.expectedCalls.map { it.id }.let { it.size == ids.size && it.toSet() == ids.toSet() } }) {
            is WalkerTurns.Claim.Ok -> c.turn
            WalkerTurns.Claim.Unknown -> throw ApiError(HttpStatusCode.NotFound, "unknown_turn")
            WalkerTurns.Claim.Rejected -> throw ApiError(HttpStatusCode.BadRequest, "bad_results")
            is WalkerTurns.Claim.Expired -> {
                record(deps, device.name, "expired", "-", c.turn.state)
                throw ApiError(HttpStatusCode.Gone, "turn_expired")
            }
        }
        val results = req.results.associate { it.id to ClientResult(it.content.take(MAX_RESULT_CHARS), it.isError) }
        val zone = ZoneId.of(pending.zoneId)

        val turn = Turn(deps, device).also { it.state = pending.state }
        try {
            val config = turn.config()
            val agent = WalkerAgent(deps.providerFactory.create(config), healthTools(deps, device, zone), pending.clientTools, deps.clock, zone)
            val outcome = turn.step(config) { agent.resume(pending.state, results) }
            call.respond(turn.reply(outcome, turns, pending.clientTools, zone))
        } finally {
            turn.logUnlessPaused()
        }
    }
}

/** 16 KB per client result; longer content is cut. */
const val MAX_RESULT_CHARS = 16 * 1024

private const val MAX_CLIENT_TOOLS = 40
private const val MAX_DESCRIPTION_CHARS = 1_000
private const val MAX_TOOLS_JSON_BYTES = 32 * 1024
private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,47}$")

/**
 * The phone's client tools as [ToolSpec]s, or null when they are unacceptable: more than 40, a name not matching
 * `^[a-z][a-z0-9_]{0,47}$`, a repeated name or one of the [reserved] health tool names, a description over 1000
 * characters, or over 32 KB of JSON in total.
 */
fun validateTools(tools: List<WalkerToolSpec>, reserved: Set<String>): List<ToolSpec>? {
    if (tools.size > MAX_CLIENT_TOOLS) return null
    val names = tools.map { it.name }
    if (names.toSet().size != names.size) return null
    if (tools.any { !TOOL_NAME.matches(it.name) || it.name in reserved || it.description.length > MAX_DESCRIPTION_CHARS }) return null
    val json = apiJson.encodeToString(ListSerializer(WalkerToolSpec.serializer()), tools)
    if (json.toByteArray(Charsets.UTF_8).size > MAX_TOOLS_JSON_BYTES) return null
    return tools.map { ToolSpec(it.name, it.description, it.parameters) }
}

private fun healthTools(deps: ServerDeps, device: DeviceRow, zone: ZoneId): AgentTools {
    val data = StoreDataTools(deps.store, deps.accessLog, Caller(device.name, device.scopes), zone, deps.clock)
    return AgentTools(data, zone, deps.clock)
}

private fun record(deps: ServerDeps, actor: String, outcome: String, model: String, state: WalkerState?) {
    val detail = "outcome=$outcome model=$model tool_calls=${state?.serverCalls ?: 0} client_calls=${state?.clientCalls ?: 0}"
    deps.accessLog.record(actor, "walker_ask", detail, 0)
}

/** One HTTP call's share of a walker turn: its outcome for the access log, and the reply. */
private class Turn(private val deps: ServerDeps, private val device: DeviceRow) {
    var outcome = "error"
    var model = "-"
    var state: WalkerState? = null
    private var paused = false

    fun config(): ProviderConfig {
        val config = deps.agentSettings.load()
        if (config == null) {
            outcome = "no_model"
            throw ApiError(HttpStatusCode.Conflict, "no_model")
        }
        model = config.model
        return config
    }

    /** Runs one HTTP call's worth of the loop under [dev.avijit.orbit.server.Limits.askTimeoutMs]. */
    suspend fun step(config: ProviderConfig, block: suspend () -> WalkerOutcome): WalkerOutcome {
        val out = try {
            withTimeoutOrNull(deps.limits.askTimeoutMs) { block() }
        } catch (e: ProviderException) {
            outcome = "model_error"
            log.warn("Walker provider {} failed: {}", config.kind.wire, e.message)
            throw ApiError(HttpStatusCode.BadGateway, "model_error")
        }
        if (out == null) {
            outcome = "timeout"
            log.warn("Walker call via {} timed out after {} ms", config.kind.wire, deps.limits.askTimeoutMs)
            throw ApiError(HttpStatusCode.BadGateway, "model_error")
        }
        return out
    }

    fun reply(out: WalkerOutcome, turns: WalkerTurns, clientTools: List<ToolSpec>, zone: ZoneId): WalkerReply {
        val s = state!!
        return when (out) {
            is WalkerOutcome.Done -> {
                model = out.model
                outcome = "ok"
                WalkerReply(WalkerReply.DONE, answer = out.answer, provenance = s.provenance.toList(), model = out.model)
            }
            is WalkerOutcome.Paused -> {
                model = out.model
                val id = turns.put(device.id, device.name, s, clientTools, zone.id)
                if (id == null) {
                    outcome = "busy"
                    throw ApiError(HttpStatusCode.ServiceUnavailable, "busy")
                }
                paused = true
                WalkerReply(
                    WalkerReply.TOOLS, turn = id, calls = out.calls.map { WalkerCall(it.id, it.name, it.args) }, say = out.say,
                    provenance = s.provenance.toList(), model = out.model,
                )
            }
        }
    }

    /** A paused turn is logged when it finishes, fails, expires or is evicted, not at each pause. */
    fun logUnlessPaused() {
        if (!paused) record(deps, device.name, outcome, model, state)
    }
}
