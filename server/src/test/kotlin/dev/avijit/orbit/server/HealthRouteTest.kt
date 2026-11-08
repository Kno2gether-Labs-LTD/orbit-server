package dev.avijit.orbit.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRouteTest {
    @Test fun healthReportsFingerprint() = withTempDir { dir ->
        Store(ServerHome(dir).dbFile).use { store ->
            testApplication {
                application { orbitModule(ServerDeps(store = store, fingerprint = "ab".repeat(32))) }
                val r = client.get("/health")
                assertEquals(HttpStatusCode.OK, r.status)
                assertEquals("""{"ok":true,"fingerprint":"${"ab".repeat(32)}"}""", r.bodyAsText())
            }
        }
    }
}
