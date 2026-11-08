package dev.avijit.orbit.server

import dev.avijit.orbit.server.api.ApiError
import dev.avijit.orbit.server.api.DEVICE_AUTH
import dev.avijit.orbit.server.api.Ingest
import dev.avijit.orbit.server.api.RateLimiter
import dev.avijit.orbit.server.agent.AgentSettings
import dev.avijit.orbit.server.agent.ConversationStore
import dev.avijit.orbit.server.agent.voice.DefaultVoiceProviderFactory
import dev.avijit.orbit.server.agent.voice.VoiceHttp
import dev.avijit.orbit.server.agent.voice.VoiceProviderFactory
import dev.avijit.orbit.server.agent.voice.VoiceService
import dev.avijit.orbit.server.agent.voice.VoiceSettings
import dev.avijit.orbit.server.api.walkerVoiceRoutes
import dev.avijit.orbit.server.agent.DefaultProviderFactory
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.agent.ProviderHttp
import dev.avijit.orbit.server.api.AgentGate
import dev.avijit.orbit.server.api.agentRoutes
import dev.avijit.orbit.server.api.dataRoutes
import dev.avijit.orbit.server.api.mcpRoutes
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import dev.avijit.orbit.server.api.deviceBearer
import dev.avijit.orbit.server.api.deviceRoutes
import dev.avijit.orbit.server.api.ingestRoutes
import dev.avijit.orbit.server.api.pairingRoutes
import dev.avijit.orbit.server.api.walkerRoutes
import dev.avijit.orbit.server.web.Dashboard
import dev.avijit.orbit.server.web.SecurityHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZoneId

@Serializable data class HealthResponse(val ok: Boolean, val fingerprint: String?)
@Serializable data class ErrorResponse(val error: String)

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.Server")

/** Request-size and rate limits. Defaults are the production values; tests shrink them. */
data class Limits(
    val maxIngestBytes: Int = 2 * 1024 * 1024,
    val maxSamples: Int = 50_000,
    val maxSleepSessions: Int = 1_000,
    val maxSegments: Int = 50_000,
    val pairAttemptsPerMinute: Int = 10,
    /** `/v1/agent/ask` per device: bounds model spend if a phone (or a stolen token) loops. */
    val asksPerMinute: Int = 20,
    val maxAskBytes: Int = 64 * 1024,
    /**
     * Whole-ask deadline (all model calls and tools). Below the phone's 180 s timeout, so the phone gets a clear 502
     * `model_error` instead of a dropped connection.
     */
    val askTimeoutMs: Long = 150_000,
    /** `/v1/ingest` per device: generous for chunked catch-up syncs, but bounds a looping phone or stolen token. */
    val ingestsPerMinute: Int = 60,
    val maxQuestionChars: Int = 2_000,
    /** Per agent token, shared by MCP and the data API. */
    val agentRequestsPerMinute: Int = 120,
    val maxMcpBytes: Int = 64 * 1024,
    /** JSON-RPC batch length cap; each extra tools/call in a batch is also charged to the token's budget. */
    val maxMcpBatch: Int = 10,
    /** Failed dashboard logins per client address (IPv6 by /64) in 15 minutes; successful logins are not counted. */
    val loginFailuresPer15Min: Int = 5,
    /** `/v1/walker/ask` + `/v1/walker/continue` per device; its own budget, so glasses never use up the ring app's asks. */
    val walkerCallsPerMinute: Int = 60,
    val maxWalkerBytes: Int = 128 * 1024,
    /** `/v1/walker/speak` + `/v1/walker/voices` per device: each speak is a paid provider call. */
    val walkerSpeakPerMinute: Int = 30,
    val maxSpeakChars: Int = 1_500,
    val maxSpeakBytes: Int = 16 * 1024,
)

/**
 * Everything the HTTP module needs. [masterKey] encrypts model API keys; [zone] defines local days for the agent's
 * tools; [env] is the process environment (tests pass their own); [providerFactory] builds model providers
 * (default: real Anthropic / OpenAI-compatible clients over one shared Ktor CIO client).
 */
