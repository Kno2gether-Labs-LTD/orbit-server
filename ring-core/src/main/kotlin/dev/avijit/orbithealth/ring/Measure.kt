package dev.avijit.orbithealth.ring

/**
 * What can be measured on demand. A family declares which kinds it supports in [RingCapabilities.measure].
 *
 * Reserved for families that support them (add when a family implements them, not before): TEMPERATURE,
 * RESPIRATORY_RATE.
 */
enum class MeasureKind { HEART_RATE, SPO2, HRV, STRESS, BLOOD_PRESSURE }

/**
 * Progress of one on-demand measurement ([RingDriver.measure]). A flow emits [Started] first (unless it fails
 * straight away), any number of [Live] values, then exactly one terminal [Result] or [Failed], and completes.
 */
sealed interface MeasureEvent {
    /** The ring accepted the request and is measuring. */
    data object Started : MeasureEvent

    /** An interim value while measuring, e.g. bpm. Not saved. */
    data class Live(val values: Map<Metric, Double>) : MeasureEvent

    /** The final reading, e.g. HEART_RATE, or BLOOD_PRESSURE_SYS plus BLOOD_PRESSURE_DIA. */
    data class Result(val values: Map<Metric, Double>) : MeasureEvent

    data class Failed(val reason: MeasureFailure) : MeasureEvent
}

/** Why a measurement produced no result. The UI maps each to specific, human copy. */
enum class MeasureFailure {
    /** The ring reports it is not on a finger. */
    NOT_WORN,
    /** No result within the family's time limit. */
    TIMEOUT,
    /** The ring is busy with another measurement or a sync. */
    BUSY,
    /** This ring (family or model) can't measure that kind. */
    UNSUPPORTED,
    /** The link dropped. */
    DISCONNECTED,
    /** The ring reported a failure or sent no usable value. */
    RING_ERROR,
}
