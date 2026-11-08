package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.DeviceRow
import dev.avijit.orbit.server.Secrets
import dev.avijit.orbit.server.Store
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal

/** The authenticated phone. */
data class DevicePrincipal(val device: DeviceRow)

const val DEVICE_AUTH = "device"

/**
 * `Authorization: Bearer <deviceToken>`. The token is hashed and looked up by hash (no string comparison of
 * secrets); unknown, malformed and revoked tokens all get the same 401. The device row is re-read on every request,
 * so revocation and scope changes take effect immediately.
 */
fun AuthenticationConfig.deviceBearer(store: Store) {
    bearer(DEVICE_AUTH) {
        realm = "orbit"
        authenticate { cred ->
            val token = cred.token
            if (!Secrets.looksLikeToken(token)) return@authenticate null
            store.deviceByTokenHash(Secrets.sha256Hex(token))?.takeUnless { it.revoked }?.let(::DevicePrincipal)
        }
    }
}

val ApplicationCall.device: DeviceRow get() = principal<DevicePrincipal>()!!.device
