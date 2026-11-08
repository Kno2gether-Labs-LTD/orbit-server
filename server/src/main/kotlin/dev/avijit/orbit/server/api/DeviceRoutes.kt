package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.AccessLog
import dev.avijit.orbit.server.Store
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.ScopesUpdate
import dev.avijit.orbithealth.sync.StateResponse
import dev.avijit.orbithealth.sync.scope
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.api.Devices")

/**
 * Device-authenticated endpoints:
 * - `GET /v1/state`: last stored timestamp per metric and last sleep start, limited to the device's scopes.
 * - `PATCH /v1/devices/me/scopes` `{scopes:[...]}`: replaces the scope set (exact wire names; empty pauses sync).
 * - `DELETE /v1/devices/me`: revokes this device (204); its token stops working at once.
 * - `GET /v1/access-log?limit=N`: agent reads, newest first; N in 1..500, default 100.
 */
fun Route.deviceRoutes(store: Store, accessLog: AccessLog) {
    get("/v1/state") {
        val scopes = call.device.scopes.mapNotNull(Scope::fromWire).toSet()
        val marks = store.latestEpochByMetric().filterKeys { m ->
            Metric.entries.firstOrNull { it.name == m }?.scope() in scopes
        }
        val sleep = if (Scope.SLEEP in scopes) store.lastSleepStartMs() else null
        call.respond(StateResponse(marks, sleep))
    }

    patch("/v1/devices/me/scopes") {
        val req = call.receiveLimited(ScopesUpdate.serializer(), 4 * 1024)
        if (req.scopes.any { Scope.fromWire(it) == null }) throw ApiError(HttpStatusCode.BadRequest, "unknown_scope")
        store.updateDeviceScopes(call.device.id, req.scopes)
        call.respond(ScopesUpdate(req.scopes))
    }

    delete("/v1/devices/me") {
        store.revokeDevice(call.device.id)
        log.info("Device {} revoked itself", call.device.id)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/v1/access-log") {
        val raw = call.request.queryParameters["limit"]
        val limit = if (raw == null) AccessLog.DEFAULT_LIMIT else raw.toIntOrNull()?.takeIf { it in 1..AccessLog.MAX_LIMIT }
            ?: throw ApiError(HttpStatusCode.BadRequest, "bad_limit")
        call.respond(accessLog.recent(limit))
    }
}
