package dev.avijit.orbit.server.web

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.avijit.orbit.server.Pairing
import dev.avijit.orbit.server.Qr
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbit.server.Secrets
import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.Settings
import dev.avijit.orbit.server.SetupCode
import dev.avijit.orbit.server.clientKey
import dev.avijit.orbit.server.agent.Agent
import dev.avijit.orbit.server.agent.AgentSettings
import dev.avijit.orbit.server.agent.AgentTools
import dev.avijit.orbit.server.agent.Caller
import dev.avijit.orbit.server.agent.ProviderException
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbit.server.agent.StoreDataTools
import dev.avijit.orbit.server.api.ApiError
import dev.avijit.orbit.server.api.RateLimiter
import dev.avijit.orbit.server.api.receiveTextLimited
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SleepDto
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.HTML
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val log = LoggerFactory.getLogger("dev.avijit.orbit.server.web.Dashboard")

/**
 * Security headers on every response (dashboard, assets and API alike). The dashboard makes no external requests:
 * fonts, CSS and JS are served from this server, and the CSP forbids anything else, including inline script and style.
 *
 * Referrer-Policy is `same-origin`, not `no-referrer`: with `no-referrer` browsers send `Origin: null` on same-origin
 * form posts, which would defeat the Origin check below. `same-origin` still sends nothing to any other site.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        val h = call.response.headers
        h.append("Content-Security-Policy", Dashboard.CSP)
        h.append("X-Content-Type-Options", "nosniff")
        h.append("Referrer-Policy", "same-origin")
        h.append("X-Frame-Options", "DENY")
        h.append("Cross-Origin-Opener-Policy", "same-origin")
        h.append("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()")
    }
}

/**
 * The admin dashboard: server-rendered pages behind the admin password.
 *
 * - Sessions: [SessionStore] (256-bit ids, 30 min idle, 12 h absolute), cookie `__Host-orbit_session`
 *   (HttpOnly, Secure, SameSite=Strict, Path=/). The id is replaced on login; logout ends it server-side.
 * - CSRF: every POST (login included) needs the session's token in `_csrf` and a same-origin `Origin` (or, when a
 *   browser sends no Origin, `Referer`).
 * - Login: bcrypt verify, [dev.avijit.orbit.server.Limits.loginFailuresPer15Min] failures per client address
 *   (IPv6 by /64) per 15 minutes; successful attempts are refunded.
 * - First run: until an admin password exists every page redirects to `/setup`, which takes the one-time
 *   [dev.avijit.orbit.server.SetupCode] and a new password (same CSRF, origin and rate-limit rules as login).
 * - Behind a reverse proxy ([ServerDeps.proxy]): POSTs need `X-Forwarded-Proto: https` from a trusted proxy, the
 *   Origin must be the public URL, and limits key on the forwarded client address.
 */
class Dashboard(private val deps: ServerDeps) {
    val sessions = SessionStore(deps.clock)
    private val loginLimiter = RateLimiter(deps.limits.loginFailuresPer15Min, 15 * 60_000L, deps.clock)
    private val setupLimiter = RateLimiter(deps.limits.loginFailuresPer15Min, 15 * 60_000L, deps.clock)
    private val testLimiter = RateLimiter(10, 60_000L, deps.clock)
    init {
        // Expired and used pairing tokens are also pruned whenever a new one is minted (Pairing.newToken).
        deps.store.purgePairTokens(deps.clock.millis())
    }

    /** The user's zone (the phone's, from its latest ingest), read per use so a new ingest takes effect at once. */
    private val zone: java.time.ZoneId get() = deps.userZone()
    private val fmt get() = Fmt(zone) { deps.clock.millis() }
    private val serverName get() = deps.store.getSetting(Settings.SERVER_NAME) ?: Pairing.DEFAULT_SERVER_NAME

    companion object {
        const val COOKIE = "__Host-orbit_session"
        const val CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self'; " +
            "connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'"
        const val ACTOR = "dashboard"
        const val PAGE_SIZE = 50
        private const val MAX_FORM = 16 * 1024
        /** Device and agent-token ids are UUIDs; anything else is refused before it reaches the store or a log line. */
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val MAX_PASSWORD = 200
        private const val MIN_PASSWORD = dev.avijit.orbit.server.Cli.MIN_PASSWORD
        private const val BCRYPT_COST = dev.avijit.orbit.server.Cli.BCRYPT_COST
    }

