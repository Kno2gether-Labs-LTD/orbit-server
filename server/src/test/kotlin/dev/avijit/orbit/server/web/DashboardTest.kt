package dev.avijit.orbit.server.web

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.Pairing.Companion.DEFAULT_SERVER_NAME
import dev.avijit.orbit.server.Secrets
import dev.avijit.orbit.server.Settings
import dev.avijit.orbit.server.api.ApiEnv
import dev.avijit.orbit.server.api.T0
import dev.avijit.orbit.server.api.withApi
import dev.avijit.orbit.server.decodeQrMatrix
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

const val PASSWORD = "correct horse battery"

/** A cookie-keeping browser that never follows redirects, so tests see every 303. */
class Browser(private val env: ApiEnv, private val origin: String? = "http://localhost") {
    private val http: HttpClient = env.builder.createClient { followRedirects = false }
    var cookie: String? = null
    var lastSetCookie: String? = null
    var lastCsrf: String? = null
    /** Sent with every request (proxy tests: X-Forwarded-*, the simulated TCP peer). */
    var extraHeaders: Map<String, String> = emptyMap()

    private fun HttpResponse.capture(): HttpResponse {
        headers.getAll(HttpHeaders.SetCookie).orEmpty().filter { it.startsWith("${Dashboard.COOKIE}=") }.forEach { sc ->
            lastSetCookie = sc
            val value = sc.substringAfter('=').substringBefore(';')
            cookie = if ("Max-Age=0" in sc || value.isEmpty()) null else value
        }
        return this
    }

    suspend fun get(path: String): HttpResponse {
        val r = http.get(path) {
            cookie?.let { header(HttpHeaders.Cookie, "${Dashboard.COOKIE}=$it") }
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }.capture()
        if (r.status == HttpStatusCode.OK) CSRF.find(r.bodyAsText())?.let { lastCsrf = it.groupValues[1] }
        return r
    }

    suspend fun post(path: String, form: Map<String, String>, csrf: String? = lastCsrf, origin: String? = this.origin): HttpResponse =
        http.post(path) {
            cookie?.let { header(HttpHeaders.Cookie, "${Dashboard.COOKIE}=$it") }
            origin?.let { header(HttpHeaders.Origin, it) }
            extraHeaders.forEach { (k, v) -> header(k, v) }
            setBody(FormDataContent(Parameters.build {
                form.forEach { (k, v) -> append(k, v) }
                csrf?.let { append("_csrf", it) }
            }))
        }.capture().also { r ->
            if (r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Unauthorized) {
                CSRF.find(r.bodyAsText())?.let { lastCsrf = it.groupValues[1] }
            }
        }

    suspend fun login(password: String = PASSWORD): HttpResponse {
        get("/login")
        return post("/login", mapOf("password" to password))
    }

    companion object {
        val CSRF = Regex("""name="_csrf" value="([A-Za-z0-9_-]+)"""")
    }
}

fun ApiEnv.setAdminPassword(pw: String = PASSWORD) =
    store.putSetting(Settings.ADMIN_HASH, BCrypt.withDefaults().hashToString(4, pw.toCharArray()))

fun withDashboard(limits: Limits = Limits(), env: Map<String, String> = emptyMap(), block: suspend ApiEnv.() -> Unit) =
    withApi(limits = limits, env = env) {
        setAdminPassword()
        block()
    }

private val PAGES = listOf("/", "/pair", "/devices", "/agent", "/tokens", "/access", "/export")

class DashboardTest {

