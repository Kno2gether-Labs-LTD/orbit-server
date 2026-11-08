package dev.avijit.orbit.server.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes

/**
 * The dashboard's static files, bundled in the jar under `/web/assets` and served from a fixed allow-list (no path
 * from the request ever reaches the class loader or the file system). Fonts are the app's OFL-licensed variable
 * Fraunces and Manrope, so the dashboard needs nothing from the internet.
 */
object Assets {
    private val TYPES = mapOf(
        "dashboard.css" to "text/css; charset=utf-8",
        "dashboard.js" to "text/javascript; charset=utf-8",
        "favicon.svg" to "image/svg+xml",
        "fonts/fraunces.ttf" to "font/ttf",
        "fonts/fraunces_italic.ttf" to "font/ttf",
        "fonts/manrope.ttf" to "font/ttf",
        "fonts/fraunces_ofl.txt" to "text/plain; charset=utf-8",
        "fonts/manrope_ofl.txt" to "text/plain; charset=utf-8",
    )

    private val cache: Map<String, ByteArray> by lazy {
        TYPES.keys.associateWith { name ->
            Assets::class.java.getResourceAsStream("/web/assets/$name")?.use { it.readBytes() }
                ?: error("missing bundled asset $name")
        }
    }

    suspend fun respond(call: ApplicationCall) {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val type = TYPES[path] ?: return call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondBytes(cache.getValue(path), ContentType.parse(type))
    }
}