    // ------------------------------------------------------------------ sessions and CSRF

    private fun adminHash(): String? = deps.store.getSetting(Settings.ADMIN_HASH)
    private fun setupPending(): Boolean = adminHash() == null
    private fun adminTag(): String? = adminHash()?.let { Secrets.sha256Hex(it).take(16) }

    fun session(call: ApplicationCall): DashSession? = sessions.get(call.request.cookies[COOKIE], adminTag())

    private fun ApplicationCall.setCookie(id: String) =
        response.headers.append(HttpHeaders.SetCookie, "$COOKIE=$id; Path=/; Secure; HttpOnly; SameSite=Strict")

    private fun ApplicationCall.clearCookie() =
        response.headers.append(HttpHeaders.SetCookie, "$COOKIE=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict")

    /**
     * True when the request's Origin (or, without one, its Referer) is this server as the browser addressed it:
     * the public URL behind a proxy, else the scheme and Host of this request. Behind a proxy the request must also
     * have reached the proxy over https (said by a trusted proxy), or it is refused.
     */
    private fun ApplicationCall.sameOrigin(): Boolean {
        val proxy = deps.proxy
        if (proxy != null && !proxy.forwardedHttps(this)) return false
        val self = proxy?.publicOrigin ?: "${request.origin.scheme}://${hostHeader()}"
        val origin = request.headers[HttpHeaders.Origin]
        if (origin != null) return origin.equals(self, ignoreCase = true)
        val referer = request.headers[HttpHeaders.Referrer] ?: return false
        return referer.equals(self, ignoreCase = true) || referer.startsWith("$self/", ignoreCase = true)
    }

    /** The Host the browser used (`host[:port]`); engines that omit the header fall back to the server address. */
    private fun ApplicationCall.hostHeader(): String = request.headers[HttpHeaders.Host] ?: request.origin.let { o ->
        val default = (o.scheme == "http" && o.serverPort == 80) || (o.scheme == "https" && o.serverPort == 443)
        if (default) o.serverHost else "${o.serverHost}:${o.serverPort}"
    }

    private suspend fun ApplicationCall.readForm(): Parameters {
        val ct = request.contentType()
        if (!ct.match(ContentType.Application.FormUrlEncoded)) throw ApiError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        return parseQueryString(receiveTextLimited(MAX_FORM))
    }

    private suspend fun ApplicationCall.forbidden() {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondHtml(HttpStatusCode.Forbidden) {
            Pages.message(this, "Request refused", "This form had expired or did not come from this dashboard. Reload the page and try again.")
        }
    }

    /** The signed-in session and the verified form of a POST, or null after responding 403. */
    private suspend fun ApplicationCall.authedPost(): Pair<DashSession, Parameters>? {
        if (setupPending()) { forbidden(); return null }
        val s = session(this)
        if (s == null || !s.authenticated || !sameOrigin()) { forbidden(); return null }
        val form = readForm()
        if (!s.csrfMatches(form["_csrf"])) { forbidden(); return null }
        return s to form
    }

    /** The signed-in session for a GET, or null after redirecting to the login (or, before setup, setup) page. */
    private suspend fun ApplicationCall.authedGet(): DashSession? {
        if (setupPending()) { seeOther("/setup"); return null }
        val s = session(this)
        if (s == null || !s.authenticated) { seeOther("/login"); return null }
        return s
    }

    private suspend fun ApplicationCall.seeOther(path: String) {
        response.header(HttpHeaders.Location, path)
        response.header(HttpHeaders.CacheControl, "no-store")
        respond(HttpStatusCode.SeeOther)
    }

    private suspend fun ApplicationCall.page(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondHtml(status, block)
    }

    private fun chrome(s: DashSession, active: String) = Chrome(serverName, s.csrf, active)

    // ------------------------------------------------------------------ routes

