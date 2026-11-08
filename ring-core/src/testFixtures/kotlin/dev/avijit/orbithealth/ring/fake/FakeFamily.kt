package dev.avijit.orbithealth.ring.fake

import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.RingPacket
import dev.avijit.orbithealth.ring.GattProfile
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingAdvertisement
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.RingFamily
import dev.avijit.orbithealth.ring.RingMatch
import dev.avijit.orbithealth.ring.RingTransport
import dev.avijit.orbithealth.ring.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Test-only ring family. Matches adverts whose name starts with [namePrefix] with [confidence]; its drivers are
 * [FakeRingDriver]s built from [capabilities] (and the other knobs), recorded in [drivers].
 *
 * ```
 * val family = FakeFamily(capabilities = FakeFamily.caps(measure = mapOf(MeasureKind.SPO2 to MeasureSpec(30, true, false))))
 * val registry = RingRegistry(listOf(family))
 * ```
 */
class FakeFamily(
    override val id: String = "fake",
    override val displayName: String = "Fake ring",
    val namePrefix: String = "FAKE",
    val confidence: Int = 90,
    val modelName: String = "Fake 1",
    var capabilities: RingCapabilities = caps(),
    var deviceInfo: DeviceInfo = DeviceInfo(deviceId = 1, firmware = "1.0", batteryPct = 80),
    /** Events [FakeRingDriver.measure] emits per kind; default: Started then a plausible Result, or UNSUPPORTED. */
    var measureScript: (MeasureKind) -> List<MeasureEvent> = { kind -> defaultMeasure(kind, capabilities) },
    override val gattProfile: GattProfile = GattProfile(
        writes = listOf(GattChar.sig("aaaa", "aaab")),
        notifies = listOf(GattChar.sig("aaaa", "aaac")),
        writeWithResponse = false,
    ),
    override val vendorAppPackages: Set<String> = emptySet(),
    override val vendorAppName: String? = null,
) : RingFamily {
    val drivers = mutableListOf<FakeRingDriver>()

    override fun match(advertisement: RingAdvertisement): RingMatch? =
        if (advertisement.name?.startsWith(namePrefix) == true) RingMatch(modelName, confidence) else null

    override fun createDriver(transport: RingTransport): FakeRingDriver = driver().also { drivers += it }

    /** A driver not tied to a transport, e.g. for a RingConnection in a ViewModel test. */
    fun driver(): FakeRingDriver = FakeRingDriver(this)

    companion object {
        /** Capabilities with sensible defaults; override what the test cares about. */
        fun caps(
            history: Set<Metric> = setOf(Metric.HEART_RATE, Metric.SPO2, Metric.STEPS),
            sleep: Boolean = true,
            workouts: Boolean = false,
            realtime: Boolean = true,
            measure: Map<MeasureKind, MeasureSpec> = mapOf(MeasureKind.HEART_RATE to MeasureSpec(30, live = true, estimate = false)),
            monitoring: Map<MonitorKind, MonitorSpec> = mapOf(MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 30, 60), canTurnOff = true, supportsWindow = false)),
            canClearHistory: Boolean = false,
        ) = RingCapabilities(history, sleep, workouts, realtime, measure, monitoring, canClearHistory)

        fun defaultMeasure(kind: MeasureKind, caps: RingCapabilities): List<MeasureEvent> {
            if (kind !in caps.measure) return listOf(MeasureEvent.Failed(MeasureFailure.UNSUPPORTED))
            val values = when (kind) {
                MeasureKind.HEART_RATE -> mapOf(Metric.HEART_RATE to 72.0)
                MeasureKind.SPO2 -> mapOf(Metric.SPO2 to 97.0)
                MeasureKind.HRV -> mapOf(Metric.HRV to 45.0)
                MeasureKind.STRESS -> mapOf(Metric.STRESS to 30.0)
                MeasureKind.BLOOD_PRESSURE -> mapOf(Metric.BLOOD_PRESSURE_SYS to 118.0, Metric.BLOOD_PRESSURE_DIA to 76.0)
            }
            return listOf(MeasureEvent.Started, MeasureEvent.Result(values))
        }
    }
}

/** In-memory driver for [FakeFamily]. Every call is appended to [calls]. */
class FakeRingDriver(private val family: FakeFamily) : RingDriver {
    val calls = mutableListOf<String>()
    var history: HistoryBatch = HistoryBatch(emptyList(), emptyList(), emptyList())
    var live: Flow<Int> = emptyFlow()
    /** What [readMonitoring] returns (null = the family can't read its settings back). */
    var monitoring: MonitoringConfig? = null
    /** What [applyMonitoring] accepts from a config; default: everything. Accepted configs are kept in [applied]. */
    var accept: (MonitoringConfig) -> MonitoringConfig = { it }
    val applied = mutableListOf<MonitoringConfig>()
    val cleared = mutableListOf<HistoryBatch>()

    override suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities> {
        calls += "handshake"
        return family.deviceInfo to family.capabilities
    }

    override suspend fun syncHistory(): HistoryBatch {
        calls += "sync"
        return history
    }

    override suspend fun clearSyncedHistory(batch: HistoryBatch) {
        calls += "clear"
        cleared += batch
    }

    override fun liveHeartRate(): Flow<Int> = live

    override fun measure(kind: MeasureKind): Flow<MeasureEvent> = flow {
        calls += "measure:$kind"
        family.measureScript(kind).forEach { emit(it) }
    }

    override suspend fun readMonitoring(): MonitoringConfig? {
        calls += "readMonitoring"
        return monitoring
    }

    override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
        calls += "applyMonitoring"
        val accepted = accept(config)
        applied += accepted
        monitoring = MonitoringConfig((monitoring?.settings ?: emptyMap()) + accepted.settings)
        return accepted
    }
}

/**
 * A hot, in-memory [RingTransport]: [written] collects (characteristic, bytes) writes; [emit] delivers ring replies;
 * [readable] answers [read].
 */
class FakeRingTransport(
    connected: Boolean = true,
    /** Where a write without a characteristic goes (null = write-less ring: such writes throw). */
    val defaultWrite: GattChar? = GattChar.sig("aaaa", "aaab"),
) : RingTransport {
    private val _packets = MutableSharedFlow<RingPacket>(extraBufferCapacity = 64)
    override val packets: Flow<RingPacket> = _packets
    private val _connected = MutableStateFlow(connected)
    override val connected: StateFlow<Boolean> = _connected
    val written = mutableListOf<Pair<GattChar, ByteArray>>()
    val readable = mutableMapOf<GattChar, ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        write(checkNotNull(defaultWrite) { "no write characteristic" }, bytes)
    }

    override suspend fun write(char: GattChar, bytes: ByteArray) {
        written += char to bytes
    }

    override suspend fun read(char: GattChar): ByteArray = readable[char] ?: error("nothing to read at $char")

    suspend fun emit(bytes: ByteArray, char: GattChar = GattChar.sig("aaaa", "aaac")) = _packets.emit(RingPacket(char, bytes))

    fun disconnect() {
        _connected.value = false
    }
}
