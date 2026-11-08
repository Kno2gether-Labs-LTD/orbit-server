package dev.avijit.orbithealth.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class RecordsTest {
    private val t0: Instant = Instant.parse("2026-11-05T22:00:00Z")

    @Test
    fun sampleEqualityIsByValue() {
        val a = Sample(Metric.HEART_RATE, t0, 62.0, SampleSource.HISTORY)
        val b = Sample(Metric.HEART_RATE, t0, 62.0, SampleSource.HISTORY)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(source = SampleSource.LIVE))
    }

    @Test
    fun sleepSessionDurationSumsSegments() {
        val session = SleepSession(
            start = t0,
            end = t0.plusSeconds(3600),
            segments = listOf(
                SleepSegment(SleepStage.AWAKE, t0, 300),
                SleepSegment(SleepStage.LIGHT, t0.plusSeconds(300), 1200),
                SleepSegment(SleepStage.DEEP, t0.plusSeconds(1500), 900),
                SleepSegment(SleepStage.REM, t0.plusSeconds(2400), 600),
                SleepSegment(SleepStage.AWAKE, t0.plusSeconds(3000), 600),
            ),
        )
        assertEquals(2700, session.totalSleepSec)
    }
}