    fun install(route: Route) = with(route) {
        get("/assets/{path...}") { Assets.respond(call) }

        get("/setup") {
            if (!setupPending()) return@get call.seeOther("/login")
            val s = session(call) ?: sessions.create(false, null).let { (id, s) -> call.setCookie(id); s }
            call.page { Pages.setup(this, s.csrf, null, false, serverName) }
        }

        post("/setup") {
            if (!setupPending()) return@post call.seeOther("/login")
            val old = call.request.cookies[COOKIE]
            val s = session(call)
            if (s == null || s.authenticated || !call.sameOrigin()) return@post call.forbidden()
            val form = call.readForm()
            if (!s.csrfMatches(form["_csrf"])) return@post call.forbidden()
            val key = deps.clientKey(call)
            if (!setupLimiter.tryAcquire(key)) {
                log.warn("Setup refused: too many attempts")
                return@post call.page(HttpStatusCode.TooManyRequests) { Pages.setup(this, s.csrf, null, true, serverName) }
            }
            val password = form["password"].orEmpty()
            val problem = when {
                password != form["confirm"].orEmpty() -> "The passwords do not match."
                password.length < MIN_PASSWORD -> "Choose a password of at least $MIN_PASSWORD characters."
                password.toByteArray(Charsets.UTF_8).size > 72 ->
                    "That password is longer than 72 bytes, and bcrypt would ignore the rest. Choose a shorter one."
                else -> null
            }
            if (problem != null) {
                setupLimiter.refund(key) // not a guess at the code
                return@post call.page(HttpStatusCode.BadRequest) { Pages.setup(this, s.csrf, problem, false, serverName) }
            }
            val code = form["code"].orEmpty()
            val badCode = "That setup code did not match, or it has expired. Run orbit-server setup-code for a new one."
            // Cheap check first, so wrong codes never cost a bcrypt hash.
            if (!deps.setupCode.matches(code)) {
                log.info("Setup attempt with a wrong or expired code")
                return@post call.page(HttpStatusCode.Unauthorized) { Pages.setup(this, s.csrf, badCode, false, serverName) }
            }
            val hash = withContext(Dispatchers.IO) { BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray()) }
            when (deps.setupCode.complete(code, hash)) {
                SetupCode.Outcome.DONE -> Unit
                SetupCode.Outcome.ALREADY_SET_UP -> return@post call.seeOther("/login")
                SetupCode.Outcome.BAD_CODE ->
                    return@post call.page(HttpStatusCode.Unauthorized) { Pages.setup(this, s.csrf, badCode, false, serverName) }
            }
            setupLimiter.refund(key)
            sessions.remove(old)
            val (id, _) = sessions.create(true, adminTag())
            call.setCookie(id)
            log.info("Admin password set on the setup page; the setup code is used up")
            call.seeOther("/")
        }

        get("/login") {
            if (setupPending()) return@get call.seeOther("/setup")
            val existing = session(call)
            if (existing?.authenticated == true) return@get call.seeOther("/")
            val s = existing ?: sessions.create(false, null).let { (id, s) -> call.setCookie(id); s }
            call.page { Pages.login(this, s.csrf, null, false, serverName) }
        }

        post("/login") {
            if (setupPending()) return@post call.seeOther("/setup")
            val old = call.request.cookies[COOKIE]
            val s = session(call)
            if (s == null || !call.sameOrigin()) return@post call.forbidden()
            val form = call.readForm()
            if (!s.csrfMatches(form["_csrf"])) return@post call.forbidden()
            val key = deps.clientKey(call)
            if (!loginLimiter.tryAcquire(key)) {
                log.warn("Dashboard login refused: too many failures")
                return@post call.page(HttpStatusCode.TooManyRequests) { Pages.login(this, s.csrf, null, true, serverName) }
            }
            val password = form["password"].orEmpty()
            val hash = adminHash()
            val ok = hash != null && password.isNotEmpty() && password.length <= MAX_PASSWORD &&
                withContext(Dispatchers.IO) { BCrypt.verifyer().verify(password.toCharArray(), hash).verified }
            if (!ok) {
                log.info("Dashboard login failed")
                return@post call.page(HttpStatusCode.Unauthorized) {
                    Pages.login(this, s.csrf, "That password did not match.", false, serverName)
                }
            }
            loginLimiter.refund(key)
            sessions.remove(old)
            val (id, _) = sessions.create(true, adminTag())
            call.setCookie(id)
            log.info("Dashboard login")
            call.seeOther("/")
        }

