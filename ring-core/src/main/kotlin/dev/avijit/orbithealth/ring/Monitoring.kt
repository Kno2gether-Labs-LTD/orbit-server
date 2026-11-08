package dev.avijit.orbithealth.ring

/** What a ring can measure automatically on a schedule. A family declares its kinds in [RingCapabilities.monitoring]. */
enum class MonitorKind { HEART_RATE, SPO2, BLOOD_PRESSURE, HRV, TEMPERATURE, STRESS }

/**
 * One automatic measurement: on or off, and how often. [overnightOnly] limits it to the night, and only means something
 * for a kind whose [MonitorSpec.supportsWindow] is true (families without a time window ignore it).
 */
data class MonitorSetting(val enabled: Boolean, val intervalMin: Int, val overnightOnly: Boolean = false)

/**
 * The ring's automatic-measurement schedule. Kinds that are absent are left as they are (when applying) or unknown
 * (when read back).
 */
data class MonitoringConfig(val settings: Map<MonitorKind, MonitorSetting>) {
    operator fun get(kind: MonitorKind): MonitorSetting? = settings[kind]

    companion object {
        val EMPTY = MonitoringConfig(emptyMap())
    }
}
