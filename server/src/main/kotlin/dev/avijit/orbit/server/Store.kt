package dev.avijit.orbit.server

import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SegmentDto
import dev.avijit.orbithealth.sync.SleepDto
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class DeviceRow(
    val id: String, val name: String, val tokenHash: String, val scopes: Set<String>,
    val createdMs: Long, val lastSeenMs: Long?, val revoked: Boolean,
    /** The phone's IANA zone from its latest ingest that carried a valid one (null before that). */
    val zone: String? = null,
)

data class AgentTokenRow(
    val id: String, val name: String, val tokenHash: String, val scopes: Set<String>,
    val createdMs: Long, val revoked: Boolean,
)

data class DeviceInfoRow(val deviceId: String, val ringId: Int, val firmware: String, val battery: Int, val atMs: Long)

data class InsertCounts(val new: Int, val dup: Int)

/**
 * SQLite store (`orbit.db`, 0600). WAL mode, foreign keys on, parameterized statements only.
 *
 * Writes go through one connection under a lock (SQLite allows one writer); each read opens its own short-lived
 * connection, so reads run concurrently with each other and with the writer under WAL.
 *
 * Same idempotent keys as the app: samples (metric, epoch_ms); sleep sessions by start; segments by
 * (session start, segment start). Conflict rules match the app's SampleRepository.
 */
class Store(private val dbFile: Path) : AutoCloseable {
    val dbPath: Path get() = dbFile
    private val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
    private val writeLock = ReentrantLock()
    private val writer: Connection

