package dev.avijit.orbit.server

import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The server's private data directory (default `~/.orbit-server`, overridable with `ORBIT_SERVER_HOME`).
 * The directory is kept at 0700 and every file written through it at 0600. On a file system without
 * POSIX permissions (e.g. FAT, some Windows setups) a warning is logged once and the host's ACLs apply.
 */
class ServerHome(dir: Path) {
    val dir: Path = dir.toAbsolutePath().normalize()
    val posix: Boolean

    init {
        Files.createDirectories(this.dir)
        posix = this.dir.fileSystem.supportedFileAttributeViews().contains("posix")
        if (posix) {
            Files.setPosixFilePermissions(this.dir, DIR_PERMS)
        } else {
            log.warn("File system at {} has no POSIX permissions; cannot enforce 0700/0600. Restrict access with your OS.", this.dir)
        }
    }

    fun file(name: String): Path = dir.resolve(name)

    val dbFile: Path get() = file(DB_FILE)

    /** Sets 0600 on [path] if it exists (used for files other libraries create, e.g. SQLite WAL files). */
    fun makePrivate(path: Path) {
        if (posix && Files.exists(path)) Files.setPosixFilePermissions(path, FILE_PERMS)
    }

    /** Writes [bytes] to [name] atomically; the file is created 0600 before any content lands in it. */
    fun writePrivate(name: String, bytes: ByteArray) {
        val target = file(name)
        val tmp = file(".$name.${System.nanoTime()}.tmp")
        try {
            if (posix) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE_PERMS))
            } else {
                Files.createFile(tmp)
            }
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            makePrivate(target)
        } catch (e: FileAlreadyExistsException) {
            throw IllegalStateException("temp file collision for $name", e)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerHome::class.java)
        val DIR_PERMS = PosixFilePermissions.fromString("rwx------")!!
        val FILE_PERMS = PosixFilePermissions.fromString("rw-------")!!
        const val DB_FILE = "orbit.db"
        const val ENV = "ORBIT_SERVER_HOME"

        /** `$ORBIT_SERVER_HOME` if set, else `~/.orbit-server`. */
        fun defaultDir(env: Map<String, String> = System.getenv()): Path =
            env[ENV]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".orbit-server")
    }
}

/** Keys in the `settings` table. */
object Settings {
    const val ADMIN_HASH = "admin.bcrypt"
    const val SERVER_NAME = "server.name"
    /** Port of the last `run`, used as the default for `pair`. */
    const val SERVER_PORT = "server.port"
    /**
     * The public `https://` URL of the last `run --behind-proxy` (absent after a self-signed run). `pair` and
     * `token create` then use it, with the public-CA trust mode.
     */
    const val PUBLIC_URL = "server.public_url"
    /** SHA-256 hex of the pending first-run setup code (the code itself is never stored). */
    const val SETUP_CODE_HASH = "setup.code.sha256"
    /** Epoch ms after which the pending setup code no longer works. */
    const val SETUP_CODE_EXPIRES = "setup.code.expires_ms"
}

/** Options for `orbit-server run`. */
data class RunConfig(
    val bind: String = "0.0.0.0",
    val port: Int = DEFAULT_PORT,
    val hosts: List<String> = emptyList(),
    val regenerateCert: Boolean = false,
    val insecureDev: Boolean = false,
    /** `--behind-proxy`: plain HTTP for a TLS-terminating reverse proxy that serves [publicUrl]. */
    val behindProxy: Boolean = false,
    val publicUrl: String? = null,
    val trustedProxies: List<Cidr> = emptyList(),
) {
    companion object {
        const val DEFAULT_PORT = 8443
        const val DEFAULT_PROXY_PORT = 8080
    }
}
