package dev.avijit.orbit.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** An error the API reports as `{"error": code}` with [status]. Messages never include request data. */
class ApiError(val status: HttpStatusCode, val code: String) : RuntimeException(code)

/** Lenient on unknown keys (newer apps may send more), strict on types and required fields. */
val apiJson = Json { ignoreUnknownKeys = true }

/**
 * Reads at most [maxBytes] of body and decodes it with [strategy]. A larger body is refused with 413 without
 * buffering it all; undecodable JSON is 400.
 */
suspend fun <T> ApplicationCall.receiveLimited(strategy: DeserializationStrategy<T>, maxBytes: Int): T {
    val text = receiveTextLimited(maxBytes)
    return try {
        apiJson.decodeFromString(strategy, text)
    } catch (e: SerializationException) {
        throw ApiError(HttpStatusCode.BadRequest, "bad_request")
    } catch (e: IllegalArgumentException) {
        throw ApiError(HttpStatusCode.BadRequest, "bad_request")
    }
}

/** The body as UTF-8 text, reading at most [maxBytes]; a larger body is 413 without buffering it all. */
suspend fun ApplicationCall.receiveTextLimited(maxBytes: Int): String {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
        if (it > maxBytes) throw ApiError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
    }
    val bytes = receiveChannel().readRemaining(maxBytes + 1L).readByteArray()
    if (bytes.size > maxBytes) throw ApiError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
    return bytes.toString(Charsets.UTF_8)
}