class ServerDeps(
    val store: Store,
    val fingerprint: String?,
    val masterKey: MasterKey? = null,
    val clock: Clock = Clock.systemUTC(),
    val limits: Limits = Limits(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val env: Map<String, String> = System.getenv(),
    providerFactory: ProviderFactory? = null,
    /** Extra host names a browser `Origin` may carry for `/mcp` (the server's own names); see [dev.avijit.orbit.server.api.OriginPolicy]. */
    val originHosts: Set<String> = emptySet(),
    /** `https://host:port` phones and agents should use (pairing QR, MCP snippets); null: the browser's own address. */
    val publicUrl: String? = null,
    /** The public TLS certificate (PEM) offered for download on the agent-tokens page. */
    val certPem: String? = null,
    /** Set for `run --behind-proxy`: forwarded-header trust and the public URL (pairing then uses trust mode `ca`). */
    val proxy: ProxyMode? = null,
    /** Builds text-to-speech providers (default: the real ones over a shared client with 20 s timeouts). */
    voiceProviderFactory: VoiceProviderFactory? = null,
) {
    val pairing = Pairing(store, clock)
    val setupCode = SetupCode(store, clock)
    val accessLog = AccessLog(store, clock)
    val agentSettings = AgentSettings(store, masterKey, env)
    val agentTokens = AgentTokens(store, clock)
    val conversations = ConversationStore(store)
    private val http by lazy { ProviderHttp.client() }
    val providerFactory: ProviderFactory = providerFactory ?: DefaultProviderFactory({ http }, clock, zone)
    val voiceSettings = VoiceSettings(store, masterKey)
    private val voiceHttp by lazy { VoiceHttp.client() }
    val voices = VoiceService(voiceSettings, voiceProviderFactory ?: DefaultVoiceProviderFactory { voiceHttp }, clock)

    /**
     * The user's zone for local days: [requested] (the phone's zone on this request) when valid, else the zone from the
     * most recent ingest of an active phone (stored on its device row), else the older global [PhoneZone.SETTING],
     * else the server's [zone]. Used by `/v1/agent/ask`, MCP, the data API and the dashboard.
     */
    fun userZone(requested: String? = null): ZoneId =
        PhoneZone.parse(requested) ?: PhoneZone.parse(store.latestDeviceZone()) ?: PhoneZone.parse(store.getSetting(PhoneZone.SETTING)) ?: zone
}

/** Validation for the IANA zone ids phones send (`AskRequest.zone`, `IngestBatch.zone`). */
object PhoneZone {
    /** Read only as a fallback: databases from before the zone moved onto the device row. */
    const val SETTING = "user.zone"
    private const val MAX_LEN = 64

    /** A region id the JDK knows ("Europe/London"), else null. Offsets and junk are refused; never throws. */
    fun parse(id: String?): ZoneId? {
        if (id.isNullOrBlank() || id.length > MAX_LEN || id !in ZoneId.getAvailableZoneIds()) return null
        return try {
            ZoneId.of(id)
        } catch (e: Exception) {
            null
        }
    }
}

/** Routes and plugins. Later tasks add agent, MCP and the dashboard here. */
fun Application.orbitModule(deps: ServerDeps) {
    install(SecurityHeaders)
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    install(Authentication) { deviceBearer(deps.store) }
    install(StatusPages) {
        exception<ApiError> { call, cause ->
            if (cause.status == HttpStatusCode.Unauthorized) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"orbit\"")
            call.respond(cause.status, ErrorResponse(cause.code))
        }
        exception<Throwable> { call, cause ->
            // Only the exception type and path: messages can carry request data (tokens, health values).
            log.error("Unhandled {} on {}", cause::class.java.simpleName, call.request.path())
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    val pairLimiter = RateLimiter(deps.limits.pairAttemptsPerMinute, 60_000, deps.clock)
    val ingest = Ingest(deps.store, deps.clock, deps.limits)
    val askLimiter = RateLimiter(deps.limits.asksPerMinute, 60_000, deps.clock)
    val ingestLimiter = RateLimiter(deps.limits.ingestsPerMinute, 60_000, deps.clock)
    val agentGate = AgentGate(deps.agentTokens, deps.accessLog, RateLimiter(deps.limits.agentRequestsPerMinute, 60_000, deps.clock))
    routing {
        get("/health") { call.respond(HealthResponse(ok = true, fingerprint = deps.fingerprint)) }
        pairingRoutes(deps.pairing, pairLimiter) { deps.clientKey(it) }
        // Agent-token routes authenticate inside the handler (after the MCP Origin check).
        mcpRoutes(deps, agentGate)
        dataRoutes(deps, agentGate)
        Dashboard(deps).install(this)
        authenticate(DEVICE_AUTH) {
            ingestRoutes(ingest, deps.limits, ingestLimiter)
            deviceRoutes(deps.store, deps.accessLog)
            agentRoutes(deps, askLimiter)
            walkerRoutes(deps)
            walkerVoiceRoutes(deps)
        }
    }
}

/**
 * HTTPS on [RunConfig.bind]:[RunConfig.port] with [tls]. Plain HTTP only for [RunConfig.insecureDev] (tests) and
 * [RunConfig.behindProxy] (a TLS-terminating reverse proxy in front, on an internal network).
 */
fun createServer(cfg: RunConfig, tls: TlsMaterial?, deps: ServerDeps): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    require(cfg.insecureDev || cfg.behindProxy || tls != null) { "TLS material is required unless --insecure-dev or --behind-proxy" }
    require(!cfg.behindProxy || deps.proxy != null) { "--behind-proxy needs the proxy settings" }
    return embeddedServer(Netty, configure = {
        if (cfg.insecureDev || cfg.behindProxy) {
            connector { host = cfg.bind; port = cfg.port }
        } else {
            val t = tls!!
            sslConnector(
                keyStore = t.keyStore,
                keyAlias = t.alias,
                keyStorePassword = { t.password.copyOf() },
                privateKeyPassword = { t.password.copyOf() },
            ) { host = cfg.bind; port = cfg.port }
        }
    }) { orbitModule(deps) }
}
