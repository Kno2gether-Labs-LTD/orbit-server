package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.agent.Agent
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.ProviderException
import dev.avijit.orbit.server.agent.StoreDataTools
import dev.avijit.orbithealth.sync.AskRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.api.Agent")

/**
 * `POST /v1/agent/ask {question, history?}` (device token) → `AskResponse {answer, provenance, model}`.
 *
 * - 409 `no_model` when no model is configured (set one in the dashboard).
 * - 502 `model_error` when the provider fails; the log names only the failure kind, never the key or a body.
 * - 502 `model_error` (logged as outcome=timeout) when the whole ask takes over [dev.avijit.orbit.server.Limits.askTimeoutMs].
 * - 400 `bad_question` for an empty or over-long question; history is trimmed to the last turns, not rejected.
 * - 429 `rate_limited` past [dev.avijit.orbit.server.Limits.asksPerMinute] per device.
 *
 * Local days use the request's `zone` (the phone's IANA id) when valid, else the zone from the phone's last ingest,
 * else the server's. Tools run with the device's own scopes and log each read under the device's name. The ask itself is logged
 * with its outcome (ok, no_model, model_error, timeout or error), whether it succeeded or not.
 */
fun Route.agentRoutes(deps: ServerDeps, limiter: RateLimiter) {
    post("/v1/agent/ask") {
        val device = call.device
        if (!limiter.tryAcquire(device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val req = call.receiveLimited(AskRequest.serializer(), deps.limits.maxAskBytes)
        val question = req.question.trim()
        if (question.isEmpty() || question.length > deps.limits.maxQuestionChars) throw ApiError(HttpStatusCode.BadRequest, "bad_question")

        var outcome = "error"
        var model = "-"
        var toolCalls = 0
        try {
            val config = deps.agentSettings.load()
            if (config == null) {
                outcome = "no_model"
                throw ApiError(HttpStatusCode.Conflict, "no_model")
            }
            model = config.model
            val provider = deps.providerFactory.create(config)
            val zone = deps.userZone(req.zone) // the phone's zone, so "today" and "last night" are the user's days
            val data = StoreDataTools(deps.store, deps.accessLog, Caller(device.name, device.scopes), zone, deps.clock)
            val agent = Agent(provider, AgentTools(data, zone, deps.clock), clock = deps.clock, zone = zone)
            val answer = try {
                withTimeoutOrNull(deps.limits.askTimeoutMs) { agent.ask(question, req.history) }
            } catch (e: ProviderException) {
                outcome = "model_error"
                log.warn("Agent provider {} failed: {}", config.kind.wire, e.message)
                throw ApiError(HttpStatusCode.BadGateway, "model_error")
            }
            if (answer == null) {
                outcome = "timeout"
                log.warn("Agent ask via {} timed out after {} ms", config.kind.wire, deps.limits.askTimeoutMs)
                throw ApiError(HttpStatusCode.BadGateway, "model_error")
            }
            model = answer.model
            toolCalls = answer.provenance.size
            outcome = "ok"
            call.respond(answer)
        } finally {
            // Every ask that reached the model step is logged, failed ones included.
            deps.accessLog.record(device.name, "agent_ask", "outcome=$outcome model=$model tool_calls=$toolCalls", 0)
        }
    }
}
