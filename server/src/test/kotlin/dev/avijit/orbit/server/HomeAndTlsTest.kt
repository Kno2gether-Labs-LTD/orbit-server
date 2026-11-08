package dev.avijit.orbit.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.cert.X509Certificate

class HomeAndTlsTest {
    @Test fun tlsCertIsReusedAcrossRuns() = withTempDir { dir ->
        val home = ServerHome(dir.resolve("home"))
        val first = Tls.loadOrCreate(home, listOf("orbit.local"))
        val second = Tls.loadOrCreate(ServerHome(dir.resolve("home")), listOf("orbit.local"))
        assertEquals(first.fingerprintSha256, second.fingerprintSha256)
        // A new --host does not silently change the identity...
        val third = Tls.loadOrCreate(home, listOf("orbit.local", "nas.example"))
        assertEquals(first.fingerprintSha256, third.fingerprintSha256)
        // ...only an explicit regenerate does.
        val fourth = Tls.loadOrCreate(home, listOf("nas.example"), regenerate = true)
        assertNotEquals(first.fingerprintSha256, fourth.fingerprintSha256)
        assertTrue("nas.example" in Tls.subjectAltNames(fourth))
    }

    @Test fun fingerprintIsLowercaseHex64() = withTempDir { dir ->
        val tls = Tls.loadOrCreate(ServerHome(dir), emptyList())
        assertTrue(tls.fingerprintSha256, Regex("[0-9a-f]{64}").matches(tls.fingerprintSha256))
    }

    @Test fun certIsEcP256TenYearsWithRequiredSans() = withTempDir { dir ->
        val tls = Tls.loadOrCreate(ServerHome(dir), listOf("myhost.lan", "192.168.1.20"))
        val cert = tls.keyStore.getCertificate(tls.alias) as X509Certificate
        assertEquals("EC", cert.publicKey.algorithm)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        val years = (cert.notAfter.time - cert.notBefore.time) / (365.25 * 24 * 3600 * 1000)
        assertTrue("validity $years", years in 9.9..10.1)
        val sans = Tls.subjectAltNames(tls)
        for (n in listOf("localhost", "127.0.0.1", "10.0.2.2", "myhost.lan", "192.168.1.20")) {
            assertTrue("$n in $sans", n in sans)
        }
        cert.checkValidity()
        cert.verify(cert.publicKey)
    }

    @Test fun homeFilesArePrivate() = withTempDir { dir ->
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        val home = ServerHome(dir.resolve("home"))
        Tls.loadOrCreate(home, emptyList())
        MasterKey(home)
        Store(home.dbFile).close()
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(home.dir))
        val files = Files.list(home.dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        assertTrue(files.map { it.fileName.toString() }.toString(), files.size >= 4)
        for (f in files) {
            val perms = Files.getPosixFilePermissions(f)
            assertTrue("$f has $perms", perms.none {
                it in setOf(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                )
            })
        }
    }

    @Test fun homeTightensAnExistingLooseDirectory() = withTempDir { dir ->
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        val loose = dir.resolve("loose")
        Files.createDirectory(loose, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        ServerHome(loose)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(loose))
    }

    @Test fun masterKeyRoundTrip() = withTempDir { dir ->
        val home = ServerHome(dir)
        val key = MasterKey(home)
        val secret = "sk-ant-test-0123456789"
        val a = key.encrypt(secret)
        val b = key.encrypt(secret)
        assertNotEquals("random IV per encryption", a, b)
        assertTrue(secret !in a)
        assertEquals(secret, key.decrypt(a))
        // The key persists: a new instance on the same home decrypts.
        assertEquals(secret, MasterKey(home).decrypt(b))
        // Tampering is detected (GCM tag).
        val raw = java.util.Base64.getDecoder().decode(a)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        assertTrue(runCatching { key.decrypt(tampered) }.isFailure)
    }
}
