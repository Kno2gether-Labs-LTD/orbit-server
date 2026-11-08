package dev.avijit.orbit.server

import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {
    private fun <T> withStore(block: (Store) -> T): T = withTempDir { dir ->
        Store(ServerHome(dir).dbFile).use(block)
    }

    private fun device(id: String = "d1") = DeviceRow(id, "Pixel", "hash-$id", setOf("heart", "activity"), 1_000, null, false)

    @Test fun storeUniqueKeysIgnoreDuplicates() = withStore { s ->
        s.insertDevice(device())
        val hr = SampleDto("HEART_RATE", 1_000, 60.0, "HISTORY")
        val first = s.insertSamples(listOf(hr, SampleDto("HEART_RATE", 2_000, 62.0, "HISTORY")), "d1")
        assertEquals(InsertCounts(2, 0), first)
        // Same key, different value: first-written wins for non-activity metrics.
        val again = s.insertSamples(listOf(hr.copy(value = 99.0)), "d1")
        assertEquals(InsertCounts(0, 1), again)
        assertEquals(listOf(60.0, 62.0), s.samples("HEART_RATE", 0, 10_000).map { it.value })

        val night = SleepDto(10_000, 20_000, listOf(SegmentDto("LIGHT", 10_000, 600), SegmentDto("DEEP", 10_600, 300)))
        assertEquals(InsertCounts(1, 0), s.insertSleep(listOf(night), "d1"))
        // Re-sent session with a later end and an extra segment: no new session, end extended, segment added.
        val later = night.copy(endMs = 25_000, segments = night.segments + SegmentDto("REM", 20_000, 300))
        assertEquals(InsertCounts(0, 1), s.insertSleep(listOf(later), "d1"))
        val stored = s.sleepSessions(0, 100_000).single()
        assertEquals(25_000, stored.endMs)
        assertEquals(3, stored.segments.size)
        assertEquals(10_000L, s.lastSleepStartMs())
    }

    @Test fun activityMetricsKeepTheLargerValue() = withStore { s ->
        s.insertSamples(listOf(SampleDto("STEPS", 1_000, 100.0, "HISTORY"), SampleDto("STEPS_DAY_TOTAL", 1_000, 500.0, "HISTORY")), null)
        s.insertSamples(listOf(SampleDto("STEPS", 1_000, 250.0, "HISTORY"), SampleDto("STEPS_DAY_TOTAL", 1_000, 400.0, "HISTORY")), null)
        assertEquals(250.0, s.samples("STEPS", 0, 2_000).single().value, 0.0)
        assertEquals(500.0, s.samples("STEPS_DAY_TOTAL", 0, 2_000).single().value, 0.0)
        assertEquals(mapOf("STEPS" to 1_000L, "STEPS_DAY_TOTAL" to 1_000L), s.latestEpochByMetric())
    }

    @Test fun pairTokensAreSingleUseAndExpire() = withStore { s ->
        s.insertPairToken("t1", expiresMs = 5_000)
        assertFalse("expired", s.consumePairToken("t1", nowMs = 6_000))
        s.insertPairToken("t2", expiresMs = 5_000)
        assertTrue(s.consumePairToken("t2", nowMs = 1_000))
        assertFalse("single use", s.consumePairToken("t2", nowMs = 1_000))
        assertFalse("unknown", s.consumePairToken("nope", nowMs = 1_000))
    }

    @Test fun devicesAgentTokensSettingsAndLog() = withStore { s ->
        s.insertDevice(device())
        assertEquals("d1", s.deviceByTokenHash("hash-d1")?.id)
        s.updateDeviceScopes("d1", setOf("sleep"))
        s.touchDevice("d1", 7_000)
        s.revokeDevice("d1")
        val d = s.listDevices().single()
        assertEquals(setOf("sleep"), d.scopes)
        assertEquals(7_000L, d.lastSeenMs)
        assertTrue(d.revoked)

        s.upsertDeviceInfo("d1", DeviceInfoDto(42, "1.0", 80), 1_000)
        s.upsertDeviceInfo("d1", DeviceInfoDto(42, "1.1", 75), 2_000)
        assertEquals(DeviceInfoRow("d1", 42, "1.1", 75, 2_000), s.deviceInfo("d1"))

        s.insertAgentToken(AgentTokenRow("a1", "claude code", "ah", setOf("heart"), 1_000, false))
        assertEquals("a1", s.agentTokenByHash("ah")?.id)
        s.revokeAgentToken("a1")
        assertTrue(s.listAgentTokens().single().revoked)

        assertNull(s.getSetting("k"))
        s.putSetting("k", "v1"); s.putSetting("k", "v2")
        assertEquals("v2", s.getSetting("k"))

        s.appendAccessLog(1_000, "agent:a1", "mcp.get_metric", "HEART_RATE", 12)
        s.appendAccessLog(2_000, "device:d1", "agent.ask", "", 3)
        val log = s.accessLog(limit = 10)
        assertEquals(listOf(2_000L, 1_000L), log.map { it.atMs })
        assertEquals(1, s.accessLog(limit = 10, sinceMs = 1_500).size)
    }

    @Test fun sqlInjectionIsInert() = withStore { s ->
        s.putSetting("x'); DROP TABLE settings; --", "v")
        assertEquals("v", s.getSetting("x'); DROP TABLE settings; --"))
    }

    @Test fun aVersion1DatabaseGainsTheDeviceZoneColumns() = withTempDir { dir ->
        val file = ServerHome(dir).dbFile
        java.nio.file.Files.createDirectories(file.parent)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use {
                it.execute("""CREATE TABLE devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, scopes TEXT NOT NULL,
                    created_ms INTEGER NOT NULL, last_seen_ms INTEGER, revoked INTEGER NOT NULL DEFAULT 0)""")
                it.execute("INSERT INTO devices VALUES ('d1','Pixel','h1','heart',1,NULL,0)")
                it.execute("PRAGMA user_version = 1")
            }
        }
        Store(file).use { s ->
            assertNull(s.device("d1")!!.zone)
            assertNull(s.latestDeviceZone())
            s.setDeviceZone("d1", "Asia/Kolkata", 5)
            assertEquals("Asia/Kolkata", s.device("d1")!!.zone)
            assertEquals("Asia/Kolkata", s.latestDeviceZone())
            s.revokeDevice("d1")
            assertNull("a revoked phone's zone no longer counts", s.latestDeviceZone())
        }
        Store(file).use { s -> assertEquals("reopening is idempotent", "Asia/Kolkata", s.device("d1")!!.zone) }
    }
}