    init {
        createPrivateFile(dbFile)
        writer = open()
        writeLock.withLock {
            writer.createStatement().use { st ->
                SCHEMA.forEach { st.execute(it) }
                // v1 → v2: the device row remembers the zone of its latest ingest (used for local days everywhere).
                val cols = st.executeQuery("PRAGMA table_info(devices)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
                if ("zone" !in cols) st.execute("ALTER TABLE devices ADD COLUMN zone TEXT")
                if ("zone_ms" !in cols) st.execute("ALTER TABLE devices ADD COLUMN zone_ms INTEGER")
                st.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
        for (suffix in listOf("-wal", "-shm")) tightenIfPosix(Paths.get(dbFile.toString() + suffix))
    }

    override fun close() = writeLock.withLock { writer.close() }

    // ---- devices ----

    fun insertDevice(d: DeviceRow) = write { c ->
        c.prepareStatement(
            "INSERT INTO devices(id, name, token_hash, scopes, created_ms, last_seen_ms, revoked) VALUES (?,?,?,?,?,?,?)",
        ).use { it.bind(d.id, d.name, d.tokenHash, d.scopes.joinScopes(), d.createdMs, d.lastSeenMs, d.revoked.int).executeUpdate() }
    }

    fun deviceByTokenHash(hash: String): DeviceRow? =
        read { c -> c.query("SELECT * FROM devices WHERE token_hash = ?", hash) { it.device() }.firstOrNull() }

    fun device(id: String): DeviceRow? =
        read { c -> c.query("SELECT * FROM devices WHERE id = ?", id) { it.device() }.firstOrNull() }

    fun listDevices(): List<DeviceRow> = read { c -> c.query("SELECT * FROM devices ORDER BY created_ms") { it.device() } }

    /** Revokes the device and deletes its walker conversation history ([dev.avijit.orbit.server.agent.ConversationStore]). */
    fun revokeDevice(id: String): Boolean = transaction { c ->
        val ok = c.update("UPDATE devices SET revoked = 1 WHERE id = ?", id) == 1
        c.update("DELETE FROM walker_messages WHERE device_id = ?", id)
        ok
    }

    fun touchDevice(id: String, nowMs: Long) = write { c -> c.update("UPDATE devices SET last_seen_ms = ? WHERE id = ?", nowMs, id) }

    /** Remembers [zone] as the device's zone, reported at [nowMs] (its latest ingest). */
    fun setDeviceZone(id: String, zone: String, nowMs: Long) =
        write { c -> c.update("UPDATE devices SET zone = ?, zone_ms = ? WHERE id = ?", zone, nowMs, id) }

    /** The zone from the most recent ingest of any active device; null when none has reported one. */
    fun latestDeviceZone(): String? = read { c ->
        c.query("SELECT zone FROM devices WHERE zone IS NOT NULL AND revoked = 0 ORDER BY zone_ms DESC LIMIT 1") { it.getString(1) }.firstOrNull()
    }

    fun updateDeviceScopes(id: String, scopes: Set<String>): Boolean =
        write { c -> c.update("UPDATE devices SET scopes = ? WHERE id = ?", scopes.joinScopes(), id) == 1 }

    // ---- pairing tokens (single use, expiring) ----

    fun insertPairToken(tokenHash: String, expiresMs: Long) =
        write { c -> c.update("INSERT INTO pair_tokens(token_hash, expires_ms, used) VALUES (?,?,0)", tokenHash, expiresMs) }

    /** Atomically marks the token used; true only for an existing, unused, unexpired token. */
    fun consumePairToken(tokenHash: String, nowMs: Long): Boolean = write { c ->
        c.update("UPDATE pair_tokens SET used = 1 WHERE token_hash = ? AND used = 0 AND expires_ms > ?", tokenHash, nowMs) == 1
    }

    fun purgePairTokens(nowMs: Long): Int =
        write { c -> c.update("DELETE FROM pair_tokens WHERE used = 1 OR expires_ms <= ?", nowMs) }

    fun pairTokenCount(): Int = read { c -> c.query("SELECT COUNT(*) FROM pair_tokens") { it.getInt(1) }.first() }

    // ---- agent tokens ----

    fun insertAgentToken(t: AgentTokenRow) = write { c ->
        c.update(
            "INSERT INTO agent_tokens(id, name, token_hash, scopes, created_ms, revoked) VALUES (?,?,?,?,?,?)",
            t.id, t.name, t.tokenHash, t.scopes.joinScopes(), t.createdMs, t.revoked.int,
        )
    }

    fun agentTokenByHash(hash: String): AgentTokenRow? =
        read { c -> c.query("SELECT * FROM agent_tokens WHERE token_hash = ?", hash) { it.agentToken() }.firstOrNull() }

    fun listAgentTokens(): List<AgentTokenRow> =
        read { c -> c.query("SELECT * FROM agent_tokens ORDER BY created_ms") { it.agentToken() } }

    fun revokeAgentToken(id: String): Boolean =
        write { c -> c.update("UPDATE agent_tokens SET revoked = 1 WHERE id = ?", id) == 1 }

    // ---- samples ----

    /** One transaction. Duplicates are ignored, except [MAX_WINS] metrics, which keep the larger value. */
    fun insertSamples(samples: List<SampleDto>, deviceId: String?): InsertCounts = transaction { c ->
        var new = 0
        c.prepareStatement(
            "INSERT OR IGNORE INTO samples(metric, epoch_ms, value, source, device_id) VALUES (?,?,?,?,?)",
        ).use { ins ->
            c.prepareStatement(
                "UPDATE samples SET value = ?, source = ? WHERE metric = ? AND epoch_ms = ? AND value < ?",
            ).use { raise ->
                for (s in samples) {
                    if (ins.bind(s.metric, s.epochMs, s.value, s.source, deviceId).executeUpdate() == 1) {
                        new++
                    } else if (s.metric in MAX_WINS) {
                        raise.bind(s.value, s.source, s.metric, s.epochMs, s.value).executeUpdate()
                    }
                }
            }
        }
        InsertCounts(new, samples.size - new)
    }

    fun samples(metric: String, fromMs: Long, toMs: Long): List<SampleDto> = read { c ->
        c.query("SELECT metric, epoch_ms, value, source FROM samples WHERE metric = ? AND epoch_ms BETWEEN ? AND ? ORDER BY epoch_ms",
            metric, fromMs, toMs) { SampleDto(it.getString(1), it.getLong(2), it.getDouble(3), it.getString(4)) }
    }

    fun latestEpochByMetric(): Map<String, Long> = read { c ->
        c.query("SELECT metric, MAX(epoch_ms) FROM samples GROUP BY metric") { it.getString(1) to it.getLong(2) }.toMap()
    }

    /** The newest sample of each metric. */
    fun latestSamples(): Map<String, SampleDto> = read { c ->
        // SQLite returns the bare columns from the row that holds MAX(epoch_ms).
        c.query("SELECT metric, MAX(epoch_ms), value, source FROM samples GROUP BY metric") {
            it.getString(1) to SampleDto(it.getString(1), it.getLong(2), it.getDouble(3), it.getString(4))
        }.toMap()
    }

    /** Streams every sample (by metric, then time) to [block] without loading them all; returns the count. */
    fun forEachSample(block: (SampleDto) -> Unit): Int = read { c ->
        var n = 0
        c.prepareStatement("SELECT metric, epoch_ms, value, source FROM samples ORDER BY metric, epoch_ms").use { st ->
            st.fetchSize = 1_000
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    block(SampleDto(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getString(4)))
                    n++
                }
            }
        }
        n
    }

    fun metricCounts(): Map<String, Int> = read { c ->
        c.query("SELECT metric, COUNT(*) FROM samples GROUP BY metric") { it.getString(1) to it.getInt(2) }.toMap()
    }

    // ---- sleep ----

    /** One transaction. A re-sent session keeps the later end; only new segments are added. */
    fun insertSleep(sessions: List<SleepDto>, deviceId: String?): InsertCounts = transaction { c ->
        var new = 0
        c.prepareStatement("INSERT OR IGNORE INTO sleep_sessions(start_ms, end_ms, device_id) VALUES (?,?,?)").use { ins ->
            c.prepareStatement("UPDATE sleep_sessions SET end_ms = ? WHERE start_ms = ? AND end_ms < ?").use { extend ->
                c.prepareStatement(
                    "INSERT OR IGNORE INTO sleep_segments(session_start_ms, stage, start_ms, duration_sec) VALUES (?,?,?,?)",
                ).use { seg ->
                    for (s in sessions) {
                        if (ins.bind(s.startMs, s.endMs, deviceId).executeUpdate() == 1) new++
                        else extend.bind(s.endMs, s.startMs, s.endMs).executeUpdate()
                        for (g in s.segments) seg.bind(s.startMs, g.stage, g.startMs, g.durationSec).executeUpdate()
                    }
                }
            }
        }
        InsertCounts(new, sessions.size - new)
    }

    /** Sessions whose start lies in [fromMs, toMs], with their segments in time order. */
    fun sleepSessions(fromMs: Long, toMs: Long): List<SleepDto> = read { c ->
        val sessions = c.query("SELECT start_ms, end_ms FROM sleep_sessions WHERE start_ms BETWEEN ? AND ? ORDER BY start_ms",
            fromMs, toMs) { it.getLong(1) to it.getLong(2) }
        if (sessions.isEmpty()) return@read emptyList()
        val segs = c.query(
            "SELECT g.session_start_ms, g.stage, g.start_ms, g.duration_sec FROM sleep_segments g " +
                "JOIN sleep_sessions s ON s.start_ms = g.session_start_ms WHERE s.start_ms BETWEEN ? AND ? ORDER BY g.start_ms",
            fromMs, toMs,
        ) { it.getLong(1) to SegmentDto(it.getString(2), it.getLong(3), it.getInt(4)) }.groupBy({ it.first }, { it.second })
        sessions.map { (start, end) -> SleepDto(start, end, segs[start].orEmpty()) }
    }

    fun lastSleepStartMs(): Long? = read { c ->
        c.query("SELECT MAX(start_ms) FROM sleep_sessions") { r -> r.getLong(1).takeUnless { r.wasNull() } }.firstOrNull()
    }

    // ---- device info ----

    fun upsertDeviceInfo(deviceId: String, info: DeviceInfoDto, atMs: Long) = write { c ->
        c.update(
            "INSERT INTO device_info(device_id, ring_id, firmware, battery, at_ms) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(device_id) DO UPDATE SET ring_id = excluded.ring_id, firmware = excluded.firmware, " +
                "battery = excluded.battery, at_ms = excluded.at_ms",
            deviceId, info.deviceId, info.firmware, info.batteryPct, atMs,
        )
    }

    fun deviceInfo(deviceId: String): DeviceInfoRow? = read { c ->
        c.query("SELECT device_id, ring_id, firmware, battery, at_ms FROM device_info WHERE device_id = ?", deviceId) {
            DeviceInfoRow(it.getString(1), it.getInt(2), it.getString(3), it.getInt(4), it.getLong(5))
        }.firstOrNull()
    }

    // ---- access log ----

    fun appendAccessLog(atMs: Long, actor: String, action: String, detail: String, rows: Int) = write { c ->
        c.update("INSERT INTO access_log(at_ms, actor, action, detail, rows) VALUES (?,?,?,?,?)", atMs, actor, action, detail, rows)
    }

    /** Newest first. */
    fun accessLog(limit: Int, sinceMs: Long = 0): List<AccessLogEntry> = read { c ->
        c.query("SELECT at_ms, actor, action, detail, rows FROM access_log WHERE at_ms >= ? ORDER BY at_ms DESC, id DESC LIMIT ?",
            sinceMs, limit.coerceIn(0, 10_000)) {
            AccessLogEntry(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5))
        }
    }

