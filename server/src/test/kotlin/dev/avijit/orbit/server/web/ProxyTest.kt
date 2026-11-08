package dev.avijit.orbit.server.web

import dev.avijit.orbit.server.Cidr
import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.ProxyMode
import dev.avijit.orbit.server.api.ApiEnv
import dev.avijit.orbit.server.api.withApi
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.TrustMode
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PUBLIC = "https://orbit.example.com"
private const val PEER_HEADER = "X-Test-Peer"
private const val CADDY = "172.18.0.2"

/** Proxy mode where the simulated TCP peer comes from a test header (the test engine has no real peer). */
private val COMPOSE = listOf(Cidr.parse("172.16.0.0/12"))
private fun testProxy() = ProxyMode(PUBLIC, COMPOSE) { call -> call.request.headers[PEER_HEADER] ?: CADDY }

private fun withProxyDashboard(limits: Limits = Limits(), block: suspend ApiEnv.() -> Unit) =
    withApi(limits = limits, fingerprint = null, publicUrl = PUBLIC, proxy = testProxy()) {
        setAdminPassword()
        block()
    }

/** A browser reaching the server through Caddy: https at the edge, its real address in X-Forwarded-For. */
private fun ApiEnv.viaCaddy(client: String = "203.0.113.7", peer: String = CADDY) = Browser(this, origin = PUBLIC).apply {
    extraHeaders = mapOf(PEER_HEADER to peer, "X-Forwarded-For" to client, "X-Forwarded-Proto" to "https")
}

class ProxyTest {

    // ---------------------------------------------------------------- pure address logic

