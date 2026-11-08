package dev.avijit.orbithealth.ring

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** A family that implements only [RingDriver.measure] gets streaming and live heart rate from the defaults. */
class RingDriverDefaultsTest {
    /** Each [measure] call plays the next script (the last one repeats). */
    private class MeasureOnlyDriver(private val scripts: List<List<MeasureEvent>>) : RingDriver {
        var runs = 0
        override suspend fun handshake(profile: UserProfile) = error("unused")
        override suspend fun syncHistory() = error("unused")
        override suspend fun clearSyncedHistory(batch: HistoryBatch) = error("unused")
        override suspend fun readMonitoring(): MonitoringConfig? = null
        override suspend fun applyMonitoring(config: MonitoringConfig) = MonitoringConfig.EMPTY
        override fun measure(kind: MeasureKind): Flow<MeasureEvent> = flow {
            val script = scripts[minOf(runs, scripts.lastIndex)]
            runs++
            script.forEach { emit(it) }
        }
    }

    private fun hr(v: Double) = mapOf(Metric.HEART_RATE to v)

    @Test
    fun liveHeartRateStreamsAcrossRepeatedMeasurements() = runTest {
        val d = MeasureOnlyDriver(
            listOf(
                listOf(MeasureEvent.Started, MeasureEvent.Live(hr(70.0)), MeasureEvent.Result(hr(71.0))),
                listOf(MeasureEvent.Started, MeasureEvent.Failed(MeasureFailure.TIMEOUT)), // a gap: keep going
                listOf(MeasureEvent.Started, MeasureEvent.Result(hr(72.4))),
            ),
        )
        assertEquals(listOf(70, 71, 72, 72), d.liveHeartRate().take(4).toList())
        assertEquals(4, d.runs)
    }

    @Test
    fun measureContinuouslyEmitsStartedOnceAndNoResult() = runTest {
        val d = MeasureOnlyDriver(listOf(listOf(MeasureEvent.Started, MeasureEvent.Result(hr(60.0)))))
        assertEquals(
            listOf(MeasureEvent.Started, MeasureEvent.Live(hr(60.0)), MeasureEvent.Live(hr(60.0))),
            d.measureContinuously(MeasureKind.HEART_RATE).take(3).toList(),
        )
    }

    @Test
    fun failureEndsTheStream() = runTest {
        val d = MeasureOnlyDriver(listOf(listOf(MeasureEvent.Failed(MeasureFailure.NOT_WORN))))
        try {
            d.liveHeartRate().toList()
            fail("expected MeasureFailedException")
        } catch (e: MeasureFailedException) {
            assertEquals(MeasureFailure.NOT_WORN, e.reason)
        }
        // A timeout before anything started is a failure too, not a silent retry loop.
        val t = MeasureOnlyDriver(listOf(listOf(MeasureEvent.Failed(MeasureFailure.TIMEOUT))))
        assertEquals(listOf(MeasureEvent.Failed(MeasureFailure.TIMEOUT)), t.measureContinuously(MeasureKind.HEART_RATE).toList())
        assertEquals(1, t.runs)
    }
}
