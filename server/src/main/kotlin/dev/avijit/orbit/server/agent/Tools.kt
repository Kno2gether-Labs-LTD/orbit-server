package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.AccessLog
import dev.avijit.orbit.server.Store
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SleepDto
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.scope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** How [DataTools.getMetric] summarises samples. */
enum class Agg {
    RAW, HOURLY, DAILY, AVG, MIN, MAX, SUM;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(s: String): Agg? = entries.firstOrNull { it.wire == s.trim().lowercase() }
    }
}

/** One value: a sample (RAW), a bucket starting at [epochMs] (HOURLY/DAILY) or a statistic over the range. */
data class Point(val epochMs: Long, val value: Double, val count: Int = 1)

/** [agg] is what was actually applied (RAW may be aggregated to stay under the point cap; [note] says so). */
data class MetricSeries(
    val metric: String, val unit: String, val estimate: Boolean, val agg: String,
    val fromMs: Long, val toMs: Long, val points: List<Point>, val rows: Int, val note: String? = null,
)

/** Sleep for the night that began on [night] (sessions starting between noon that day and noon the next). */
data class SleepSummary(
    val night: String, val startMs: Long, val endMs: Long, val inBedMin: Int, val asleepMin: Int,
    val stagesMin: Map<String, Int>, val sessions: Int,
)

/** One night for the data API: the summary plus the raw sessions and segments. */
data class SleepNight(val summary: SleepSummary, val sessions: List<SleepDto>)

/** Today's daily value against the mean and sample SD of the previous [days] days that have data. */
data class BaselineCompare(
    val metric: String, val unit: String, val estimate: Boolean, val days: Int, val today: Double?,
    val baselineMean: Double?, val baselineSd: Double?, val baselineDaysWithData: Int,
    val difference: Double?, val zScore: Double?,
)

/**
 * The caller lacks the scope (wire name, e.g. "heart") the call needs, or, with a null [scope], has no health-data
 * scope at all. The message is safe to log.
 */
class ScopeDenied(val scope: String?) : RuntimeException("scope_denied: ${scope ?: "no data scopes"}")

/** Who is reading: [actor] goes into the access log (device or agent-token name); [scopes] are wire names. */
data class Caller(val actor: String, val scopes: Set<String>)

/**
 * Read-only health-data tools shared by the server agent and MCP (S5). All arithmetic happens here, never in a model.
 * Each call checks the caller's scopes (throwing [ScopeDenied]) and appends an access-log row; bad arguments
 * throw [IllegalArgumentException] with a message safe to show the model.
 */
interface DataTools {
    /** Metrics with data inside the caller's scopes, plus "SLEEP" when sleep is shared and recorded. */
    fun listMetrics(): List<String>
    fun getMetric(metric: String, from: Instant, to: Instant, agg: Agg): MetricSeries
    fun getSleep(night: LocalDate): SleepSummary?
    /** Nights [from]..[to] (by the date each night began) that have sleep, with raw sessions. One log row. */
    fun getSleepRange(from: LocalDate, to: LocalDate): List<SleepNight>
    fun dailySummary(date: LocalDate): Map<String, Double>
    fun compareToBaseline(metric: String, days: Int): BaselineCompare
}

/**
 * [DataTools] over the [Store], for one [caller]. Days are local days in [zone].
 *
 * Daily rules match the app: steps = max(sum of STEPS buckets, largest STEPS_DAY_TOTAL of the day), where a total
 * stamped within 15 minutes after midnight counts for the previous day; DISTANCE_M and CALORIES_KCAL are summed;
 * STEPS_DAY_TOTAL takes the day's maximum; every other metric is the mean.
 */