    @Test fun cidrsMatchV4AndV6WithoutDns() {
        val docker = Cidr.parse("172.16.0.0/12")
        assertTrue(docker.contains("172.18.0.2"))
        assertTrue(docker.contains("172.31.255.255"))
        assertFalse(docker.contains("172.32.0.1"))
        assertFalse(docker.contains("8.8.8.8"))
        assertFalse("names are never resolved", docker.contains("localhost"))
        assertTrue(Cidr.parse("::1/128").contains("::1"))
        assertTrue(Cidr.parse("fd00::/8").contains("fd12:3456::1"))
        assertFalse(Cidr.parse("fd00::/8").contains("2001:db8::1"))
        assertTrue("IPv4-mapped IPv6 is IPv4", Cidr.parse("127.0.0.0/8").contains("::ffff:127.0.0.1"))
        assertTrue(Cidr.parse("10.1.2.3").contains("10.1.2.3"))
        for (bad in listOf("", "10.0.0.0/33", "10.0.0/8", "example.com/8", "::1/129", "10.0.0.0/-1", "10.0.0.0/x")) {
            try { Cidr.parse(bad); org.junit.Assert.fail(bad) } catch (e: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun forwardedHeadersAreIgnoredFromUntrustedSources() {
        val p = ProxyMode(PUBLIC, listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16").map(Cidr::parse))
        // From the proxy: the client it reports.
        assertEquals("203.0.113.7", p.resolveClient(CADDY, listOf("203.0.113.7")))
        // From anyone else: the TCP peer, whatever the headers claim.
        assertEquals("198.51.100.9", p.resolveClient("198.51.100.9", listOf("203.0.113.7")))
        assertEquals("198.51.100.9", p.resolveClient("198.51.100.9", listOf("127.0.0.1")))
        // A spoofed left-hand entry is skipped: the rightmost address the trusted proxy saw wins.
        assertEquals("203.0.113.7", p.resolveClient(CADDY, listOf("1.2.3.4, 203.0.113.7")))
        assertEquals("203.0.113.7", p.resolveClient(CADDY, listOf("1.2.3.4", "203.0.113.7, 10.0.0.5")))
        // Missing or garbage: the peer.
        assertEquals(CADDY, p.resolveClient(CADDY, emptyList()))
        assertEquals(CADDY, p.resolveClient(CADDY, listOf("not-an-ip")))
        assertEquals("2001:db8::1", p.resolveClient(CADDY, listOf("[2001:db8::1]")))
        assertEquals("203.0.113.7", p.resolveClient(CADDY, listOf("203.0.113.7:51234")))
        assertTrue(p.isTrusted("127.0.0.1"))
        assertTrue(p.isTrusted("::1"))
        assertTrue(p.isTrusted("10.0.0.5"))
        assertTrue(p.isTrusted("192.168.1.2"))
        assertFalse(p.isTrusted("203.0.113.7"))
        // Every hop trusted: the TCP peer, never the client-controlled leftmost entry.
        assertEquals(CADDY, p.resolveClient(CADDY, listOf("10.0.0.7, 172.18.0.9")))
        assertEquals(CADDY, p.resolveClient(CADDY, listOf("127.0.0.1")))
        // A custom list replaces the defaults.
        val narrow = ProxyMode(PUBLIC, listOf(Cidr.parse("172.20.0.0/16")))
        assertEquals("10.0.0.5", narrow.resolveClient("10.0.0.5", listOf("203.0.113.7")))
        assertEquals("203.0.113.7", narrow.resolveClient("172.20.0.3", listOf("203.0.113.7")))
    }

    @Test fun loopbackOnlyDefaultIgnoresForwardedHeadersFromDockerPeers() {
        val p = ProxyMode(PUBLIC) // DEFAULT_TRUSTED
        assertEquals("172.18.0.2", p.resolveClient("172.18.0.2", listOf("203.0.113.7")))
        assertEquals("10.0.0.5", p.resolveClient("10.0.0.5", listOf("203.0.113.7")))
        assertEquals("192.168.1.2", p.resolveClient("192.168.1.2", listOf("203.0.113.7")))
        assertEquals("203.0.113.7", p.resolveClient("127.0.0.1", listOf("203.0.113.7")))
        assertEquals("203.0.113.7", p.resolveClient("::1", listOf("203.0.113.7")))
    }

    @Test fun loopbackOnlyDefaultRefusesDashboardPostsFromDockerPeers() =
        withApi(fingerprint = null, publicUrl = PUBLIC, proxy = ProxyMode(PUBLIC) { it.request.headers[PEER_HEADER] ?: CADDY }) {
            setAdminPassword()
            val b = viaCaddy()
            b.get("/login")
            assertEquals("172.x is not trusted by default", HttpStatusCode.Forbidden, b.post("/login", mapOf("password" to PASSWORD)).status)
            assertEquals(HttpStatusCode.SeeOther, viaCaddy(peer = "127.0.0.1").login().status)
        }

    @Test fun setupWorksThroughTheProxyOnlyFromTheTrustedProxyOverHttps() =
        withApi(fingerprint = null, publicUrl = PUBLIC, proxy = testProxy()) {
            val code = dev.avijit.orbit.server.SetupCode(store, clock).issue().code
            val form = mapOf("code" to code, "password" to "a brand new password", "confirm" to "a brand new password")

            val untrusted = viaCaddy(peer = "198.51.100.9")
            untrusted.get("/setup")
            assertEquals(HttpStatusCode.Forbidden, untrusted.post("/setup", form).status)
            val plain = viaCaddy().apply { extraHeaders = extraHeaders - "X-Forwarded-Proto" }
            plain.get("/setup")
            assertEquals(HttpStatusCode.Forbidden, plain.post("/setup", form).status)
            assertNull(store.getSetting(dev.avijit.orbit.server.Settings.ADMIN_HASH))

            // The limiter keys on the forwarded client: one client exhausts its attempts, another is unaffected.
            val a = viaCaddy(client = "203.0.113.7")
            a.get("/setup")
            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, a.post("/setup", form + ("code" to "00000-00000-00000-00000-00000-0000$it")).status) }
            assertEquals(HttpStatusCode.TooManyRequests, a.post("/setup", form).status)

            val b = viaCaddy(client = "198.51.100.20")
            b.get("/setup")
            val ok = b.post("/setup", form)
            assertEquals(HttpStatusCode.SeeOther, ok.status)
            assertEquals("/", ok.headers[HttpHeaders.Location])
            assertNotNull(store.getSetting(dev.avijit.orbit.server.Settings.ADMIN_HASH))
        }

    // ---------------------------------------------------------------- dashboard behind the proxy

    @Test fun dashboardWorksThroughTheProxyWithSecureCookies() = withProxyDashboard {
        val b = viaCaddy()
        assertEquals(HttpStatusCode.SeeOther, b.login().status)
        val sc = b.lastSetCookie!!
        assertTrue(sc, sc.startsWith("__Host-") && "Secure" in sc && "HttpOnly" in sc)
        assertEquals(HttpStatusCode.OK, b.get("/").status)
    }

    @Test fun postsNeedForwardedHttpsFromATrustedProxy() = withProxyDashboard {
        val plain = viaCaddy().apply { extraHeaders = extraHeaders + ("X-Forwarded-Proto" to "http") }
        plain.get("/login")
        assertEquals(HttpStatusCode.Forbidden, plain.post("/login", mapOf("password" to dev.avijit.orbit.server.web.PASSWORD)).status)

        val none = viaCaddy().apply { extraHeaders = extraHeaders - "X-Forwarded-Proto" }
        none.get("/login")
        assertEquals(HttpStatusCode.Forbidden, none.post("/login", mapOf("password" to dev.avijit.orbit.server.web.PASSWORD)).status)

        // Someone reaching the internal port directly cannot claim https.
        val direct = viaCaddy(peer = "198.51.100.9")
        direct.get("/login")
        assertEquals(HttpStatusCode.Forbidden, direct.post("/login", mapOf("password" to dev.avijit.orbit.server.web.PASSWORD)).status)

        assertEquals(HttpStatusCode.SeeOther, viaCaddy().login().status)
    }

    @Test fun originChecksUseThePublicUrl() = withProxyDashboard {
        val wrong = viaCaddy().let { Browser(this, origin = "http://localhost").apply { extraHeaders = it.extraHeaders } }
        wrong.get("/login")
        assertEquals(HttpStatusCode.Forbidden, wrong.post("/login", mapOf("password" to dev.avijit.orbit.server.web.PASSWORD)).status)
        val evil = viaCaddy().let { Browser(this, origin = "https://evil.example").apply { extraHeaders = it.extraHeaders } }
        evil.get("/login")
        assertEquals(HttpStatusCode.Forbidden, evil.post("/login", mapOf("password" to dev.avijit.orbit.server.web.PASSWORD)).status)
        assertEquals(HttpStatusCode.SeeOther, viaCaddy().login().status)
    }

    @Test fun loginLimitsKeyOnTheForwardedClient() = withProxyDashboard {
        val a = viaCaddy(client = "203.0.113.7")
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, a.login("wrong password $it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, a.login().status)
        // Another client behind the same proxy is not locked out.
        assertEquals(HttpStatusCode.SeeOther, viaCaddy(client = "198.51.100.20").login().status)
    }

    @Test fun pairingUriModeIsCaBehindAProxy() = withProxyDashboard {
        val b = viaCaddy()
        b.login()
        val html = b.get("/pair").bodyAsText()
        val link = Regex("""orbit://pair\?[^"<\s]+""").find(html)!!.value.replace("&amp;", "&")
        val uri = PairingUri.parse(link)!!
        assertEquals(TrustMode.CA, uri.mode)
        assertEquals(PUBLIC, uri.url)
        assertNull(uri.fingerprint)
        assertTrue(html, html.contains("Public certificate"))
        // The token redeems once.
        val r = pair(token = uri.token)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
    }

    @Test fun pairingRateLimitKeysOnTheForwardedClient() = withProxyDashboard(limits = Limits(pairAttemptsPerMinute = 2)) {
        suspend fun attempt(client: String, peer: String = CADDY) = this.client.post("/v1/pair") {
            contentType(ContentType.Application.Json)
            header(PEER_HEADER, peer); header("X-Forwarded-For", client)
            setBody("""{"token":"nope-nope-nope-nope","deviceName":"x"}""")
        }.status
        assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.7"))
        assertEquals(HttpStatusCode.Unauthorized, attempt("203.0.113.7"))
        assertEquals(HttpStatusCode.TooManyRequests, attempt("203.0.113.7"))
        assertEquals(HttpStatusCode.Unauthorized, attempt("198.51.100.20"))
        // Untrusted peer: XFF ignored, keyed by the peer.
        assertEquals(HttpStatusCode.Unauthorized, attempt("1.1.1.1", peer = "198.51.100.9"))
        assertEquals(HttpStatusCode.Unauthorized, attempt("1.1.1.2", peer = "198.51.100.9"))
        assertEquals(HttpStatusCode.TooManyRequests, attempt("1.1.1.3", peer = "198.51.100.9"))
    }

    @Test fun tokensPageNeedsNoSelfSignedCertBehindAProxy() = withProxyDashboard {
        val b = viaCaddy()
        b.login()
        b.get("/tokens")
        val html = b.post("/tokens", mapOf("name" to "claude-code", "scope" to "heart")).bodyAsText()
        assertTrue(html, html.contains("$PUBLIC/mcp"))
        assertFalse(html, html.contains("NODE_EXTRA_CA_CERTS"))
        assertTrue(html, html.contains("public certificate"))
    }

    @Test fun selfSignedModeIsUnchanged() = withDashboard {
        val b = Browser(this)
        b.login()
        val html = b.get("/pair").bodyAsText()
        val link = Regex("""orbit://pair\?[^"<\s]+""").find(html)!!.value.replace("&amp;", "&")
        val uri = PairingUri.parse(link)!!
        assertEquals(TrustMode.PIN, uri.mode)
        assertNotNull(uri.fingerprint)
        assertFalse(link.contains("m="))
    }
}