    /** Newest first, [limit] rows after skipping [offset]. */
    fun accessLogPage(limit: Int, offset: Int): List<AccessLogEntry> = read { c ->
        c.query("SELECT at_ms, actor, action, detail, rows FROM access_log ORDER BY at_ms DESC, id DESC LIMIT ? OFFSET ?",
            limit.coerceIn(0, 10_000), offset.coerceAtLeast(0)) {
            AccessLogEntry(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5))
        }
    }

    /** Streams the whole access log, oldest first, to [block] without loading it all; returns the count. */
    fun forEachAccessLog(block: (AccessLogEntry) -> Unit): Int = read { c ->
        var n = 0
        c.prepareStatement("SELECT at_ms, actor, action, detail, rows FROM access_log ORDER BY at_ms, id").use { st ->
            st.fetchSize = 1_000
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    block(AccessLogEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
                    n++
                }
            }
        }
        n
    }

    fun accessLogCount(sinceMs: Long = 0): Int = read { c ->
        c.query("SELECT COUNT(*) FROM access_log WHERE at_ms >= ?", sinceMs) { it.getInt(1) }.first()
    }

    // ---- settings ----

    fun getSetting(key: String): String? =
        read { c -> c.query("SELECT value FROM settings WHERE key = ?", key) { it.getString(1) }.firstOrNull() }

    fun putSetting(key: String, value: String) = write { c ->
        c.update("INSERT INTO settings(key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)
    }

    fun deleteSetting(key: String) = write { c -> c.update("DELETE FROM settings WHERE key = ?", key) }

    /**
     * First-run setup in one transaction: when no admin password exists yet and [codeHash] equals the pending,
     * unexpired setup code's hash, stores [adminHash] and deletes the code. Concurrent attempts yield one winner.
     */
    fun completeSetup(codeHash: String, nowMs: Long, adminHash: String): SetupCode.Outcome = transaction { c ->
        fun get(k: String) = c.query("SELECT value FROM settings WHERE key = ?", k) { it.getString(1) }.firstOrNull()
        if (get(Settings.ADMIN_HASH) != null) return@transaction SetupCode.Outcome.ALREADY_SET_UP
        val stored = get(Settings.SETUP_CODE_HASH)
        val expires = get(Settings.SETUP_CODE_EXPIRES)?.toLongOrNull()
        val match = stored != null && expires != null && nowMs < expires &&
            java.security.MessageDigest.isEqual(stored.toByteArray(), codeHash.toByteArray())
        if (!match) return@transaction SetupCode.Outcome.BAD_CODE
        c.update("INSERT INTO settings(key, value) VALUES (?,?)", Settings.ADMIN_HASH, adminHash)
        c.update("DELETE FROM settings WHERE key IN (?, ?)", Settings.SETUP_CODE_HASH, Settings.SETUP_CODE_EXPIRES)
        SetupCode.Outcome.DONE
    }

    // ---- plumbing ----

    private fun open(): Connection {
        val cfg = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            enforceForeignKeys(true)
            setBusyTimeout(5_000)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        }
        return cfg.createConnection(url)
    }

    internal fun <T> write(block: (Connection) -> T): T = writeLock.withLock { block(writer) }

    internal fun <T> transaction(block: (Connection) -> T): T = writeLock.withLock {
        writer.autoCommit = false
        try {
            block(writer).also { writer.commit() }
        } catch (e: Throwable) {
            writer.rollback()
            throw e
        } finally {
            writer.autoCommit = true
        }
    }

    internal fun <T> read(block: (Connection) -> T): T = open().use(block)

    private fun createPrivateFile(path: Path) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        if (!Files.exists(path)) {
            if (posix) Files.createFile(path, PosixFilePermissions.asFileAttribute(ServerHome.FILE_PERMS)) else Files.createFile(path)
        }
        tightenIfPosix(path)
    }

    private fun tightenIfPosix(path: Path) {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix") && Files.exists(path)) {
            Files.setPosixFilePermissions(path, ServerHome.FILE_PERMS)
        }
    }

    companion object {
        /** Metrics whose stored value is raised when a larger value arrives for the same timestamp (as in the app). */
        val MAX_WINS = setOf("STEPS", "DISTANCE_M", "CALORIES_KCAL", "STEPS_DAY_TOTAL")

        const val SCHEMA_VERSION = 3

        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS devices(
                id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, scopes TEXT NOT NULL,
                created_ms INTEGER NOT NULL, last_seen_ms INTEGER, revoked INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS pair_tokens(
                token_hash TEXT PRIMARY KEY, expires_ms INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS agent_tokens(
                id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, scopes TEXT NOT NULL,
                created_ms INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS samples(
                metric TEXT NOT NULL, epoch_ms INTEGER NOT NULL, value REAL NOT NULL, source TEXT NOT NULL,
                device_id TEXT REFERENCES devices(id) ON DELETE SET NULL, UNIQUE(metric, epoch_ms))""",
            """CREATE TABLE IF NOT EXISTS sleep_sessions(
                start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL,
                device_id TEXT REFERENCES devices(id) ON DELETE SET NULL)""",
            """CREATE TABLE IF NOT EXISTS sleep_segments(
                session_start_ms INTEGER NOT NULL REFERENCES sleep_sessions(start_ms) ON DELETE CASCADE,
                stage TEXT NOT NULL, start_ms INTEGER NOT NULL, duration_sec INTEGER NOT NULL,
                UNIQUE(session_start_ms, start_ms))""",
            """CREATE TABLE IF NOT EXISTS device_info(
                device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE, ring_id INTEGER NOT NULL,
                firmware TEXT NOT NULL, battery INTEGER NOT NULL, at_ms INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS access_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, actor TEXT NOT NULL,
                action TEXT NOT NULL, detail TEXT NOT NULL, rows INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS access_log_at ON access_log(at_ms)",
            "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // v3: OrbitWalker conversation history (see agent/ConversationStore.kt). Additive; nothing else changes.
            """CREATE TABLE IF NOT EXISTS walker_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                conversation TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS walker_messages_conv ON walker_messages(device_id, conversation, id)",
            "CREATE INDEX IF NOT EXISTS walker_messages_device ON walker_messages(device_id, id)",
        )
    }
}

private val Boolean.int get() = if (this) 1 else 0

private fun Set<String>.joinScopes() = sorted().joinToString(",")
private fun String.splitScopes(): Set<String> = split(',').filter { it.isNotBlank() }.toSet()

internal fun PreparedStatement.bind(vararg args: Any?): PreparedStatement {
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> setNull(idx, Types.NULL)
            is String -> setString(idx, a)
            is Int -> setInt(idx, a)
            is Long -> setLong(idx, a)
            is Double -> setDouble(idx, a)
            is Boolean -> setInt(idx, a.int)
            else -> throw IllegalArgumentException("unsupported SQL parameter type ${a::class}")
        }
    }
    return this
}

internal fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { it.bind(*args).executeUpdate() }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(*args).executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out += map(rs)
            out
        }
    }

private fun ResultSet.device() = DeviceRow(
    id = getString("id"), name = getString("name"), tokenHash = getString("token_hash"),
    scopes = getString("scopes").splitScopes(), createdMs = getLong("created_ms"),
    lastSeenMs = getLong("last_seen_ms").takeUnless { wasNull() }, revoked = getInt("revoked") != 0,
    zone = getString("zone"),
)

private fun ResultSet.agentToken() = AgentTokenRow(
    id = getString("id"), name = getString("name"), tokenHash = getString("token_hash"),
    scopes = getString("scopes").splitScopes(), createdMs = getLong("created_ms"), revoked = getInt("revoked") != 0,
)
