package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.Pairing
import dev.avijit.orbit.server.Secrets
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Settings
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.withTempDir
import dev.avijit.orbithealth.sync.PairRequest
import dev.avijit.orbithealth.sync.PairResponse
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.Scope
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingTest {

    @Test fun pairTokenIsSingleUse() = withApi {
        val token = newPairToken()
        val statuses = coroutineScope {
            (1..8).map { async(Dispatchers.IO) { pair(token, "Phone $it").status } }.awaitAll()
        }
        assertEquals(statuses.toString(), 1, statuses.count { it == HttpStatusCode.OK })
        assertEquals(statuses.toString(), 7, statuses.count { it == HttpStatusCode.Unauthorized })
        assertEquals(1, store.listDevices().size)
        // And once more, sequentially, after the dust settles.
        assertEquals(HttpStatusCode.Unauthorized, pair(token).status)
    }

    @Test fun pairTokenIsSingleUseAcrossThreads() = withTempDir { dir ->
        Store(ServerHome(dir).dbFile).use { store ->
            val pairing = Pairing(store, MutableClock(T0))
            val token = pairing.newToken("https://h:8443", FP, "n").token
            val threads = 32
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val results = (1..threads).map { i -> pool.submit<PairResponse?> { start.await(); pairing.redeem(token, "p$i") } }
            start.countDown()
            val ok = results.mapNotNull { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            assertEquals(1, ok.size)
            assertEquals(1, store.listDevices().size)
        }
    }

    @Test fun expiredPairTokenRejected() = withApi {
        val token = newPairToken()
        clock.advance(Pairing.TOKEN_TTL_MS + 1)
        val r = pair(token)
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(r.bodyAsText(), "invalid_pairing_token" in r.bodyAsText())
        assertTrue(store.listDevices().isEmpty())
    }

    @Test fun pairTokenValidJustBeforeExpiry() = withApi {
        val token = newPairToken()
        clock.advance(Pairing.TOKEN_TTL_MS - 1)
        assertEquals(HttpStatusCode.OK, pair(token).status)
    }

    @Test fun pairCreatesDeviceWithAllScopesAndHashedToken() = withApi {
        store.putSetting(Settings.SERVER_NAME, "Living Room Mac")
        val uriToken = newPairToken()
        val r = pair(uriToken, "Pixel 9")
        assertEquals(HttpStatusCode.OK, r.status)
        val resp = testJson.decodeFromString<PairResponse>(r.bodyAsText())
        assertEquals("Living Room Mac", resp.serverName)
        assertEquals(Scope.entries.map { it.wire }.toSet(), resp.scopes)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(resp.deviceToken))

        val dev = store.device(resp.deviceId)!!
        assertEquals("Pixel 9", dev.name)
        assertEquals(Secrets.sha256Hex(resp.deviceToken), dev.tokenHash)
        assertFalse(dev.revoked)
        assertEquals(T0, dev.createdMs)

        // Raw secrets never reach the database file.
        val dbBytes = String(Files.readAllBytes(dir(store)), Charsets.ISO_8859_1) +
            walText(store)
        assertFalse("device token stored in clear", resp.deviceToken in dbBytes)
        assertFalse("pairing token stored in clear", uriToken in dbBytes)
    }

    @Test fun newTokenBuildsAValidPairingUri() = withApi {
        val p = pairing.newToken("https://192.168.1.5:8443", FP, "Test Box")
        val parsed = PairingUri.parse(p.toUri())
        assertNotNull(parsed)
        assertEquals(p, parsed)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(p.token))
        assertTrue(p.token != pairing.newToken("https://192.168.1.5:8443", FP, "Test Box").token)
    }

    @Test fun malformedPairRequestsAreRejected() = withApi {
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/pair", "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/pair", """{"token":"x"}""").status)
        // Blank / control-only device name.
        assertEquals(HttpStatusCode.BadRequest, pair(name = " \u0000\n ").status)
        // A token that is not even token-shaped is just an invalid token.
        assertEquals(HttpStatusCode.Unauthorized, pair(token = "' OR 1=1 --").status)
        // Oversized body.
        val huge = testJson.encodeToString(PairRequest("a".repeat(10_000), "x"))
        assertEquals(HttpStatusCode.PayloadTooLarge, postJson("/v1/pair", huge).status)
    }

    @Test fun longDeviceNamesAreTruncatedAndControlCharsStripped() = withApi {
        val r = pair(name = "My\u0007 Phone\n" + "x".repeat(200))
        assertEquals(HttpStatusCode.OK, r.status)
        val dev = store.listDevices().single()
        assertTrue(dev.name, dev.name.startsWith("My Phone"))
        assertEquals(Pairing.MAX_DEVICE_NAME, dev.name.length)
    }

    @Test fun pairIsRateLimitedPerRemoteIp() = withApi(Limits(pairAttemptsPerMinute = 10)) {
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, pair(token = "wrong-token-$it-aaaaaaaaaaaa").status) }
        val token = newPairToken()
        assertEquals(HttpStatusCode.TooManyRequests, pair(token).status)
        clock.advance(60_001)
        assertEquals(HttpStatusCode.OK, pair(token).status)
    }

    @Test fun healthStillWorks() = withApi {
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(FP in r.bodyAsText())
    }

    @Test fun unknownBearerGets401() = withApi {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state") { header(HttpHeaders.Authorization, "Bearer nope") }.status)
        val pairToken = newPairToken()
        // A pairing token is not a device token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/state") { header(HttpHeaders.Authorization, "Bearer $pairToken") }.status)
        assertNull(store.deviceByTokenHash(Secrets.sha256Hex(pairToken)))
    }

    private fun dir(store: Store) = store.dbPath
    private fun walText(store: Store): String {
        val wal = store.dbPath.resolveSibling(store.dbPath.fileName.toString() + "-wal")
        return if (Files.exists(wal)) String(Files.readAllBytes(wal), Charsets.ISO_8859_1) else ""
    }
}
