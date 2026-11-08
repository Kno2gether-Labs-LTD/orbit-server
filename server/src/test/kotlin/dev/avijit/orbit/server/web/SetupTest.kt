package dev.avijit.orbit.server.web

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.SetupCode
import dev.avijit.orbit.server.Settings
import dev.avijit.orbit.server.api.ApiEnv
import dev.avijit.orbit.server.api.withApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NEW_PASSWORD = "a brand new password"
private val ADMIN_PAGES = listOf("/", "/pair", "/devices", "/agent", "/tokens", "/access", "/export", "/cert.pem")

/** A server that has never been set up: no admin hash, one setup code issued. */
private fun withFreshServer(limits: Limits = Limits(), block: suspend ApiEnv.(code: String) -> Unit) =
    withApi(limits = limits) { block(SetupCode(store, clock).issue().code) }

private suspend fun Browser.setup(code: String, password: String = NEW_PASSWORD, confirm: String = password) =
    post("/setup", mapOf("code" to code, "password" to password, "confirm" to confirm))

class SetupTest {

    @Test fun everyAdminPageRedirectsToSetupBeforeSetup() = withFreshServer {
        val b = Browser(this)
        for (p in ADMIN_PAGES) {
            val r = b.get(p)
            assertEquals(p, HttpStatusCode.SeeOther, r.status)
            assertEquals(p, "/setup", r.headers[HttpHeaders.Location])
        }
        val login = b.get("/login")
        assertEquals(HttpStatusCode.SeeOther, login.status)
        assertEquals("/setup", login.headers[HttpHeaders.Location])

        val page = b.get("/setup")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html, html.contains("Set up"))
        assertTrue(html, html.contains("name=\"code\""))
        assertTrue(html, html.contains("name=\"confirm\""))
        assertFalse("no \"admin\" username value", html.contains("value=\"admin\""))
        assertTrue(html, Regex("""name="username"[^>]*autocomplete="username"|autocomplete="username"[^>]*name="username"""").containsMatchIn(html))
        assertNotNull(b.lastCsrf)
        assertNotNull(b.cookie)
    }

    @Test fun noLoginWorksBeforeSetup() = withFreshServer {
        val b = Browser(this)
        b.get("/setup")
        for (pw in listOf("", "admin", "password", "orbit", "correct horse battery")) {
            val r = b.post("/login", mapOf("password" to pw))
            assertNotEquals(pw, HttpStatusCode.OK, r.status)
            assertEquals(pw, "/setup", r.headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.SeeOther, b.get("/").status)
        }
        // Admin POSTs act on nothing.
        val before = store.listAgentTokens().size
        assertEquals(HttpStatusCode.Forbidden, b.post("/tokens", mapOf("name" to "x", "scope" to "heart")).status)
        assertEquals(before, store.listAgentTokens().size)
    }

    @Test fun setupStoresTheHashBurnsTheCodeAndSignsIn() = withFreshServer { code ->
        val b = Browser(this)
        b.get("/setup")
        val anonymous = b.cookie!!
        val r = b.setup(code)
        assertEquals(r.bodyAsText(), HttpStatusCode.SeeOther, r.status)
        assertEquals("/", r.headers[HttpHeaders.Location])
        assertNotEquals("session id rotated", anonymous, b.cookie)
        assertEquals(HttpStatusCode.OK, b.get("/").status)

        val hash = store.getSetting(Settings.ADMIN_HASH)!!
        assertTrue(BCrypt.verifyer().verify(NEW_PASSWORD.toCharArray(), hash).verified)
        assertNull("code burned", store.getSetting(Settings.SETUP_CODE_HASH))

        // The pre-setup id is dead.
        assertEquals(HttpStatusCode.SeeOther, Browser(this).apply { cookie = anonymous }.get("/").status)
        // Setup is now closed; the same code cannot set another password.
        val again = Browser(this)
        val closed = again.get("/setup")
        assertEquals(HttpStatusCode.SeeOther, closed.status)
        assertEquals("/login", closed.headers[HttpHeaders.Location])
        again.get("/login")
        val replay = again.post("/setup", mapOf("code" to code, "password" to "attacker password", "confirm" to "attacker password"))
        assertNotEquals(HttpStatusCode.OK, replay.status)
        assertEquals(hash, store.getSetting(Settings.ADMIN_HASH))
        // And the new password works for a normal login.
        assertEquals(HttpStatusCode.SeeOther, Browser(this).login(NEW_PASSWORD).status)
        assertTrue("code never logged", store.accessLog(100).none { code in it.detail })
    }

    @Test fun wrongOrExpiredCodesAreRefused() = withFreshServer { code ->
        val b = Browser(this)
        b.get("/setup")
        val wrong = b.setup("00000-00000-00000-00000-00000-00000")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue(wrong.bodyAsText().contains("did not match"))
        assertNull(store.getSetting(Settings.ADMIN_HASH))

        clock.advance(SetupCode.TTL_MS)
        b.get("/setup") // the idle form session has expired too
        val expired = b.setup(code)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertNull(store.getSetting(Settings.ADMIN_HASH))

        // A regenerated code works.
        val fresh = SetupCode(store, clock).issue().code
        assertEquals(HttpStatusCode.SeeOther, b.setup(fresh).status)
        assertNotNull(store.getSetting(Settings.ADMIN_HASH))
    }

    @Test fun passwordRulesDoNotBurnTheCode() = withFreshServer { code ->
        val b = Browser(this)
        b.get("/setup")
        val cases = listOf(
            Triple("short", "short", "at least 10"),
            Triple("x".repeat(73), "x".repeat(73), "72 bytes"),
            Triple("é".repeat(37), "é".repeat(37), "72 bytes"), // 74 bytes in UTF-8
            Triple(NEW_PASSWORD, "something else entirely", "do not match"),
        )
        for ((pw, confirm, msg) in cases) {
            val r = b.setup(code, pw, confirm)
            assertEquals(msg, HttpStatusCode.BadRequest, r.status)
            assertTrue(msg, r.bodyAsText().contains(msg))
        }
        assertNull(store.getSetting(Settings.ADMIN_HASH))
        assertNotNull(store.getSetting(Settings.SETUP_CODE_HASH))
        assertEquals(HttpStatusCode.SeeOther, b.setup(code).status)
    }

    @Test fun setupAttemptsAreRateLimited() = withFreshServer { code ->
        val b = Browser(this)
        b.get("/setup")
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, b.setup("00000-00000-00000-00000-0000$it-00000").status) }
        val limited = b.setup(code)
        assertEquals("even the right code", HttpStatusCode.TooManyRequests, limited.status)
        assertNull(store.getSetting(Settings.ADMIN_HASH))
        clock.advance(15 * 60_000L)
        assertEquals(HttpStatusCode.SeeOther, b.setup(code).status)
    }

    @Test fun setupNeedsCsrfAndSameOrigin() = withFreshServer { code ->
        val b = Browser(this)
        b.get("/setup")
        val good = b.lastCsrf
        val form = mapOf("code" to code, "password" to NEW_PASSWORD, "confirm" to NEW_PASSWORD)
        assertEquals(HttpStatusCode.Forbidden, b.post("/setup", form, csrf = null).status)
        assertEquals(HttpStatusCode.Forbidden, b.post("/setup", form, csrf = "x".repeat(43)).status)
        assertEquals(HttpStatusCode.Forbidden, b.post("/setup", form, csrf = good, origin = "https://evil.example").status)
        assertEquals(HttpStatusCode.Forbidden, b.post("/setup", form, csrf = good, origin = null).status)
        assertEquals(HttpStatusCode.Forbidden, Browser(this).post("/setup", form, csrf = good).status)
        assertNull(store.getSetting(Settings.ADMIN_HASH))
        assertEquals(HttpStatusCode.SeeOther, b.post("/setup", form, csrf = good).status)
    }

    @Test fun deviceAndAgentApisAreUnaffectedBeforeSetup() = withFreshServer {
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        // Without tokens they answer 401 as always, not a redirect to setup.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/data/samples?metric=HEART_RATE").status)
    }
}
