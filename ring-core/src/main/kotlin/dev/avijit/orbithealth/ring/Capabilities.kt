package dev.avijit.orbithealth.ring

/**
 * What the paired ring can do. The single source of truth for the UI: screens show only what is declared here
 * (the Measure sheet lists only [measure] keys, the schedule screen only [monitoring] keys and their intervals).
 *
 * Returned by [RingDriver.handshake]; families combine what the ring reports (e.g. a support bitmap) with their
 * own defaults.
 */
data class RingCapabilities(
    /** Metrics the ring records in its own history and returns from [RingDriver.syncHistory]. */
    val history: Set<Metric>,
    /** The ring records sleep sessions. */
    val sleep: Boolean,
    /** The ring records workouts. */
    val workouts: Boolean,
    /** The ring can stream live values (e.g. [RingDriver.liveHeartRate]). */
    val realtime: Boolean,
    /** On-demand measurements ([RingDriver.measure]); a kind that is absent is not offered. */
    val measure: Map<MeasureKind, MeasureSpec> = emptyMap(),
    /** Automatic measurements the app can schedule ([RingDriver.applyMonitoring]); absent kinds are not offered. */
    val monitoring: Map<MonitorKind, MonitorSpec> = emptyMap(),
    /** [RingDriver.clearSyncedHistory] really deletes synced history on the ring. */
    val canClearHistory: Boolean = false,
)

/** How one on-demand measurement behaves. */
data class MeasureSpec(
    /** Typical time to a result, for the countdown ring. */
    val typicalSeconds: Int,
    /** The measurement streams [MeasureEvent.Live] values while it runs. */
    val live: Boolean,
    /** The result is an estimate (e.g. blood pressure, stress) and must be labelled ESTIMATE in the UI. */
    val estimate: Boolean,
)

/** How one automatic measurement can be scheduled. */
data class MonitorSpec(
    /** Allowed intervals in minutes, ascending; the picker offers only these. */
    val intervalsMin: List<Int>,
    /** The monitor can be switched off. */
    val canTurnOff: Boolean,
    /** The monitor supports a time window (e.g. SpO₂ "overnight only"). */
    val supportsWindow: Boolean,
)
