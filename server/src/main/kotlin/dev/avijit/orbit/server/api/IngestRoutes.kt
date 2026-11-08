package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.DeviceRow
import dev.avijit.orbit.server.Limits
import dev.avijit.orbit.server.PhoneZone
import dev.avijit.orbit.server.Store
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.IngestResult
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.SleepDto
import dev.avijit.orbithealth.sync.scope
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.Clock

/**
 * Idempotent ingest of one batch from a device.
 *
 * Policy (documented in the S3 report):
 * - Batches over [Limits.maxSamples] samples, [Limits.maxSleepSessions] sessions or [Limits.maxSegments] segments,
 *   or bodies over [Limits.maxIngestBytes], are refused whole with 413; the phone should send smaller chunks.
 * - Individual items that cannot be stored are skipped, not fatal, and counted in `samplesSkipped`/`sleepSkipped`:
 *   unknown metric/source names (exact enum names), values outside 0..1e9, timestamps before 1970 or more than a day
 *   in the future (they would poison the watermark), sleep sessions that end before they start or last over 24 h,
 *   and anything outside the device's scopes. Invalid segments are dropped from an otherwise valid session.
 * - A valid IANA `zone` is remembered on the device row as the user's zone; an invalid one is ignored.
 * - Device info needs the `profile` scope and sane values, otherwise it is silently ignored.
 * - Duplicates are ignored, except activity metrics where the larger value wins ([Store.insertSamples]).
 */
class Ingest(private val store: Store, private val clock: Clock, private val limits: Limits) {

    fun ingest(device: DeviceRow, batch: IngestBatch): IngestResult {
        if (batch.samples.size > limits.maxSamples || batch.sleep.size > limits.maxSleepSessions ||
            batch.sleep.sumOf { it.segments.size.toLong() } > limits.maxSegments
        ) {
            throw ApiError(HttpStatusCode.PayloadTooLarge, "batch_too_large")
        }
        val now = clock.millis()
        val latestAllowed = now + MAX_FUTURE_MS
        val scopes = device.scopes.mapNotNull(Scope::fromWire).toSet()

        val samples = batch.samples.filter { it.isValid(latestAllowed) && METRICS.getValue(it.metric).scope() in scopes }
        val sleep = if (Scope.SLEEP in scopes) batch.sleep.filter { it.isValid(latestAllowed) }.map { s ->
            s.copy(segments = s.segments.filter { g -> g.stage in STAGES && g.startMs > 0 && g.durationSec in 0..MAX_SESSION_SEC })
        } else emptyList()

        val s = if (samples.isEmpty()) null else store.insertSamples(samples, device.id)
        val z = if (sleep.isEmpty()) null else store.insertSleep(sleep, device.id)
        batch.device?.takeIf { Scope.PROFILE in scopes && it.isValid() }?.let { info ->
            store.upsertDeviceInfo(device.id, info.copy(firmware = info.firmware.trim()), now)
        }
        // Remember the phone's zone on its device row, so asks without one, MCP, the data API and the dashboard all
        // use the user's local days.
        PhoneZone.parse(batch.zone)?.let { z -> store.setDeviceZone(device.id, z.id, now) }
        store.touchDevice(device.id, now)
        return IngestResult(
            samplesNew = s?.new ?: 0, samplesDup = s?.dup ?: 0,
            sleepNew = z?.new ?: 0, sleepDup = z?.dup ?: 0,
            samplesSkipped = batch.samples.size - samples.size,
            sleepSkipped = batch.sleep.size - sleep.size,
        )
    }

    private fun SampleDto.isValid(latestAllowed: Long) =
        metric in METRICS && source in SOURCES && epochMs in 1..latestAllowed && value.isFinite() && value in 0.0..MAX_VALUE

    private fun SleepDto.isValid(latestAllowed: Long) =
        startMs > 0 && endMs > startMs && endMs <= latestAllowed && endMs - startMs <= MAX_SESSION_SEC * 1000L

    private fun DeviceInfoDto.isValid() =
        deviceId >= 0 && batteryPct in 0..100 && firmware.trim().length in 1..64 && firmware.none { it.isISOControl() }

    companion object {
        private val METRICS = Metric.entries.associateBy { it.name }
        private val SOURCES = SampleSource.entries.map { it.name }.toSet()
        private val STAGES = SleepStage.entries.map { it.name }.toSet()
        const val MAX_FUTURE_MS = 24 * 3_600_000L
        const val MAX_SESSION_SEC = 24 * 3_600
        const val MAX_VALUE = 1e9
    }
}

/** `POST /v1/ingest` (device token): body [IngestBatch] → [IngestResult]. */
fun Route.ingestRoutes(ingest: Ingest, limits: Limits, limiter: RateLimiter) {
    post("/v1/ingest") {
        // Per device, checked before the body is read: 429 `rate_limited` past [Limits.ingestsPerMinute].
        if (!limiter.tryAcquire(call.device.id)) throw ApiError(HttpStatusCode.TooManyRequests, "rate_limited")
        val batch = call.receiveLimited(IngestBatch.serializer(), limits.maxIngestBytes)
        call.respond(ingest.ingest(call.device, batch))
    }
}
