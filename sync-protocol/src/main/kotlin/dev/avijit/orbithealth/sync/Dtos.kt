package dev.avijit.orbithealth.sync

import kotlinx.serialization.Serializable

// Wire contract between the Orbit Health app and the Orbit server. Keep plain and stable.

@Serializable data class SampleDto(val metric: String, val epochMs: Long, val value: Double, val source: String)
@Serializable data class SegmentDto(val stage: String, val startMs: Long, val durationSec: Int)
@Serializable data class SleepDto(val startMs: Long, val endMs: Long, val segments: List<SegmentDto>)
@Serializable data class DeviceInfoDto(val deviceId: Int, val firmware: String, val batteryPct: Int)

/**
 * One push from the phone. [zone] is the phone's IANA time zone id (e.g. "Europe/London"); the server uses it, when valid,
 * for local days ("today", "last night"). Null for older clients.
 */
@Serializable data class IngestBatch(
    val samples: List<SampleDto>,
    val sleep: List<SleepDto>,
    val device: DeviceInfoDto? = null,
    val zone: String? = null,
)
/**
 * Counts for one ingest. `*Skipped` items were not stored: unknown metric/stage/source, invalid values or timestamps,
 * or outside the device's scopes. They default to 0 so older JSON without them still decodes.
 */
@Serializable data class IngestResult(
    val samplesNew: Int, val samplesDup: Int, val sleepNew: Int, val sleepDup: Int,
    val samplesSkipped: Int = 0, val sleepSkipped: Int = 0,
)

@Serializable data class PairRequest(val token: String, val deviceName: String)
@Serializable data class PairResponse(val deviceToken: String, val deviceId: String, val serverName: String, val scopes: Set<String>)

/** Body and response of `PATCH /v1/devices/me/scopes`: the complete new scope set (wire names, e.g. "heart"). */
@Serializable data class ScopesUpdate(val scopes: Set<String>)

@Serializable data class StateResponse(val lastEpochMsByMetric: Map<String, Long>, val lastSleepStartMs: Long? = null)

@Serializable data class ChatTurn(val role: String, val text: String)
/** [zone]: the phone's IANA time zone id, so "today" and "last night" mean the user's local day. Null for older clients. */
@Serializable data class AskRequest(val question: String, val history: List<ChatTurn> = emptyList(), val zone: String? = null)
@Serializable data class ToolUse(val tool: String, val args: Map<String, String>)
@Serializable data class AskResponse(val answer: String, val provenance: List<ToolUse>, val model: String)

@Serializable data class AccessLogEntry(val atMs: Long, val actor: String, val action: String, val detail: String, val rows: Int)
