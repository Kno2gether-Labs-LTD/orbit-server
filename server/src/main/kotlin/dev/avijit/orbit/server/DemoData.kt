package dev.avijit.orbit.server

import dev.avijit.orbit.server.agent.AgentSettings
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Invented demo data for screenshots and trying the dashboard: a week of plausible ring readings, two devices, two
 * agent tokens and some access-log rows. Deterministic (fixed seed). Never run against a real data directory: the
 * CLI refuses when samples already exist and requires an explicit acknowledgement.
 */
object DemoData {
    data class Counts(val samples: Int, val nights: Int)

    fun seed(store: Store, masterKey: MasterKey, clock: Clock, zone: ZoneId): Counts {
        val r = Random(42)
        val now = clock.millis()
        val today = LocalDate.now(clock.withZone(zone))
        val samples = ArrayList<SampleDto>()
        val sleep = ArrayList<SleepDto>()
        val h = 3_600_000L

        for (dayBack in 7 downTo 0) {
            val day = today.minusDays(dayBack.toLong())
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            // Night that ends this morning: 23:10 the day before to about 06:50.
            val sleepStart = dayStart - 50 * 60_000L + r.nextLong(-20, 20) * 60_000L
            val sleepEnd = dayStart + 6 * h + 50 * 60_000L + r.nextLong(-25, 25) * 60_000L
            if (sleepEnd < now) sleep += night(sleepStart, sleepEnd, r)
            var steps = 0.0
            var t = dayStart
            while (t < dayStart + 24 * h && t < now) {
                val hour = ((t - dayStart) / h).toInt()
                val asleep = t in sleepStart..sleepEnd || hour < 7
                val diurnal = sin((hour - 9) / 24.0 * 2 * PI)
                samples += SampleDto("HEART_RATE", t, (if (asleep) 53.0 else 66.0 + 6 * diurnal) + r.nextDouble(-4.0, 6.0), "HISTORY")
                if ((t - dayStart) % h == 0L) {
                    samples += SampleDto("HRV", t, (if (asleep) 56.0 else 44.0) + r.nextDouble(-7.0, 7.0), "HISTORY")
                    samples += SampleDto("SPO2", t, (96 + r.nextInt(0, 3)).toDouble(), "HISTORY")
                    if (!asleep) samples += SampleDto("STRESS", t, (28 + 10 * diurnal + r.nextDouble(-6.0, 9.0)).coerceIn(5.0, 90.0), "HISTORY")
                    if (asleep) samples += SampleDto("RESPIRATORY_RATE", t, 13.5 + r.nextDouble(-1.0, 1.5), "HISTORY")
                }
                if ((t - dayStart) % (30 * 60_000L) == 0L && !asleep) {
                    val bucket = (if (hour in 8..9 || hour in 17..18) r.nextInt(600, 1400) else r.nextInt(40, 420)).toDouble()
                    steps += bucket
                    samples += SampleDto("STEPS", t, bucket, "HISTORY")
                    samples += SampleDto("STEPS_DAY_TOTAL", t, steps, "HISTORY")
                }
                if (hour in setOf(8, 13, 20) && (t - dayStart) % h == 0L) {
                    samples += SampleDto("BLOOD_PRESSURE_SYS", t, (114 + r.nextInt(-4, 7)).toDouble(), "MEASURED")
                    samples += SampleDto("BLOOD_PRESSURE_DIA", t, (75 + r.nextInt(-3, 5)).toDouble(), "MEASURED")
                }
                t += 10 * 60_000L
            }
        }

        val phone = UUID.randomUUID().toString()
        store.insertDevice(DeviceRow(phone, "Pixel 9 Pro", Secrets.sha256Hex(Secrets.newToken()), Scope.ALL_WIRE - "profile",
            now - 5 * 24 * h, now - 4 * 60_000L, revoked = false))
        store.upsertDeviceInfo(phone, DeviceInfoDto(0xA1B2, "1.0.32", 82), now - 4 * 60_000L)
        store.insertDevice(DeviceRow(UUID.randomUUID().toString(), "Galaxy Tab S9", Secrets.sha256Hex(Secrets.newToken()), setOf("sleep"),
            now - 6 * 24 * h, now - 3 * 24 * h, revoked = true))
        store.insertSamples(samples, phone)
        store.insertSleep(sleep, phone)

        val tokens = AgentTokens(store, clock)
        tokens.create("claude-code", setOf("heart", "sleep", "activity"))
        tokens.create("weekly-report", Scope.ALL_WIRE)
        tokens.revoke(tokens.create("old-script", setOf("heart")).row.id)

        val log = listOf(
            Triple(2L, "claude-code", "get_metric" to "metric=HEART_RATE agg=daily from=2026-10-30 to=2026-11-05"),
            Triple(3L, "claude-code", "get_sleep" to "night=yesterday"),
            Triple(3L, "claude-code", "mcp_initialize" to "client=claude-code 2.1.0 protocol=2025-06-18"),
            Triple(41L, "Pixel 9 Pro", "agent_ask" to "outcome=ok model=llama3.2 tool_calls=2"),
            Triple(42L, "Pixel 9 Pro", "get_metric" to "metric=HRV agg=avg"),
            Triple(130L, "weekly-report", "compare_to_baseline" to "metric=HEART_RATE days=14"),
            Triple(131L, "weekly-report", "daily_summary" to "date=2026-11-05"),
            Triple(380L, "old-script", "data_samples" to "DENIED revoked token"),
            Triple(720L, "weekly-report", "list_metrics" to ""),
        )
        for ((minAgo, actor, ad) in log.reversed()) store.appendAccessLog(now - minAgo * 60_000L, actor, ad.first, ad.second, if (ad.second.startsWith("DENIED")) 0 else r.nextInt(1, 160))

        AgentSettings(store, masterKey, emptyMap()).save(ProviderKind.OPENAI_COMPAT, "http://localhost:11434/v1", "llama3.2", null)
        return Counts(samples.size, sleep.size)
    }

    /** A night of 90-minute cycles: light, deep (early), REM (later), with short wakes. */
    private fun night(start: Long, end: Long, r: Random): SleepDto {
        val segs = ArrayList<SegmentDto>()
        var t = start
        var cycle = 0
        while (t < end) {
            val plan = listOf(
                "LIGHT" to 25 + r.nextInt(0, 15),
                "DEEP" to (if (cycle < 2) 35 else 12) + r.nextInt(0, 10),
                "LIGHT" to 15 + r.nextInt(0, 10),
                "REM" to (if (cycle < 2) 12 else 28) + r.nextInt(0, 10),
                "AWAKE" to r.nextInt(2, 7),
            )
            for ((stage, min) in plan) {
                if (t >= end) break
                val dur = minOf(min * 60_000L, end - t)
                segs += SegmentDto(stage, t, (dur / 1000).toInt())
                t += dur
            }
            cycle++
        }
        return SleepDto(start, end, segs)
    }
}
