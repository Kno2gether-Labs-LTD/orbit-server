package dev.avijit.orbit.server

import java.nio.file.Files
import java.nio.file.Path

/** A fresh temp directory per test, removed afterwards. Nothing touches the real ~/.orbit-server. */
fun <T> withTempDir(block: (Path) -> T): T {
    val dir = Files.createTempDirectory("orbit-server-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
