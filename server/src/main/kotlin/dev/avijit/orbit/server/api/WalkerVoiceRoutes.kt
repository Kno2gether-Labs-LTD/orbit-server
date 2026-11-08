package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.agent.voice.VoiceService
import dev.avijit.orbithealth.sync.WalkerSpeakRequest
import dev.avijit.orbithealth.sync.WalkerVoicesResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Server voices for the OrbitWalker glasses, for device tokens. The phone plays this speech and falls back to its
 * own TTS on any error.
 *
 * - `GET /v1/walker/voices` → `{voices:[{id:"openai:coral", name, provider, providerName}], default}`: the voices of
 *   every configured provider (one whose list fails is left out); `default` is the dashboard's default voice when it
 *   is available, else the first voice, else null.
 * - `POST /v1/walker/speak {text, voice?}` → 200 MP3 bytes (`audio/mpeg`). `voice` null means `default`.
 *
 * Errors (`{"error": code}`): 400 `bad_request` (text empty after trimming or over
 * [dev.avijit.orbit.server.Limits.maxSpeakChars], or a malformed body), 404 `no_voice` (no provider configured),
 * 400 `unknown_voice` (not one of the listed voices), 502 `voice_error` (the provider failed or timed out), 429
 * `rate_limited` past [dev.avijit.orbit.server.Limits.walkerSpeakPerMinute] per device (both routes together), 413
 * for bodies over [dev.avijit.orbit.server.Limits.maxSpeakBytes]. Neither the text nor the audio is stored or logged.
 */
fun Route.walkerVoiceRoutes(deps: ServerDeps) {
    val limiter = RateLimiter(deps.limits.walkerSpeakPerMinute, 60_000, deps.clock)

    get("/v1/walker/voices") {
        if (!limiter.tryAcquire(call.device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val c = deps.voices.catalog()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(WalkerVoicesResponse(c.voices, c.defaultVoice))
    }

    post("/v1/walker/speak") {
        if (!limiter.tryAcquire(call.device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val req = call.receiveLimited(WalkerSpeakRequest.serializer(), deps.limits.maxSpeakBytes)
        val text = req.text.trim()
        if (text.isEmpty() || text.length > deps.limits.maxSpeakChars) throw ApiError(HttpStatusCode.BadRequest, "bad_request")
        when (val out = deps.voices.speak(text, req.voice?.trim()?.takeIf { it.isNotEmpty() })) {
            is VoiceService.Outcome.Spoken -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(out.audio.bytes, ContentType.parse(out.audio.contentType))
            }
            VoiceService.Outcome.NoVoice -> throw ApiError(HttpStatusCode.NotFound, "no_voice")
            VoiceService.Outcome.UnknownVoice -> throw ApiError(HttpStatusCode.BadRequest, "unknown_voice")
            is VoiceService.Outcome.Failed -> throw ApiError(HttpStatusCode.BadGateway, "voice_error")
        }
    }
}
