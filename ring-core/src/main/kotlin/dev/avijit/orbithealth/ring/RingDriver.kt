package dev.avijit.orbithealth.ring

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlin.math.roundToInt

/** Driver contract v2: one implementation per [RingFamily]. */
interface RingDriver {
    /** Sets time, time zone and profile; returns the ring's identity and what it can do. */
    suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities>

    /** Reads all ring history without deleting anything. Throws [SyncFailed]. */
    suspend fun syncHistory(): HistoryBatch

    /**
     * Deletes from the ring only the history types fully received by the most recent successful
     * [syncHistory], and only if [batch] is the exact instance that call returned (reference
     * identity). Callers MUST persist [batch] before calling this. No-op for any other batch, if
     * there was no successful sync since the last clear, or if the latest sync failed.
     */
    suspend fun clearSyncedHistory(batch: HistoryBatch)

    /**
     * Live bpm until cancelled ("Measure now"): the [MeasureEvent.Live] heart-rate values of
     * [measureContinuously]`(HEART_RATE)`. Throws [MeasureFailedException] if the measurement fails (the ring can't
     * start, the link drops, ...). Families get this for free; override only for a dedicated live-HR command.
     */
    fun liveHeartRate(): Flow<Int> = measureContinuously(MeasureKind.HEART_RATE).transform { e ->
        when (e) {
            is MeasureEvent.Live -> e.values[Metric.HEART_RATE]?.let { emit(it.roundToInt()) }
            is MeasureEvent.Failed -> throw MeasureFailedException(e.reason)
            else -> Unit
        }
    }

    /**
     * Streaming mode of [measure]: emits [MeasureEvent.Started], then [MeasureEvent.Live] values until the collector
     * cancels (which stops the measurement on the ring). It never emits a [MeasureEvent.Result]; a
     * [MeasureEvent.Failed] ends it.
     *
     * The default runs [measure] back to back, turning each Result into a Live value and restarting after a Result or
     * a TIMEOUT (Started is emitted once); any other failure is emitted and ends the flow. Families whose ring streams
     * natively (e.g. YCBT keeps sending bpm until told to stop) override it so the ring is started and stopped once.
     */
    fun measureContinuously(kind: MeasureKind): Flow<MeasureEvent> = flow {
        var started = false
        while (true) {
            var again = false
            measure(kind).collect { e ->
                when (e) {
                    MeasureEvent.Started -> if (!started) { started = true; emit(e) }
                    is MeasureEvent.Live -> emit(e)
                    is MeasureEvent.Result -> { emit(MeasureEvent.Live(e.values)); again = true }
                    is MeasureEvent.Failed -> if (started && e.reason == MeasureFailure.TIMEOUT) again = true else emit(e)
                }
            }
            if (!again) return@flow
        }
    }

    /**
     * Measures [kind] once on demand. Emits [MeasureEvent.Started], any [MeasureEvent.Live] values, then one
     * [MeasureEvent.Result] or [MeasureEvent.Failed], and completes. A kind the ring can't measure emits only
     * `Failed(UNSUPPORTED)`. Cancelling the collection stops the measurement on the ring. Never throws for ring
     * failures (they are [MeasureEvent.Failed]).
     */
    fun measure(kind: MeasureKind): Flow<MeasureEvent>

    /** The ring's current automatic-measurement schedule, or null when the family can't read it back. */
    suspend fun readMonitoring(): MonitoringConfig?

    /**
     * Applies [config] (kinds absent from it are left as they are) and returns what the ring actually accepted.
     * A kind the ring says it can't do (unsupported) is simply left out of the result and not worth retrying. A kind
     * the ring refused for another (possibly transient) reason throws [MonitoringNotApplied] after the other kinds
     * were sent, carrying what was accepted, so the caller retries later. Throws on a link failure.
     */
    suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig
}

class SyncFailed(reason: String) : Exception(reason)

/** [RingDriver.applyMonitoring] could not apply every kind for a reason worth retrying; [accepted] did go through. */
class MonitoringNotApplied(val accepted: MonitoringConfig, val refused: Set<MonitorKind>) :
    Exception("ring refused monitoring for $refused")

/** A streaming measurement (e.g. [RingDriver.liveHeartRate]) failed with [reason]. */
class MeasureFailedException(val reason: MeasureFailure) : Exception("measurement failed: $reason")