        post("/logout") {
            call.authedPost() ?: return@post
            sessions.remove(call.request.cookies[COOKIE])
            call.clearCookie()
            call.seeOther("/login")
        }

        get("/") {
            val s = call.authedGet() ?: return@get
            call.page { Pages.overview(this, chrome(s, "/"), fmt, overview()) }
        }

        get("/pair") {
            val s = call.authedGet() ?: return@get
            val refresh = call.request.queryParameters["r"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0
            call.page { Pages.pair(this, chrome(s, "/pair"), fmt, pairVm(refresh)) }
        }

        get("/devices") {
            val s = call.authedGet() ?: return@get
            call.page { Pages.devices(this, chrome(s, "/devices"), fmt, devices()) }
        }

        post("/devices/{id}/revoke") {
            val id = call.parameters["id"]?.takeIf { ID.matches(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
            call.authedPost() ?: return@post
            if (deps.store.revokeDevice(id)) log.info("Device {} revoked from the dashboard", id)
            call.seeOther("/devices")
        }

        get("/agent") {
            val s = call.authedGet() ?: return@get
            call.page { Pages.agent(this, chrome(s, "/agent"), agentVm(
                saved = call.request.queryParameters["saved"] == "1",
                keyRemoved = call.request.queryParameters["keyRemoved"] == "1",
            )) }
        }

        post("/agent") {
            val (s, form) = call.authedPost() ?: return@post
            val kind = ProviderKind.fromWire(form["provider"])
            val entered = AgentForm(form["provider"].orEmpty(), form["baseUrl"].orEmpty().trim(), form["model"].orEmpty().trim())
            val hadKey = deps.agentSettings.hasApiKey()
            val keyBlank = form["apiKey"].isNullOrBlank() && form["removeKey"] != "1"
            val error = if (kind == null || kind !in deps.agentSettings.selectableKinds()) "Choose a provider." else try {
                val key = when {
                    form["removeKey"] == "1" -> ""
                    form["apiKey"].isNullOrBlank() -> null
                    else -> form["apiKey"]
                }
                deps.agentSettings.save(kind, entered.baseUrl, entered.model, key)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "Those settings are not valid."
            } catch (e: IllegalStateException) {
                "The server has no master key loaded, so it cannot store an API key."
            }
            if (error == null) {
                // A blank key is kept only for the same provider and host; say so when it was dropped.
                val dropped = hadKey && keyBlank && !deps.agentSettings.hasApiKey()
                return@post call.seeOther(if (dropped) "/agent?saved=1&keyRemoved=1" else "/agent?saved=1")
            }
            call.page(HttpStatusCode.BadRequest) { Pages.agent(this, chrome(s, "/agent"), agentVm(form = entered, error = error)) }
        }

        post("/agent/test") {
            val (s, _) = call.authedPost() ?: return@post
            val result = if (!testLimiter.tryAcquire("test")) {
                TestResult(null, null, emptyList(), "Too many tests in a minute. Wait a moment and try again.")
            } else runTest()
            call.page { Pages.agent(this, chrome(s, "/agent"), agentVm(test = result)) }
        }

        get("/tokens") {
            val s = call.authedGet() ?: return@get
            call.page { Pages.tokens(this, chrome(s, "/tokens"), fmt, tokensVm()) }
        }

        post("/tokens") {
            val (s, form) = call.authedPost() ?: return@post
            val name = form["name"].orEmpty()
            val scopes = form.getAll("scope").orEmpty().toSet()
            try {
                val created = deps.agentTokens.create(name, scopes)
                log.info("Agent token {} created from the dashboard", created.row.id)
                val base = publicBase(call)
                val ct = CreatedToken(created.row.name, created.row.scopes, created.token, "$base/mcp", base)
                call.page { Pages.tokens(this, chrome(s, "/tokens"), fmt, tokensVm(created = ct)) }
            } catch (e: IllegalArgumentException) {
                call.page(HttpStatusCode.BadRequest) {
                    Pages.tokens(this, chrome(s, "/tokens"), fmt, tokensVm(error = e.message, formName = name, formScopes = scopes))
                }
            }
        }

        post("/tokens/{id}/revoke") {
            val id = call.parameters["id"]?.takeIf { ID.matches(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
            call.authedPost() ?: return@post
            if (deps.agentTokens.revoke(id)) log.info("Agent token {} revoked from the dashboard", id)
            call.seeOther("/tokens")
        }

        get("/access") {
            val s = call.authedGet() ?: return@get
            val total = deps.store.accessLogCount()
            val pages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
            val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, pages)
            val rows = deps.store.accessLogPage(PAGE_SIZE, (page - 1) * PAGE_SIZE)
            call.page { Pages.access(this, chrome(s, "/access"), fmt, AccessVm(rows, page, pages, total)) }
        }

        get("/export") {
            val s = call.authedGet() ?: return@get
            val vm = ExportVm(
                deps.store.metricCounts().values.sum(), deps.store.sleepSessions(Long.MIN_VALUE, Long.MAX_VALUE).size,
                deps.store.listDevices().size, deps.store.accessLogCount(),
            )
            call.page { Pages.export(this, chrome(s, "/export"), vm) }
        }

        post("/export") {
            call.authedPost() ?: return@post
            export(call)
        }

        get("/cert.pem") {
            call.authedGet() ?: return@get
            val pem = deps.certPem ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "orbit-server.pem").toString())
            call.respondBytes(pem.toByteArray(), ContentType.parse("application/x-pem-file"))
        }
    }

    // ------------------------------------------------------------------ page data

    /** The https base URL phones and agents should use: configured at startup, else what this browser used. */
    private fun publicBase(call: ApplicationCall): String =
        deps.publicUrl ?: "${call.request.origin.scheme}://${call.hostHeader()}"

    private fun pairVm(refresh: Int): PairVm {
        val now = deps.clock.millis()
        deps.proxy?.let { proxy ->
            // Behind a reverse proxy: the public certificate is checked against the exact host (trust mode `ca`).
            val link = deps.pairing.newPublicCaToken(proxy.publicUrl, serverName)
            val uri = link.toUri()
            val walker = link.toUri(PairingUri.WALKER_PREFIX)
            return PairVm(
                uri, Qr.svg(uri, label = "Pairing QR code"), now + Pairing.TOKEN_TTL_MS, now, proxy.publicUrl, null, refresh, publicCa = true,
                walkerUri = walker, walkerQrSvg = Qr.svg(walker, label = "OrbitWalker pairing QR code"),
            )
        }
        val base = deps.publicUrl ?: "https://localhost:8443"
        val fp = deps.fingerprint
        if (fp == null || !base.startsWith("https://")) return PairVm(null, null, now, now, base, null, refresh)
        val link = deps.pairing.newToken(base, fp, serverName)
        val uri = link.toUri()
        val walker = link.toUri(PairingUri.WALKER_PREFIX)
        return PairVm(
            uri, Qr.svg(uri, label = "Pairing QR code"), now + Pairing.TOKEN_TTL_MS, now, base, fp, refresh,
            walkerUri = walker, walkerQrSvg = Qr.svg(walker, label = "OrbitWalker pairing QR code"),
        )
    }

    private fun devices(): List<DeviceVm> = deps.store.listDevices().map { d ->
        val info = deps.store.deviceInfo(d.id)
        DeviceVm(d.id, d.name, d.createdMs, d.lastSeenMs, d.revoked, d.scopes, info?.battery, info?.firmware)
    }.sortedWith(compareBy<DeviceVm> { it.revoked }.thenByDescending { it.lastSeenMs ?: 0 })

    private fun agentVm(
        form: AgentForm? = null, error: String? = null, saved: Boolean = false, test: TestResult? = null, keyRemoved: Boolean = false,
    ): AgentVm {
        val settings = deps.agentSettings
        val cfg = settings.load()
        val kind = deps.store.getSetting(AgentSettings.PROVIDER) ?: cfg?.kind?.wire ?: ProviderKind.ANTHROPIC.wire
        val f = form ?: AgentForm(kind, deps.store.getSetting(AgentSettings.BASE_URL).orEmpty(), deps.store.getSetting(AgentSettings.MODEL) ?: cfg?.model.orEmpty())
        return AgentVm(settings.selectableKinds(), f, cfg != null, settings.hasApiKey(), error, saved, test, keyRemoved && !settings.hasApiKey(), settings.loadRefusal())
    }

    private suspend fun runTest(): TestResult {
        var outcome = "error"
        var model = "-"
        var calls = 0
        try {
            val cfg = deps.agentSettings.load()
            if (cfg == null) {
                outcome = "no_model"
                return TestResult(null, null, emptyList(), "No model is configured yet. Save a provider first.")
            }
            model = cfg.model
            val zone = zone
            // What a phone's ask could read: the union of active phones' scopes, never more than they share.
            val data = StoreDataTools(deps.store, deps.accessLog, Caller(ACTOR, testScopes()), zone, deps.clock)
            val agent = Agent(deps.providerFactory.create(cfg), AgentTools(data, zone, deps.clock), clock = deps.clock, zone = zone)
            val r = try {
                agent.ask(TEST_QUESTION, emptyList())
            } catch (e: ProviderException) {
                outcome = "model_error"
                log.warn("Agent test via {} failed: {}", cfg.kind.wire, e.message)
                return TestResult(null, cfg.model, emptyList(), "The model request failed (${e.message}). Check the base URL, model name and key.")
            }
            outcome = "ok"; model = r.model; calls = r.provenance.size
            return TestResult(r.answer, r.model, r.provenance, null)
        } finally {
            deps.accessLog.record(ACTOR, "agent_test", "outcome=$outcome model=$model tool_calls=$calls", 0)
        }
    }

    /** Union of the scopes of every phone that is not revoked (empty when none is paired). */
    internal fun testScopes(): Set<String> =
        deps.store.listDevices().filter { !it.revoked }.flatMapTo(sortedSetOf()) { it.scopes }

    private fun tokensVm(created: CreatedToken? = null, error: String? = null, formName: String = "", formScopes: Set<String> = setOf("heart", "sleep")) =
        TokensVm(deps.agentTokens.list(), created, error, formName, formScopes, deps.fingerprint, deps.certPem != null, publicCa = deps.proxy != null)

    private fun overview(): OverviewVm {
        val zone = zone
        val f = Fmt(zone) { deps.clock.millis() }
        val now = deps.clock.millis()
        val store = deps.store
        val latest = store.latestSamples()
        val today = LocalDate.now(deps.clock.withZone(zone))
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()

        fun dailyMeans(m: Metric): List<Double> = store.samples(m.name, weekStart, now)
            .groupBy { Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate() }.toSortedMap().values.map { d -> d.map { it.value }.average() }

        fun card(m: Metric, label: String, glyph: String, tint: String, unit: String, estimate: Boolean = false, decimals: Int = 0): MetricCard {
            val s = latest[m.name]
            return MetricCard(label, glyph, tint, s?.let { num(it.value, decimals) }, unit, s?.let { f.rel(it.epochMs) } ?: "No data yet", estimate, dailyMeans(m))
        }

        val dailySteps = (0..6).map { i ->
            val d = today.minusDays((6 - i).toLong())
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            maxOf(store.samples(Metric.STEPS.name, from, to).sumOf { it.value }, store.samples(Metric.STEPS_DAY_TOTAL.name, from, to).maxOfOrNull { it.value } ?: 0.0)
        }
        val stepsLatest = maxOf(latest[Metric.STEPS.name]?.epochMs ?: 0, latest[Metric.STEPS_DAY_TOTAL.name]?.epochMs ?: 0)

        val nights = store.sleepSessions(today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        fun asleepMin(s: SleepDto) = s.segments.filter { it.stage != "AWAKE" }.sumOf { it.durationSec } / 60
        val lastNight = nights.lastOrNull()

        val sys = latest[Metric.BLOOD_PRESSURE_SYS.name]
        val dia = latest[Metric.BLOOD_PRESSURE_DIA.name]
        val cards = listOf(
            card(Metric.HEART_RATE, "Heart rate", "heart", "heart", "bpm"),
            card(Metric.HRV, "HRV", "hrv", "readiness", "ms"),
            card(Metric.SPO2, "SpO₂", "drop", "spo2", "%"),
            card(Metric.STRESS, "Stress", "lotus", "stress", "", estimate = true),
            MetricCard("Steps today", "steps", "activity", if (stepsLatest > 0) num(dailySteps.last(), 0) else null, "",
                if (stepsLatest > 0) "Today so far" else "No data yet", false, dailySteps),
            MetricCard("Sleep", "sleep", "sleep", lastNight?.let { "${asleepMin(it) / 60}h ${asleepMin(it) % 60}m" }, "",
                lastNight?.let { "Night of ${f.day(it.startMs)}" } ?: "No data yet", false, nights.map { asleepMin(it).toDouble() }),
            MetricCard("Blood pressure", "heart", "heart", if (sys != null && dia != null) "${num(sys.value, 0)}/${num(dia.value, 0)}" else null,
                "mmHg", sys?.let { f.rel(it.epochMs) } ?: "No data yet", true, dailyMeans(Metric.BLOOD_PRESSURE_SYS)),
            card(Metric.RESPIRATORY_RATE, "Breathing", "breath", "spo2", "/min"),
        )

        val devices = devices()
        val active = devices.filter { !it.revoked }
        val freshest = active.filter { it.lastSeenMs != null }.maxByOrNull { it.lastSeenMs!! }
        val samples = store.metricCounts().values.sum()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val readsToday = store.accessLog(10_000, dayStart).count { it.actor != ACTOR }
        val (insight, link) = when {
            active.isEmpty() -> "No phone is paired yet. Pair Orbit Health to start syncing." to ("/pair" to "Pair a phone")
            freshest == null -> "${active.first().name} is paired but has not synced yet." to null
            else -> "${freshest.name} synced ${f.rel(freshest.lastSeenMs)}." to null
        }
        val meta = "${"%,d".format(samples)} samples · ${active.size} device${if (active.size == 1) "" else "s"} · " +
            "$readsToday agent read${if (readsToday == 1) "" else "s"} today"
        val cfg = deps.agentSettings.load()
        val agentLine = cfg?.let { "Model: ${it.model}" } ?: "No model configured"
        val greeting = when (f.hour()) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        return OverviewVm(
            greeting, insight, meta, link, cards, devices, store.accessLog(5),
            agentLine, deps.agentTokens.list().count { !it.revoked },
        )
    }

    private fun num(v: Double, decimals: Int): String =
        if (decimals == 0) "%,d".format(v.roundToInt()) else "%.${decimals}f".format(v)

    // ------------------------------------------------------------------ export

    private suspend fun export(call: ApplicationCall) {
        val store = deps.store
        val now = deps.clock.millis()
        val sleep = store.sleepSessions(Long.MIN_VALUE, Long.MAX_VALUE)
        val sampleCount = store.metricCounts().values.sum()
        deps.accessLog.record(ACTOR, "export", "all data (json)", sampleCount + sleep.size)
        log.info("Data export from the dashboard")
        val date = DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(now).atZone(zone))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "orbit-export-$date.json").toString())
        val json = Json { encodeDefaults = true }
        call.respondTextWriter(ContentType.Application.Json) {
            val head = buildJsonObject {
                put("format", "orbit-server-export"); put("version", 1); put("exportedAtMs", now); put("server", serverName)
                putJsonArray("devices") {
                    for (d in store.listDevices()) add(buildJsonObject {
                        put("id", d.id); put("name", d.name); put("createdMs", d.createdMs); put("lastSeenMs", d.lastSeenMs)
                        put("revoked", d.revoked); put("scopes", buildJsonArray { d.scopes.sorted().forEach { add(JsonPrimitive(it)) } })
                    })
                }
            }.toString()
            write(head.removeSuffix("}"))
            write(",\"samples\":[")
            var first = true
            store.forEachSample { s ->
                if (!first) write(",")
                first = false
                write(json.encodeToString(SampleDto.serializer(), s))
            }
            write("],\"sleep\":")
            write(json.encodeToString(ListSerializer(SleepDto.serializer()), sleep))
            write(",\"accessLog\":[")
            first = true
            store.forEachAccessLog { e ->
                if (!first) write(",")
                first = false
                write(json.encodeToString(AccessLogEntry.serializer(), e))
            }
            write("]}")
        }
    }
}

