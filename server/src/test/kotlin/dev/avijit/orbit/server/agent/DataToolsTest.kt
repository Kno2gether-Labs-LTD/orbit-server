package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.AccessLog
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.api.MutableClock
import dev.avijit.orbit.server.api.T0
import dev.avijit.orbit.server.withTempDir
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** T0 is 2026-05-28T20:26:40Z; tests run in UTC. */
class DataToolsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 5, 28)
    private fun at(date: LocalDate, h: Int, m: Int = 0): Long = date.atTime(h, m).toInstant(zone).toEpochMilli()
    private fun s(metric: String, ms: Long, v: Double) = SampleDto(metric, ms, v, "HISTORY")

    private class Env(val store: Store, val clock: MutableClock, val tools: StoreDataTools)

    private fun env(scopes: Set<String> = Scope.ALL_WIRE, block: Env.() -> Unit) = withTempDir { dir ->
        Store(ServerHome(dir).dbFile).use { store ->
            val clock = MutableClock(T0)
            Env(store, clock, StoreDataTools(store, AccessLog(store, clock), Caller("Pixel 9", scopes), zone, clock)).block()
        }
    }

    @Test fun stepsDailyUsesMaxOfBucketsAndDayTotal() = env {
        val d1 = today.minusDays(1)
        store.insertSamples(listOf(
            s("STEPS", at(d1, 9), 1000.0), s("STEPS", at(d1, 14), 2000.0),
            s("STEPS_DAY_TOTAL", at(d1, 20), 2500.0),
            s("STEPS_DAY_TOTAL", at(today, 0, 10), 3200.0), // read just after midnight: yesterday's final count
            s("STEPS", at(today, 8), 500.0),
            s("STEPS_DAY_TOTAL", at(today, 18), 4000.0),
        ), null)
        val r = tools.getMetric("STEPS", Instant.ofEpochMilli(at(d1, 0)), Instant.ofEpochMilli(at(today, 23, 59)), Agg.DAILY)
        assertEquals("daily", r.agg)
        assertEquals(listOf(3200.0, 4000.0), r.points.map { it.value })
        assertEquals(listOf(at(d1, 0), at(today, 0)), r.points.map { it.epochMs })

        val sum = tools.getMetric("STEPS", Instant.ofEpochMilli(at(d1, 0)), Instant.ofEpochMilli(at(today, 23, 59)), Agg.SUM)
        assertEquals(7200.0, sum.points.single().value, 0.0)
    }

    @Test fun avgMinMaxAndHourly() = env {
        store.insertSamples(listOf(
            s("HEART_RATE", at(today, 10, 0), 60.0), s("HEART_RATE", at(today, 10, 30), 70.0), s("HEART_RATE", at(today, 11, 15), 80.0),
        ), null)
        val from = Instant.ofEpochMilli(at(today, 0)); val to = Instant.ofEpochMilli(T0)
        assertEquals(70.0, tools.getMetric("HEART_RATE", from, to, Agg.AVG).points.single().value, 1e-9)
        assertEquals(60.0, tools.getMetric("HEART_RATE", from, to, Agg.MIN).points.single().value, 0.0)
        val max = tools.getMetric("HEART_RATE", from, to, Agg.MAX)
        assertEquals(80.0, max.points.single().value, 0.0)
        assertEquals(3, max.rows)
        val hourly = tools.getMetric("HEART_RATE", from, to, Agg.HOURLY)
        assertEquals(listOf(65.0, 80.0), hourly.points.map { it.value })
        assertEquals(listOf(2, 1), hourly.points.map { it.count })
    }

    @Test fun rawIsCappedAndAggregatedInstead() = env {
        val start = at(today.minusDays(2), 0)
        store.insertSamples((0 until 2000).map { s("HEART_RATE", start + it * 60_000L, 60.0 + it % 10) }, null)
        val r = tools.getMetric("HEART_RATE", Instant.ofEpochMilli(start), Instant.ofEpochMilli(T0), Agg.RAW)
        assertTrue(r.points.size <= StoreDataTools.MAX_POINTS)
        assertEquals("hourly", r.agg)
        assertNotNull(r.note)
        assertEquals(2000, r.rows)

        val few = tools.getMetric("HEART_RATE", Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 9 * 60_000L), Agg.RAW)
        assertEquals("raw", few.agg)
        assertEquals(10, few.points.size)
    }

    @Test fun badArgumentsAreRejected() = env {
        val now = Instant.ofEpochMilli(T0)
        for (call in listOf<() -> Unit>(
            { tools.getMetric("GLUCOSE", now.minusSeconds(60), now, Agg.AVG) },
            { tools.getMetric("HEART_RATE", now, now.minusSeconds(60), Agg.AVG) },
            { tools.getMetric("HEART_RATE", now.minusSeconds(500L * 86_400), now, Agg.DAILY) },
            { tools.compareToBaseline("HEART_RATE", 0) },
        )) {
            try { call(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { /* ok */ }
        }
    }

    @Test fun sleepSummaryTotalsStages() = env {
        val start = at(today.minusDays(1), 23)
        store.insertSleep(listOf(SleepDto(start, start + 8 * 3_600_000L, listOf(
            SegmentDto("LIGHT", start, 3 * 3600), SegmentDto("DEEP", start + 3 * 3_600_000L, 3600),
            SegmentDto("REM", start + 4 * 3_600_000L, 5400), SegmentDto("AWAKE", start + 5_400_000L * 3, 1800),
        ))), null)
        val night = tools.getSleep(today.minusDays(1))!!
        assertEquals(330, night.asleepMin)
        assertEquals(480, night.inBedMin)
        assertEquals(mapOf("LIGHT" to 180, "DEEP" to 60, "REM" to 90, "AWAKE" to 30), night.stagesMin)
        assertEquals(start, night.startMs)
        assertNull(tools.getSleep(today.minusDays(3)))
    }

    @Test fun baselineComparesTodayWithPreviousDays() = env {
        store.insertSamples(listOf(
            s("HEART_RATE", at(today.minusDays(3), 12), 60.0),
            s("HEART_RATE", at(today.minusDays(2), 12), 62.0),
            s("HEART_RATE", at(today.minusDays(1), 12), 64.0),
            s("HEART_RATE", at(today.minusDays(4), 12), 99.0), // outside the 3-day baseline
            s("HEART_RATE", at(today, 9), 68.0), s("HEART_RATE", at(today, 10), 72.0),
        ), null)
        val c = tools.compareToBaseline("HEART_RATE", 3)
        assertEquals(70.0, c.today!!, 1e-9)
        assertEquals(62.0, c.baselineMean!!, 1e-9)
        assertEquals(2.0, c.baselineSd!!, 1e-9)
        assertEquals(3, c.baselineDaysWithData)
        assertEquals(8.0, c.difference!!, 1e-9)
        assertEquals(4.0, c.zScore!!, 1e-9)
    }

    @Test fun dailySummaryOnlyIncludesScopedMetrics() = env(scopes = setOf("activity", "sleep")) {
        store.insertSamples(listOf(s("STEPS", at(today, 9), 1234.0), s("HEART_RATE", at(today, 9), 61.0)), null)
        val sum = tools.dailySummary(today)
        assertEquals(1234.0, sum["STEPS"]!!, 0.0)
        assertFalse(sum.keys.any { it.startsWith("HEART_RATE") })
    }

    @Test fun dailySummaryWithNoDataScopesIsANeutralDenial() = env(scopes = setOf("profile")) {
        try { tools.dailySummary(today); fail("expected ScopeDenied") } catch (e: ScopeDenied) { assertNull(e.scope) }
        val out = AgentTools(tools, zone, clock).call("daily_summary", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(out.isError)
        assertTrue(out.content, out.content.contains("No health data has been shared"))
        assertFalse(out.content, out.content.contains("heart"))
        assertTrue(store.accessLog(10).all { it.detail.startsWith("DENIED") })
    }

    @Test fun listMetricsRespectsScopesAndEveryCallIsLogged() = env(scopes = setOf("heart")) {
        store.insertSamples(listOf(s("HEART_RATE", at(today, 9), 61.0), s("STEPS", at(today, 9), 100.0)), null)
        assertEquals(listOf("HEART_RATE"), tools.listMetrics())
        tools.getMetric("HEART_RATE", Instant.ofEpochMilli(at(today, 0)), Instant.ofEpochMilli(T0), Agg.AVG)
        val log = store.accessLog(10)
        assertEquals(listOf("get_metric", "list_metrics"), log.map { it.action })
        assertTrue(log.all { it.actor == "Pixel 9" })
        assertTrue(log[0].detail, log[0].detail.contains("metric=HEART_RATE") && log[0].detail.contains("agg=avg"))
        assertFalse("no health values in the log", log[0].detail.contains("61"))
        assertEquals(1, log[0].rows)
    }

    @Test fun outOfScopeCallsThrowAndAreLogged() = env(scopes = setOf("sleep")) {
        try {
            tools.getMetric("HEART_RATE", Instant.ofEpochMilli(T0 - 3_600_000), Instant.ofEpochMilli(T0), Agg.AVG)
            fail("expected ScopeDenied")
        } catch (e: ScopeDenied) {
            assertEquals("heart", e.scope)
        }
        try { tools.compareToBaseline("STEPS", 7); fail("expected ScopeDenied") } catch (e: ScopeDenied) { assertEquals("activity", e.scope) }
        val log = store.accessLog(10)
        assertEquals(2, log.size)
        assertTrue(log.all { it.detail.startsWith("DENIED") && it.rows == 0 })
    }
}