    @Test fun dashboardRequiresLogin() = withDashboard {
        val b = Browser(this)
        for (p in PAGES) {
            val r = b.get(p)
            assertEquals(p, HttpStatusCode.SeeOther, r.status)
            assertEquals(p, "/login", r.headers[HttpHeaders.Location])
        }
        // POSTs without a session never act, whatever they carry.
        val before = store.listAgentTokens().size
        assertEquals(HttpStatusCode.Forbidden, b.post("/tokens", mapOf("name" to "x", "scope" to "heart"), csrf = "forged").status)
        assertEquals(before, store.listAgentTokens().size)
        assertEquals(HttpStatusCode.Forbidden, b.post("/export", emptyMap(), csrf = "forged").status)

        // The login page and the static assets are public; the login page carries the security headers.
        val login = b.get("/login")
        assertEquals(HttpStatusCode.OK, login.status)
        assertSecurityHeaders(login)
        // The hidden username (for password managers) is the server name, read-only; never a fixed "admin".
        val loginHtml = login.bodyAsText()
        assertFalse("no \"admin\" username value", loginHtml.contains("value=\"admin\""))
        val username = Regex("""<input[^>]*name="username"[^>]*>""").find(loginHtml)?.value ?: error(loginHtml)
        assertTrue(username, username.contains("readonly"))
        assertTrue(username, username.contains("autocomplete=\"username\""))
        val name = store.getSetting(Settings.SERVER_NAME) ?: DEFAULT_SERVER_NAME
        assertTrue(username, username.contains("value=\"$name\""))
        assertEquals(HttpStatusCode.OK, b.get("/assets/dashboard.css").status)

        val bad = b.post("/login", mapOf("password" to "wrong password!"))
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertTrue(bad.bodyAsText().contains("did not match"))
        assertEquals(HttpStatusCode.SeeOther, b.get("/").status)

        assertEquals(HttpStatusCode.SeeOther, b.login().status)
        for (p in PAGES) {
            val r = b.get(p)
            assertEquals(p, HttpStatusCode.OK, r.status)
            assertSecurityHeaders(r)
            assertEquals(p, "no-store", r.headers[HttpHeaders.CacheControl])
        }
    }