class StoreDataTools(
    private val store: Store,
    private val accessLog: AccessLog,
    private val caller: Caller,
    private val zone: ZoneId,
    private val clock: Clock,
    private val maxPoints: Int = MAX_POINTS,
) : DataTools {

    override fun listMetrics(): List<String> = logged("list_metrics", "", scope = null) {
        val counts = store.metricCounts()
        val metrics = Metric.entries.filter { it.scope().wire in caller.scopes && (counts[it.name] ?: 0) > 0 }.map { it.name }
        val sleep = if (Scope.SLEEP.wire in caller.scopes && store.lastSleepStartMs() != null) listOf("SLEEP") else emptyList()
        val out = metrics + sleep
        out to out.size
    }

    override fun getMetric(metric: String, from: Instant, to: Instant, agg: Agg): MetricSeries {
        val m = parseMetric(metric)
        require(!to.isBefore(from)) { "'to' is before 'from'" }
        require(Duration.between(from, to) <= MAX_RANGE) { "range is longer than ${MAX_RANGE.toDays()} days" }
        val detail = "metric=${m.name} from=$from to=$to agg=${agg.wire}"
        return logged("get_metric", detail, m.scope()) {
            val s = series(m, from.toEpochMilli(), to.toEpochMilli(), agg)
            s to s.rows
        }
    }

    override fun getSleep(night: LocalDate): SleepSummary? = logged("get_sleep", "night=$night", Scope.SLEEP) {
        val s = sleep(night)
        s to (s?.sessions ?: 0)
    }

    override fun getSleepRange(from: LocalDate, to: LocalDate): List<SleepNight> {
        require(!to.isBefore(from)) { "'to' is before 'from'" }
        require(ChronoUnit.DAYS.between(from, to) <= MAX_RANGE.toDays()) { "range is longer than ${MAX_RANGE.toDays()} days" }
        return logged("get_sleep_range", "from=$from to=$to", Scope.SLEEP) {
            val sessions = store.sleepSessions(noonMs(from), noonMs(to.plusDays(1)) - 1)
            val nights = sessions.groupBy { localDate(it.startMs - NOON_MS) }.toSortedMap()
                .map { (night, ss) -> SleepNight(summarize(night, ss), ss) }
            nights to sessions.size
        }
    }

    override fun dailySummary(date: LocalDate): Map<String, Double> {
        val shared = caller.scopes.mapNotNull(Scope::fromWire).filter { it != Scope.PROFILE }.toSet()
        if (shared.isEmpty()) {
            accessLog.record(caller.actor, "daily_summary", "DENIED date=$date", 0)
            throw ScopeDenied(null)
        }
        return logged("daily_summary", "date=$date", scope = null) {
            val out = LinkedHashMap<String, Double>()
            var rows = 0
            fun daily(m: Metric, key: String = m.name) {
                if (m.scope() !in shared) return
                val (values, n) = dailyValues(m, date, date)
                rows += n
                values.firstOrNull()?.let { out[key] = it.value }
            }
            fun stats(m: Metric, suffix: String = "", vararg kinds: Agg) {
                if (m.scope() !in shared) return
                val samples = store.samples(m.name, dayStartMs(date), dayStartMs(date.plusDays(1)) - 1)
                rows += samples.size
                if (samples.isEmpty()) return
                for (k in kinds) out["${m.name}_${k.wire}$suffix"] = stat(samples.map { it.value }, k)
            }
            daily(Metric.STEPS); daily(Metric.DISTANCE_M); daily(Metric.CALORIES_KCAL)
            stats(Metric.HEART_RATE, "", Agg.AVG, Agg.MIN, Agg.MAX)
            stats(Metric.HRV, "", Agg.AVG)
            stats(Metric.RESPIRATORY_RATE, "", Agg.AVG)
            stats(Metric.SPO2, "", Agg.AVG, Agg.MIN)
            stats(Metric.STRESS, "_estimate", Agg.AVG)
            stats(Metric.BLOOD_PRESSURE_SYS, "_estimate", Agg.AVG)
            stats(Metric.BLOOD_PRESSURE_DIA, "_estimate", Agg.AVG)
            if (Scope.SLEEP in shared) {
                sleep(date.minusDays(1))?.let {
                    rows += it.sessions
                    out["SLEEP_asleep_min"] = it.asleepMin.toDouble()
                    out["SLEEP_in_bed_min"] = it.inBedMin.toDouble()
                }
            }
            out to rows
        }
    }

    override fun compareToBaseline(metric: String, days: Int): BaselineCompare {
        val m = parseMetric(metric)
        require(days in 1..MAX_BASELINE_DAYS) { "days must be between 1 and $MAX_BASELINE_DAYS" }
        return logged("compare_to_baseline", "metric=${m.name} days=$days", m.scope()) {
            val today = LocalDate.now(clock.withZone(zone))
            val (todayValues, n1) = dailyValues(m, today, today)
            val (base, n2) = dailyValues(m, today.minusDays(days.toLong()), today.minusDays(1))
            val values = base.map { it.value }
            val mean = values.takeIf { it.isNotEmpty() }?.average()
            val sd = if (values.size >= 2) sqrt(values.sumOf { (it - mean!!) * (it - mean) } / (values.size - 1)) else null
            val t = todayValues.firstOrNull()?.value
            val diff = if (t != null && mean != null) t - mean else null
            val z = if (diff != null && sd != null && sd > 0) diff / sd else null
            BaselineCompare(m.name, unit(m), m in ESTIMATED, days, t, mean, sd, values.size, diff, z) to (n1 + n2)
        }
    }

    // ---- computation (no logging) ----

    private fun series(m: Metric, fromMs: Long, toMs: Long, agg: Agg): MetricSeries {
        fun out(a: String, points: List<Point>, rows: Int, note: String? = null) =
            MetricSeries(m.name, unit(m), m in ESTIMATED, a, fromMs, toMs, points, rows, note)
        val fromDay = localDate(fromMs)
        val toDay = localDate(toMs)
        return when (agg) {
            Agg.DAILY -> dailyValues(m, fromDay, toDay).let { (p, n) -> out("daily", p, n, wholeDaysNote(m)) }
            Agg.RAW, Agg.HOURLY -> {
                val raw = store.samples(m.name, fromMs, toMs)
                when {
                    agg == Agg.RAW && raw.size <= maxPoints -> out("raw", raw.map { Point(it.epochMs, it.value) }, raw.size)
                    else -> {
                        val hourly = hourly(m, raw)
                        val why = if (agg == Agg.RAW) "${raw.size} samples" else "${hourly.size} hours"
                        if (hourly.size <= maxPoints) {
                            out("hourly", hourly, raw.size, if (agg == Agg.RAW) "$why exceed the $maxPoints-point cap; returned hourly ${bucketWord(m)}." else null)
                        } else {
                            val (daily, n) = dailyValues(m, fromDay, toDay)
                            out("daily", daily, maxOf(n, raw.size), "$why exceed the $maxPoints-point cap; returned daily ${bucketWord(m)}.")
                        }
                    }
                }
            }
            Agg.AVG, Agg.MIN, Agg.MAX, Agg.SUM -> {
                if (m in DAILY_FIRST) {
                    val (daily, n) = dailyValues(m, fromDay, toDay)
                    val points = if (daily.isEmpty()) emptyList() else listOf(Point(fromMs, stat(daily.map { it.value }, agg), daily.size))
                    out(agg.wire, points, n, "Computed over ${daily.size} daily totals (local days $fromDay to $toDay); count = days.")
                } else {
                    val raw = store.samples(m.name, fromMs, toMs)
                    val points = if (raw.isEmpty()) emptyList() else listOf(Point(fromMs, stat(raw.map { it.value }, agg), raw.size))
                    out(agg.wire, points, raw.size)
                }
            }
        }
    }

    private fun hourly(m: Metric, raw: List<SampleDto>): List<Point> =
        raw.groupBy { Instant.ofEpochMilli(it.epochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli() }
            .toSortedMap().map { (hour, ss) -> Point(hour, bucket(m, ss.map { it.value }), ss.size) }

    /** One point per local day in [first]..[last] that has data, plus the number of rows read. */
    private fun dailyValues(m: Metric, first: LocalDate, last: LocalDate): Pair<List<Point>, Int> {
        val start = dayStartMs(first)
        val end = dayStartMs(last.plusDays(1))
        val co = CARRY_OVER.toMillis()
        return when (m) {
            Metric.STEPS -> {
                val buckets = store.samples(Metric.STEPS.name, start, end - 1).groupBy { localDate(it.epochMs) }
                val totals = store.samples(Metric.STEPS_DAY_TOTAL.name, start + co, end + co - 1).groupBy { localDate(it.epochMs - co) }
                val days = (buckets.keys + totals.keys).sorted()
                val points = days.map { d ->
                    val sum = buckets[d].orEmpty().sumOf { it.value }.roundToInt()
                    val total = totals[d].orEmpty().maxOfOrNull { it.value }?.roundToInt() ?: 0
                    Point(dayStartMs(d), maxOf(sum, total).toDouble(), buckets[d].orEmpty().size + totals[d].orEmpty().size)
                }
                points to (buckets.values.sumOf { it.size } + totals.values.sumOf { it.size })
            }
            Metric.STEPS_DAY_TOTAL -> {
                val totals = store.samples(m.name, start + co, end + co - 1)
                totals.groupBy { localDate(it.epochMs - co) }.toSortedMap()
                    .map { (d, ss) -> Point(dayStartMs(d), ss.maxOf { it.value }, ss.size) } to totals.size
            }
            else -> {
                val raw = store.samples(m.name, start, end - 1)
                raw.groupBy { localDate(it.epochMs) }.toSortedMap()
                    .map { (d, ss) -> Point(dayStartMs(d), bucket(m, ss.map { it.value }), ss.size) } to raw.size
            }
        }
    }

    private fun noonMs(d: LocalDate): Long = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sleep(night: LocalDate): SleepSummary? {
        val sessions = store.sleepSessions(noonMs(night), noonMs(night.plusDays(1)) - 1)
        if (sessions.isEmpty()) return null
        return summarize(night, sessions)
    }

    private fun summarize(night: LocalDate, sessions: List<SleepDto>): SleepSummary {
        val stages = LinkedHashMap<String, Int>()
        for (seg in sessions.flatMap { it.segments }) stages[seg.stage] = (stages[seg.stage] ?: 0) + seg.durationSec
        val asleepSec = sessions.flatMap { it.segments }.filter { it.stage != "AWAKE" }.sumOf { it.durationSec }
        val inBedMs = sessions.sumOf { it.endMs - it.startMs }
        return SleepSummary(
            night = night.toString(),
            startMs = sessions.minOf { it.startMs },
            endMs = sessions.maxOf { it.endMs },
            inBedMin = (inBedMs / 60_000.0).roundToInt(),
            asleepMin = (asleepSec / 60.0).roundToInt(),
            stagesMin = stages.mapValues { (_, sec) -> (sec / 60.0).roundToInt() },
            sessions = sessions.size,
        )
    }

    // ---- plumbing ----

    private fun <T> logged(action: String, detail: String, scope: Scope?, block: () -> Pair<T, Int>): T {
        if (scope != null && scope.wire !in caller.scopes) {
            accessLog.record(caller.actor, action, "DENIED $detail".trim(), 0)
            throw ScopeDenied(scope.wire)
        }
        val (result, rows) = block()
        accessLog.record(caller.actor, action, detail, rows)
        return result
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun dayStartMs(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun bucket(m: Metric, values: List<Double>): Double = when (m) {
        Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL -> values.sum()
        Metric.STEPS_DAY_TOTAL -> values.max()
        else -> values.average()
    }

    private fun bucketWord(m: Metric) = when (m) {
        Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL -> "sums"
        Metric.STEPS_DAY_TOTAL -> "maxima"
        else -> "means"
    }

    private fun wholeDaysNote(m: Metric) =
        if (m == Metric.STEPS) "Whole local days; steps per day = max(sum of 30-min buckets, ring's day total)." else "Whole local days."

    companion object {
        const val MAX_POINTS = 500
        const val MAX_BASELINE_DAYS = 90
        val MAX_RANGE: Duration = Duration.ofDays(400)
        /** As in the app's DailySteps: a day total stamped this soon after midnight is the previous day's. */
        val CARRY_OVER: Duration = Duration.ofMinutes(15)
        private const val NOON_MS = 12 * 3_600_000L
        private val DAILY_FIRST = setOf(Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL, Metric.STEPS_DAY_TOTAL)
        val ESTIMATED = setOf(Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA, Metric.STRESS)

        fun parseMetric(s: String): Metric =
            Metric.entries.firstOrNull { it.name == s.trim().uppercase() } ?: throw IllegalArgumentException("unknown metric '${s.take(40)}'")

        fun unit(m: Metric): String = when (m) {
            Metric.HEART_RATE -> "bpm"
            Metric.HRV -> "ms"
            Metric.SPO2 -> "%"
            Metric.STRESS -> "score 0-100"
            Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA -> "mmHg"
            Metric.RESPIRATORY_RATE -> "breaths/min"
            Metric.STEPS, Metric.STEPS_DAY_TOTAL -> "steps"
            Metric.DISTANCE_M -> "m"
            Metric.CALORIES_KCAL -> "kcal"
        }

        fun stat(values: List<Double>, agg: Agg): Double = when (agg) {
            Agg.MIN -> values.min()
            Agg.MAX -> values.max()
            Agg.SUM -> values.sum()
            else -> values.average()
        }
    }
}

/** Result of one tool call, as sent back to the model. */
data class ToolOutcome(val content: String, val isError: Boolean)

/**
 * The agent-facing tool surface over [DataTools]: JSON-schema [specs] and [call], which parses model arguments,
 * runs the tool and renders a compact JSON result (local times, values rounded to 0.1). Failures become error
 * results the model can read, never exceptions.
 */
class AgentTools(private val data: DataTools, private val zone: ZoneId, private val clock: Clock) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "get_metric",
            "Read one health metric over a time range, aggregated on the server. Use agg=avg/min/max/sum for a single " +
                "number over the range, hourly/daily for a series, raw for individual readings (capped at " +
                "${StoreDataTools.MAX_POINTS} points; larger results come back aggregated). For STEPS, DISTANCE_M and " +
                "CALORIES_KCAL, avg/min/max/sum are computed over whole local days. BLOOD_PRESSURE_* and STRESS are estimates.",
            schema(
                required = listOf("metric"),
                "metric" to enumProp("Metric name.", Metric.entries.map { it.name }),
                "from" to strProp("Start: ISO date (YYYY-MM-DD) or local date-time (YYYY-MM-DDTHH:MM). Default: 24 hours before 'to'."),
                "to" to strProp("End: ISO date (whole day included), local date-time, or 'now'. Default: now."),
                "agg" to enumProp("Aggregation. Default raw.", Agg.entries.map { it.wire }),
            ),
        ),
        ToolSpec(
            "get_sleep",
            "Sleep for one night: start and end time, minutes asleep and in bed, and minutes per stage (DEEP, LIGHT, REM, AWAKE). " +
                "A night is named by the date it began.",
            schema(required = emptyList(), "night" to strProp("Date the night began (YYYY-MM-DD), or 'last'. Default: last night.")),
        ),
        ToolSpec(
            "daily_summary",
            "Key values for one local day: steps, distance, calories, heart rate avg/min/max, HRV, SpO2, stress and blood " +
                "pressure (estimates), and the previous night's sleep. Only shared metrics with data appear.",
            schema(required = emptyList(), "date" to strProp("YYYY-MM-DD, 'today' or 'yesterday'. Default: today.")),
        ),
        ToolSpec(
            "compare_to_baseline",
            "Compare today's daily value of a metric with the mean and standard deviation of the previous N days " +
                "(difference and z-score are computed for you).",
            schema(
                required = listOf("metric"),
                "metric" to enumProp("Metric name.", Metric.entries.map { it.name }),
                "days" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", StoreDataTools.MAX_BASELINE_DAYS); put("description", "Baseline length in days. Default 14.") },
            ),
        ),
        ToolSpec("list_metrics", "List the metrics that have data and are shared with you.", schema(required = emptyList())),
    )

    fun call(name: String, args: JsonObject): ToolOutcome = try {
        ToolOutcome(run(name, args).toString(), isError = false)
    } catch (e: UnknownTool) {
        failure("unknown_tool", "No tool named '${name.take(40)}'.")
    } catch (e: ScopeDenied) {
        val scope = e.scope
        if (scope == null) failure("scope_denied", "No health data has been shared with this agent.")
        else failure("scope_denied", "The user has not shared $scope data with Orbit Server.", "scope" to scope)
    } catch (e: IllegalArgumentException) {
        failure("bad_arguments", e.message ?: "invalid arguments")
    } catch (e: DateTimeException) {
        failure("bad_arguments", "invalid date or time")
    }

    private fun run(name: String, a: JsonObject): JsonObject = when (name) {
        "get_metric" -> {
            val now = clock.instant()
            val to = a.str("to")?.let { parseTime(it, end = true) } ?: now
            val from = a.str("from")?.let { parseTime(it, end = false) } ?: to.minus(Duration.ofHours(24))
            val aggText = a.str("agg") ?: a.str("aggregation") ?: "raw"
            val agg = Agg.fromWire(aggText) ?: throw IllegalArgumentException("unknown agg '${aggText.take(20)}'")
            val s = data.getMetric(a.str("metric") ?: throw IllegalArgumentException("metric is required"), from, to, agg)
            buildJsonObject {
                put("metric", s.metric); put("unit", s.unit); put("estimate", s.estimate); put("aggregation", s.agg)
                put("from", local(s.fromMs)); put("to", local(s.toMs)); put("timezone", zone.id); put("rows", s.rows)
                putJsonArray("points") {
                    for (p in s.points) addJsonObject { put("time", local(p.epochMs)); put("value", round1(p.value)); put("n", p.count) }
                }
                if (s.points.isEmpty()) put("note", "No data in this range.") else s.note?.let { put("note", it) }
            }
        }
        "get_sleep" -> {
            val night = a.str("night")?.let(::parseDate) ?: today().minusDays(1)
            val s = data.getSleep(night)
            buildJsonObject {
                put("night", night.toString())
                put("found", s != null)
                if (s != null) {
                    put("start", local(s.startMs)); put("end", local(s.endMs)); put("timezone", zone.id)
                    put("asleep_min", s.asleepMin); put("in_bed_min", s.inBedMin); put("sessions", s.sessions)
                    putJsonObject("stages_min") { s.stagesMin.forEach { (k, v) -> put(k, v) } }
                }
            }
        }
        "daily_summary" -> {
            val d = a.str("date")?.let(::parseDate) ?: today()
            val sum = data.dailySummary(d)
            buildJsonObject {
                put("date", d.toString())
                putJsonObject("values") { sum.forEach { (k, v) -> put(k, round1(v)) } }
                put("note", if (sum.isEmpty()) "No shared data for this day." else "Keys ending in _estimate are estimates. SLEEP_* is the night before this date.")
            }
        }
        "compare_to_baseline" -> {
            val days = a["days"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: throw IllegalArgumentException("days must be a number") } ?: 14
            val c = data.compareToBaseline(a.str("metric") ?: throw IllegalArgumentException("metric is required"), days)
            buildJsonObject {
                put("metric", c.metric); put("unit", c.unit); put("estimate", c.estimate); put("days", c.days)
                put("today", c.today?.let(::round1)); put("baseline_mean", c.baselineMean?.let(::round1))
                put("baseline_sd", c.baselineSd?.let(::round1)); put("baseline_days_with_data", c.baselineDaysWithData)
                put("difference", c.difference?.let(::round1)); put("z_score", c.zScore?.let { (it * 100).roundToLong() / 100.0 })
                if (c.today == null) put("note", "No data for today yet.")
            }
        }
        "list_metrics" -> buildJsonObject { putJsonArray("metrics") { data.listMetrics().forEach { add(JsonPrimitive(it)) } } }
        else -> throw UnknownTool()
    }

    private class UnknownTool : IllegalArgumentException("unknown tool")

    private fun failure(code: String, message: String, vararg extra: Pair<String, String>): ToolOutcome {
        val body = buildJsonObject {
            put("error", code)
            put("message", message)
            extra.forEach { (k, v) -> put(k, v) }
        }
        return ToolOutcome(body.toString(), isError = true)
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    /** "today", "yesterday"/"last", or an ISO date. */
    fun parseDate(s: String): LocalDate = when (s.trim().lowercase()) {
        "today" -> today()
        "yesterday", "last", "last night", "lastnight" -> today().minusDays(1)
        else -> LocalDate.parse(s.trim())
    }

    /** ISO date (start of day, or end of day when [end]), offset date-time, local date-time in [zone], or "now". */
    fun parseTime(s: String, end: Boolean): Instant {
        val t = s.trim()
        if (t.equals("now", ignoreCase = true)) return clock.instant()
        if (t.length == 10 || t.all { it.isLetter() || it == ' ' }) {
            val d = parseDate(t)
            return if (end) d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1) else d.atStartOfDay(zone).toInstant()
        }
        return try {
            OffsetDateTime.parse(t).toInstant()
        } catch (e: DateTimeException) {
            LocalDateTime.parse(t).atZone(zone).toInstant()
        }
    }

    private fun local(ms: Long): String = LOCAL.format(Instant.ofEpochMilli(ms).atZone(zone))

    companion object {
        private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

        private fun strProp(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }

        private fun enumProp(desc: String, values: List<String>) = buildJsonObject {
            put("type", "string"); put("description", desc)
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        }

        private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }
}
