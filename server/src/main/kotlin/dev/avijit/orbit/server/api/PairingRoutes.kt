package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Pairing
import dev.avijit.orbithealth.sync.PairRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.api.Pairing")

private const val MAX_PAIR_BODY = 4 * 1024

/**
 * `POST /v1/pair {token, deviceName}` → 200 [dev.avijit.orbithealth.sync.PairResponse].
 * 400 malformed, 401 `invalid_pairing_token` (unknown, used or expired), 413 body too large,
 * 429 more than the per-IP attempt limit in a minute (every attempt counts).
 *
 * The limiter keys on [clientKey]: the TCP peer address (IPv6 by /64, see [RateLimiter.clientKey]), or behind a
 * trusted reverse proxy the client address it forwards (see [dev.avijit.orbit.server.ProxyMode]).
 */
fun Route.pairingRoutes(
    pairing: Pairing,
    limiter: RateLimiter,
    clientKey: (ApplicationCall) -> String = { RateLimiter.clientKey(it.request.origin.remoteAddress) },
) {
    post("/v1/pair") {
        if (!limiter.tryAcquire(clientKey(call))) {
            throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        }
        val req = call.receiveLimited(PairRequest.serializer(), MAX_PAIR_BODY)
        val resp = try {
            pairing.redeem(req.token, req.deviceName)
        } catch (e: IllegalArgumentException) {
            throw ApiError(HttpStatusCode.BadRequest, "bad_device_name")
        } ?: throw ApiError(HttpStatusCode.Unauthorized, "invalid_pairing_token")
        log.info("Paired device {}", resp.deviceId)
        call.respond(resp)
    }
}
