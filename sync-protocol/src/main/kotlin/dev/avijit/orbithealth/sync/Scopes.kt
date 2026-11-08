package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.ring.Metric

/** Data scopes a paired device may be granted. Sleep sessions use SLEEP; PROFILE covers device info/profile. */
enum class Scope {
    HEART, SLEEP, ACTIVITY, STRESS, SPO2, PROFILE;

    val wire: String get() = name.lowercase()

    companion object {
        /** Exact wire name ("heart") to scope; anything else is null. */
        fun fromWire(s: String): Scope? = entries.firstOrNull { it.wire == s }

        val ALL_WIRE: Set<String> = entries.map { it.wire }.toSet()
    }
}

fun Metric.scope(): Scope = when (this) {
    Metric.HEART_RATE, Metric.HRV, Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA,
    Metric.RESPIRATORY_RATE -> Scope.HEART
    Metric.SPO2 -> Scope.SPO2
    Metric.STRESS -> Scope.STRESS
    Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL, Metric.STEPS_DAY_TOTAL -> Scope.ACTIVITY
}
