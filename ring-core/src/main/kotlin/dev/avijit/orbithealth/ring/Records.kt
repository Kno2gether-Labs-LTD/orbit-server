package dev.avijit.orbithealth.ring

import java.time.Instant

/** STEPS, DISTANCE_M and CALORIES_KCAL samples are per-interval amounts (30-min buckets from the ring). */
enum class Metric { HEART_RATE, HRV, SPO2, STRESS, BLOOD_PRESSURE_SYS, BLOOD_PRESSURE_DIA, RESPIRATORY_RATE, STEPS, DISTANCE_M, CALORIES_KCAL,
    /** The ring's running step count for the day so far (cumulative, resets at midnight), not an interval. */
    STEPS_DAY_TOTAL }

data class Sample(val metric: Metric, val time: Instant, val value: Double, val source: SampleSource)

enum class SampleSource { HISTORY, LIVE, MEASURED }

enum class SleepStage { AWAKE, REM, LIGHT, DEEP }

data class SleepSegment(val stage: SleepStage, val start: Instant, val durationSec: Int)

data class SleepSession(val start: Instant, val end: Instant, val segments: List<SleepSegment>)

/** Total seconds spent asleep: sum of durationSec over all non-AWAKE segments. */
val SleepSession.totalSleepSec: Int
    get() = segments.filter { it.stage != SleepStage.AWAKE }.sumOf { it.durationSec }

data class WorkoutRecord(val start: Instant, val end: Instant, val steps: Int, val distanceM: Int, val kcal: Int, val mode: Int?, val avgHr: Int?)

data class DeviceInfo(val deviceId: Int, val firmware: String, val batteryPct: Int)

data class HistoryBatch(val samples: List<Sample>, val sleep: List<SleepSession>, val workouts: List<WorkoutRecord>)

data class UserProfile(val heightCm: Int, val weightKg: Int, val sex: Sex, val age: Int)

enum class Sex { MALE, FEMALE, UNSPECIFIED }
