package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.MasterKey
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.Pairing
import dev.avijit.orbit.server.ProxyMode
import dev.avijit.orbit.server.ServerDeps
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.orbitModule
import dev.avijit.orbit.server.withTempDir
import dev.avijit.orbithealth.sync.PairRequest
import dev.avijit.orbithealth.sync.PairResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move. */
class MutableClock(@Volatile var nowMs: Long) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(ms: Long) { nowMs += ms }
}

val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

const val T0 = 1_780_000_000_000L // 2026-05-28, an arbitrary fixed "now"
const val FP = "ab0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd"

class ApiEnv(val deps: ServerDeps, val clock: MutableClock, val builder: ApplicationTestBuilder) {
    val store: Store get() = deps.store
    val client: HttpClient get() = builder.client
    val pairing = Pairing(store, clock)

    fun newPairToken(): String = pairing.newToken("https://192.168.1.5:8443", FP, "Test Box").token

    suspend fun postJson(path: String, body: String, bearer: String? = null): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        setBody(body)
    }

    suspend fun pair(token: String = newPairToken(), name: String = "Pixel 9"): HttpResponse =
        postJson("/v1/pair", testJson.encodeToString(PairRequest(token, name)))

    /** Pairs a new device and returns its response (device token etc.). */
    suspend fun pairedDevice(name: String = "Pixel 9"): PairResponse {
        val r = pair(name = name)
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        return testJson.decodeFromString(r.bodyAsText())
    }
}

/**
 * A test server over a fresh home. [env] replaces the process environment (so `ORBIT_SCRIPTED_AGENT` on the
 * developer's machine never leaks in); the zone is UTC.
 */
fun withApi(
    limits: Limits = Limits(),
    env: Map<String, String> = emptyMap(),
    providerFactory: ProviderFactory? = null,
    fingerprint: String? = FP,
    publicUrl: String? = null,
    proxy: ProxyMode? = null,
    block: suspend ApiEnv.() -> Unit,
) = withTempDir { dir ->
    val home = ServerHome(dir)
    Store(home.dbFile).use { store ->
        val clock = MutableClock(T0)
        val deps = ServerDeps(
            store = store, fingerprint = fingerprint, masterKey = MasterKey(home), clock = clock, limits = limits,
            zone = ZoneOffset.UTC, env = env, providerFactory = providerFactory, publicUrl = publicUrl, proxy = proxy,
        )
        testApplication {
            application { orbitModule(deps) }
            ApiEnv(deps, clock, this).block()
        }
    }
}