    @Test fun loginRotatesSession() = withDashboard {
        val b = Browser(this)
        b.get("/login")
        val anonymous = b.cookie!!
        assertTrue("256-bit id", Secrets.looksLikeToken(anonymous) && anonymous.length >= 43)
        val sc = b.lastSetCookie!!
        for (attr in listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/")) assertTrue(sc, sc.contains(attr))
        assertTrue("__Host- prefix", Dashboard.COOKIE.startsWith("__Host-"))

        val r = b.post("/login", mapOf("password" to PASSWORD))
        assertEquals(HttpStatusCode.SeeOther, r.status)
        assertEquals("/", r.headers[HttpHeaders.Location])
        val authed = b.cookie!!
        assertNotEquals("the id changes on login", anonymous, authed)
        assertEquals(HttpStatusCode.OK, b.get("/").status)

        // The pre-login id is dead: it neither logs in nor keeps its CSRF token.
        val old = Browser(this).apply { cookie = anonymous }
        assertEquals(HttpStatusCode.SeeOther, old.get("/").status)

        // Logout ends the session server-side, not just in the browser.
        b.get("/")
        val out = b.post("/logout", emptyMap())
        assertEquals(HttpStatusCode.SeeOther, out.status)
        assertNull(b.cookie)
        val replay = Browser(this).apply { cookie = authed }
        assertEquals(HttpStatusCode.SeeOther, replay.get("/").status)
    }

    @Test fun sessionsExpireWhenIdleAndAfterTwelveHours() = withDashboard {
        val b = Browser(this)
        b.login()
        clock.advance(29 * 60_000L)
        assertEquals(HttpStatusCode.OK, b.get("/").status)
        clock.advance(31 * 60_000L)
        assertEquals("30 min idle", HttpStatusCode.SeeOther, b.get("/").status)

        val c = Browser(this)
        c.login()
        repeat(25) { // active every 29 minutes for ~12 h
            clock.advance(29 * 60_000L)
            if (it < 24) assertEquals("still active at ${(it + 1) * 29} min", HttpStatusCode.OK, c.get("/").status)
        }
        assertEquals("absolute 12 h limit", HttpStatusCode.SeeOther, c.get("/").status)
    }

    @Test fun changingTheAdminPasswordEndsSessions() = withDashboard {
        val b = Browser(this)
        b.login()
        assertEquals(HttpStatusCode.OK, b.get("/").status)
        setAdminPassword("another long password")
        assertEquals(HttpStatusCode.SeeOther, b.get("/").status)
    }

    @Test fun postWithoutCsrfRejected() = withDashboard {
        val b = Browser(this)
        b.login()
        b.get("/tokens")
        val good = b.lastCsrf!!
        val form = mapOf("name" to "claude-code", "scope" to "heart")

        assertEquals(HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = null).status)
        assertEquals(HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = "x".repeat(43)).status)
        assertEquals("cross-origin", HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = good, origin = "https://evil.example").status)
        assertEquals("Origin null", HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = good, origin = "null").status)
        assertEquals("no Origin and no Referer", HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = good, origin = null).status)
        // Another session's token is not this session's token.
        val other = Browser(this).apply { login(); get("/tokens") }
        assertEquals(HttpStatusCode.Forbidden, b.post("/tokens", form, csrf = other.lastCsrf).status)
        assertTrue("nothing was created", store.listAgentTokens().isEmpty())

        val ok = b.post("/tokens", form, csrf = good)
        assertEquals(ok.bodyAsText(), HttpStatusCode.OK, ok.status)
        assertEquals(1, store.listAgentTokens().size)
        // Logout and the login form are POSTs too and need the token.
        assertEquals(HttpStatusCode.Forbidden, b.post("/logout", emptyMap(), csrf = null).status)
        assertEquals(HttpStatusCode.OK, b.get("/").status)
    }

    @Test fun loginRateLimited() = withDashboard(limits = Limits(loginFailuresPer15Min = 5)) {
        val b = Browser(this)
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, b.login("wrong password $it").status) }
        val blocked = b.login(PASSWORD)
        assertEquals("even the right password is refused while limited", HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue(blocked.bodyAsText().contains("15 minutes"))
        assertEquals(HttpStatusCode.SeeOther, b.get("/").status)

        clock.advance(15 * 60_000L + 1)
        assertEquals(HttpStatusCode.SeeOther, b.login(PASSWORD).status)
        assertEquals(HttpStatusCode.OK, b.get("/").status)
    }

    @Test fun successfulLoginsDoNotCountTowardTheLimit() = withDashboard(limits = Limits(loginFailuresPer15Min = 2)) {
        repeat(4) { assertEquals(HttpStatusCode.SeeOther, Browser(this).login().status) }
        val b = Browser(this)
        assertEquals(HttpStatusCode.Unauthorized, b.login("nope nope nope").status)
        assertEquals(HttpStatusCode.SeeOther, b.login().status)
    }

    @Test fun pairPageShowsFreshQr() = withDashboard {
        val b = Browser(this)
        b.login()
        val first = b.get("/pair").bodyAsText()
        val second = b.get("/pair").bodyAsText()
        val u1 = pairingUriIn(first)
        val u2 = pairingUriIn(second)
        assertNotEquals("every visit mints a new one-time token", u1.token, u2.token)
        assertEquals(dev.avijit.orbit.server.api.FP, u1.fingerprint)
        assertTrue(first.contains("data-expires-at=\"${T0 + 10 * 60_000}\""))

        // The QR encodes exactly the URI shown next to it.
        val svg = Regex("""<svg[^>]*class="qr"[\s\S]*?</svg>""").find(second)!!.value
        assertEquals(u2.toUri(), decodeQrMatrix(qrSvgModules(svg)))

        // Both tokens are live and single-use.
        assertEquals(HttpStatusCode.OK, pair(token = u1.token).status)
        assertEquals(HttpStatusCode.Unauthorized, pair(token = u1.token).status)
        assertEquals(HttpStatusCode.OK, pair(token = u2.token).status)
    }

    @Test fun tokenCreateShowsTheTokenOnceWithSnippets() = withDashboard {
        val b = Browser(this)
        b.login()
        b.get("/tokens")
        val r = b.post("/tokens", mapOf("name" to "claude-code", "scope" to "heart")).bodyAsText()
        val row = store.listAgentTokens().single()
        assertEquals(setOf("heart"), row.scopes)
        val token = Regex("""Bearer ([A-Za-z0-9_-]{43})""").find(r)!!.groupValues[1]
        assertEquals(row.tokenHash, Secrets.sha256Hex(token))
        assertTrue(r.contains("claude mcp add --transport http orbit"))
        assertTrue(r.contains("&quot;mcpServers&quot;") || r.contains("\"mcpServers\""))
        assertTrue(r.contains("NODE_EXTRA_CA_CERTS"))
        assertFalse("never shown again", b.get("/tokens").bodyAsText().contains(token))

        val dup = b.post("/tokens", mapOf("name" to "claude-code", "scope" to "sleep"))
        assertEquals(HttpStatusCode.BadRequest, dup.status)
        assertTrue(dup.bodyAsText().contains("already named"))

        b.get("/tokens")
        assertEquals(HttpStatusCode.SeeOther, b.post("/tokens/${row.id}/revoke", emptyMap()).status)
        assertTrue(store.listAgentTokens().single().revoked)
    }

    @Test fun devicesCanBeRevoked() = withDashboard {
        val dev = pairedDevice("Pixel 9")
        val b = Browser(this)
        b.login()
        assertTrue(b.get("/devices").bodyAsText().contains("Pixel 9"))
        assertEquals(HttpStatusCode.SeeOther, b.post("/devices/${dev.deviceId}/revoke", emptyMap()).status)
        assertTrue(store.device(dev.deviceId)!!.revoked)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state") { header(HttpHeaders.Authorization, "Bearer ${dev.deviceToken}") }.status)
    }

    @Test fun agentTestReadsOnlyWhatActivePhonesShare() = withDashboard(env = mapOf("ORBIT_SCRIPTED_AGENT" to "1")) {
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 61.0, "HISTORY")), null)
        val sleepOnly = pairedDevice("Sleep only")
        store.updateDeviceScopes(sleepOnly.deviceId, setOf("sleep"))
        val revoked = pairedDevice("Old phone") // all scopes, but revoked
        store.revokeDevice(revoked.deviceId)
        val b = Browser(this)
        b.login()
        b.get("/agent")
        b.post("/agent", mapOf("provider" to "scripted", "baseUrl" to "", "model" to "", "apiKey" to ""))
        b.get("/agent")
        val html = b.post("/agent/test", emptyMap()).bodyAsText()
        assertTrue("heart is shared by no active phone", html.contains("have access to your heart data"))

        store.updateDeviceScopes(sleepOnly.deviceId, setOf("sleep", "heart"))
        b.get("/agent")
        val shared = b.post("/agent/test", emptyMap()).bodyAsText()
        assertFalse(shared.contains("have access to your heart data"))
        assertTrue(shared.contains("61"))
    }

    @Test fun agentSettingsSaveAndTestShowsAnswerAndProvenance() = withDashboard(env = mapOf("ORBIT_SCRIPTED_AGENT" to "1")) {
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 3_600_000, 61.0, "HISTORY")), null)
        pairedDevice("Pixel 9") // the test reads with the phones' scopes
        val b = Browser(this)
        b.login()
        val page = b.get("/agent").bodyAsText()
        assertTrue(page.contains("re-enter the key when changing provider or host", ignoreCase = true))

        val saved = b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "http://localhost:11434/v1", "model" to "llama3.2", "apiKey" to "sk-secret-123"))
        assertEquals(HttpStatusCode.SeeOther, saved.status)
        assertEquals("llama3.2", deps.agentSettings.load()!!.model)
        val shown = b.get("/agent").bodyAsText()
        assertFalse("the key is never sent back", shown.contains("sk-secret-123"))
        assertTrue(shown.contains("saved key", ignoreCase = true))

        assertEquals(HttpStatusCode.BadRequest, b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "ftp://x", "model" to "m", "apiKey" to "")).status)

        b.get("/agent")
        b.post("/agent", mapOf("provider" to "scripted", "baseUrl" to "", "model" to "", "apiKey" to ""))
        b.get("/agent")
        val test = b.post("/agent/test", emptyMap())
        assertEquals(HttpStatusCode.OK, test.status)
        val html = test.bodyAsText()
        assertTrue(html, html.contains("61"))
        assertTrue(html.contains("get_metric"))
        assertTrue(store.accessLog(10).any { it.actor == "dashboard" && it.action == "agent_test" })
    }

    @Test fun exportDownloadsAllDataAndIsLogged() = withDashboard {
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 60_000, 58.0, "HISTORY"), SampleDto("STEPS", T0 - 120_000, 400.0, "HISTORY")), null)
        store.insertSleep(listOf(SleepDto(T0 - 8 * 3_600_000, T0 - 3_600_000, listOf(SegmentDto("DEEP", T0 - 8 * 3_600_000, 3600)))), null)
        val b = Browser(this)
        b.login()
        b.get("/export")
        val r = b.post("/export", emptyMap())
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentDisposition]!!.contains("attachment"))
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2, json["samples"]!!.jsonArray.size)
        assertEquals(1, json["sleep"]!!.jsonArray.size)
        assertEquals("DEEP", json["sleep"]!!.jsonArray[0].jsonObject["segments"]!!.jsonArray[0].jsonObject["stage"]!!.jsonPrimitive.content)
        val log = store.accessLog(10).single { it.action == "export" }
        assertEquals("dashboard", log.actor)
        assertEquals(3, log.rows)
    }

    @Test fun exportStreamsTheWholeAccessLog() = withDashboard {
        val n = 10_050 // more than the old 10,000-row cap
        val rows = (0 until n).map { i -> Triple(T0 + i, "claude-code", "row #$i") }
        rows.forEach { (at, actor, d) -> store.appendAccessLog(at, actor, "get_metric", d, 1) }
        val b = Browser(this)
        b.login()
        b.get("/export")
        val json = Json.parseToJsonElement(b.post("/export", emptyMap()).bodyAsText()).jsonObject
        val log = json["accessLog"]!!.jsonArray
        assertEquals("every row plus the export's own row", n + 1, log.size)
        assertTrue(log.any { it.jsonObject["detail"]!!.jsonPrimitive.content == "row #0" })
    }

    @Test fun droppingTheSavedKeyOnAProviderChangeIsAnnounced() = withDashboard(env = mapOf("ORBIT_SCRIPTED_AGENT" to "1")) {
        val b = Browser(this)
        b.login()
        b.get("/agent")
        b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "http://localhost:11434/v1", "model" to "m", "apiKey" to "sk-1"))
        assertTrue(deps.agentSettings.hasApiKey())
        b.get("/agent")
        // Same provider and host, blank key: kept, no notice.
        val same = b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "http://localhost:11434/v1", "model" to "m2", "apiKey" to ""))
        assertEquals("/agent?saved=1", same.headers[HttpHeaders.Location])
        assertTrue(deps.agentSettings.hasApiKey())
        b.get("/agent")
        // Another host, blank key: the key is dropped and the page says so.
        val moved = b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "http://other-box.local:11434/v1", "model" to "m2", "apiKey" to ""))
        assertFalse(deps.agentSettings.hasApiKey())
        val page = b.get(moved.headers[HttpHeaders.Location]!!).bodyAsText()
        assertTrue(page, page.contains("Your saved API key was removed because the provider or host changed"))
        // Plain http to a public host is refused with a clear message.
        val insecure = b.post("/agent", mapOf("provider" to "openai_compat", "baseUrl" to "http://api.example.com/v1", "model" to "m", "apiKey" to "sk-2"))
        assertTrue(insecure.bodyAsText().contains("use https for this host"))
        assertFalse(deps.agentSettings.hasApiKey())
        // Without a key change, the plain saved page has no such notice.
        assertFalse(b.get("/agent?saved=1").bodyAsText().contains("was removed"))
    }

    @Test fun pairPageVisitsDoNotAccumulateTokens() = withDashboard {
        val b = Browser(this)
        b.login()
        repeat(5) { b.get("/pair") }
        assertEquals("unexpired codes stay valid", 5, store.pairTokenCount())
        clock.advance(10 * 60_000L + 1)
        b.get("/pair")
        assertEquals("expired codes are pruned when a new one is minted", 1, store.pairTokenCount())
        val uri = Regex("""orbit://pair\?[^"<\s]+""").find(b.get("/pair").bodyAsText())!!.value.replace("&amp;", "&")
        assertEquals(HttpStatusCode.OK, pair(token = PairingUri.parse(uri)!!.token).status)
        b.get("/pair")
        assertEquals("used codes are pruned too", 2, store.pairTokenCount())
    }

    @Test fun revokeRejectsMalformedIds() = withDashboard {
        val b = Browser(this)
        b.login()
        b.get("/devices")
        for (bad in listOf("not-a-uuid", "x".repeat(300), "%0Aforged%20log%20line")) {
            assertEquals(bad, HttpStatusCode.NotFound, b.post("/devices/$bad/revoke", emptyMap()).status)
            assertEquals(bad, HttpStatusCode.NotFound, b.post("/tokens/$bad/revoke", emptyMap()).status)
        }
        // A well-formed id still needs CSRF and works.
        val id = deps.agentTokens.create("t", setOf("heart")).row.id
        assertEquals(HttpStatusCode.SeeOther, b.post("/tokens/$id/revoke", emptyMap()).status)
        assertTrue(store.listAgentTokens().single().revoked)
    }

    @Test fun accessLogIsPaginated() = withDashboard {
        repeat(120) { store.appendAccessLog(T0 + it, "claude-code", "get_metric", "metric=HEART_RATE #$it", 1) }
        val b = Browser(this)
        b.login()
        val p1 = b.get("/access").bodyAsText()
        assertTrue(p1.contains("#119"))
        assertFalse(p1.contains("#69<"))
        assertTrue(p1.contains("href=\"/access?page=2\""))
        val p3 = b.get("/access?page=3").bodyAsText()
        assertTrue(p3.contains("#0<"))
        assertFalse(p3.contains("page=4"))
        assertEquals(HttpStatusCode.OK, b.get("/access?page=-5").status)
    }

    @Test fun overviewShowsLatestValues() = withDashboard {
        store.insertSamples(listOf(SampleDto("HEART_RATE", T0 - 60_000, 57.0, "HISTORY"), SampleDto("STRESS", T0 - 60_000, 31.0, "HISTORY")), null)
        pairedDevice("Pixel 9")
        val b = Browser(this)
        b.login()
        val html = b.get("/").bodyAsText()
        assertTrue(html.contains(">57<"))
        assertTrue(html.contains("Pixel 9"))
        assertTrue("estimates are labelled", html.contains("ESTIMATE"))
    }

    @Test fun assetsAreLocalAndPagesMakeNoExternalRequests() = withDashboard {
        val b = Browser(this)
        val css = b.get("/assets/dashboard.css")
        assertTrue(css.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
        val font = b.get("/assets/fonts/fraunces.ttf")
        assertEquals(HttpStatusCode.OK, font.status)
        assertEquals("font/ttf", font.headers[HttpHeaders.ContentType])
        assertEquals("control: encoded paths reach the server and are decoded there", HttpStatusCode.OK, b.get("/assets/dashboard%2Ecss").status)
        // Encoded traversal reaches the route undecoded by the client; it must still never leave the allow-list.
        for (p in listOf("/assets/%2e%2e/orbit.db", "/assets/%2E%2E%2Forbit.db", "/assets/fonts%2F..%2F..%2Forbit.db", "/assets/..%2fdashboard.css")) {
            assertEquals(p, HttpStatusCode.NotFound, b.get(p).status)
        }
        assertEquals(HttpStatusCode.NotFound, b.get("/assets/nope.js").status)
        b.login()
        val urls = Regex("""(?:src|href)="([^"]+)"|url\(([^)]+)\)""")
        val bodies = PAGES.map { b.get(it).bodyAsText() } + css.bodyAsText() + b.get("/assets/dashboard.js").bodyAsText()
        for (body in bodies) for (m in urls.findAll(body)) {
            val u = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim('"', '\'')
            assertTrue("external reference $u", u.startsWith("/") || u.startsWith("#") || u.startsWith("data:"))
        }
        for (body in bodies) assertFalse(body.contains("style=\""))
    }

    @Test fun agentPageExplainsARefusedStoredUrl() = withDashboard {
        store.putSetting(dev.avijit.orbit.server.agent.AgentSettings.PROVIDER, "openai_compat")
        store.putSetting(dev.avijit.orbit.server.agent.AgentSettings.BASE_URL, "http://models.example.com/v1")
        store.putSetting(dev.avijit.orbit.server.agent.AgentSettings.MODEL, "llama3.2")
        val b = Browser(this)
        b.login()
        val html = b.get("/agent").bodyAsText()
        assertTrue(html, html.contains("is not used"))
        assertTrue(html, html.contains("models.example.com"))
    }

    private fun assertSecurityHeaders(r: HttpResponse) {
        val csp = r.headers["Content-Security-Policy"]!!
        for (d in listOf("default-src 'self'", "img-src 'self' data:", "style-src 'self'", "script-src 'self'", "frame-ancestors 'none'")) {
            assertTrue(csp, csp.contains(d))
        }
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertNotNull(r.headers["Referrer-Policy"])
    }

    private fun pairingUriIn(html: String): PairingUri {
        val raw = Regex("""orbit://pair\?[^"<\s]+""").find(html)!!.value.replace("&amp;", "&")
        return PairingUri.parse(raw)!!
    }

    /** Reads the modules back out of the SVG: one `M{x} {y}h1v1h-1z` square per dark module. */
    private fun qrSvgModules(svg: String): Array<BooleanArray> {
        val size = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg)!!.groupValues[1].toInt()
        val grid = Array(size) { BooleanArray(size) }
        Regex("""M(\d+) (\d+)h1v1h-1z""").findAll(svg).forEach { grid[it.groupValues[2].toInt()][it.groupValues[1].toInt()] = true }
        return grid
    }
}
