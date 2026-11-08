package dev.avijit.orbit.server

import at.favre.lib.crypto.bcrypt.BCrypt
import org.junit.Assert.assertEquals
import dev.avijit.orbithealth.sync.PairingUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class CliTest {
    private fun cli(home: java.nio.file.Path, vararg args: String): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val code = PrintStream(buf, true).use { Cli(home, it, it).run(args.toList()) }
        return code to buf.toString()
    }

    @Test fun initStoresBcryptHashAndRefusesOverwrite() = withTempDir { dir ->
        val home = dir.resolve("home")
        val pw = dir.resolve("pw.txt").also { Files.writeString(it, "correct horse battery\n") }
        val (code, out) = cli(home, "init", "--admin-password-file", pw.toString(), "--name", "Test Box")
        assertEquals(out, 0, code)
        assertTrue(out, Regex("[0-9a-f]{64}").containsMatchIn(out))
        assertTrue("never echoes the password", "correct horse" !in out)

        val hash = Store(ServerHome(home).dbFile).use { it.getSetting(Settings.ADMIN_HASH) }!!
        assertTrue(hash.startsWith("$2"))
        assertTrue(BCrypt.verifyer().verify("correct horse battery".toCharArray(), hash).verified)
        assertEquals("Test Box", Store(ServerHome(home).dbFile).use { it.getSetting(Settings.SERVER_NAME) })

        val (again, againOut) = cli(home, "init", "--admin-password-file", pw.toString())
        assertNotEquals(0, again)
        assertTrue(againOut, "--force" in againOut)

        val (forced, _) = cli(home, "init", "--admin-password-file", pw.toString(), "--force")
        assertEquals(0, forced)
    }

    @Test fun shortPasswordIsRejected() = withTempDir { dir ->
        val pw = dir.resolve("pw.txt").also { Files.writeString(it, "short") }
        val (code, _) = cli(dir.resolve("home"), "init", "--admin-password-file", pw.toString())
        assertNotEquals(0, code)
    }

    private fun codeFrom(out: String): String = Regex("[0-9A-Z]{5}(-[0-9A-Z]{5}){5}").find(out)!!.value

    @Test fun firstRunPrintsASetupCodeBannerOnlyWhenNotSetUp() = withTempDir { dir ->
        val home = ServerHome(dir.resolve("home"))
        Store(home.dbFile).use { store ->
            val buf = ByteArrayOutputStream()
            val printed = PrintStream(buf, true).use { Cli(home.dir, it, it).issueSetupCodeIfNeeded(store, "https://orbit.example.com/setup") }
            assertTrue(printed)
            val out = buf.toString()
            assertTrue(out, out.contains("https://orbit.example.com/setup"))
            assertTrue(SetupCode(store, java.time.Clock.systemUTC()).matches(codeFrom(out)))

            store.putSetting(Settings.ADMIN_HASH, "\$2a\$04\$x")
            val buf2 = ByteArrayOutputStream()
            val again = PrintStream(buf2, true).use { Cli(home.dir, it, it).issueSetupCodeIfNeeded(store, "https://x/setup") }
            assertFalse(again)
            assertEquals("", buf2.toString())
        }
    }

    @Test fun setupCodeCommandRegeneratesAndRefusesOnceSetUp() = withTempDir { dir ->
        val home = dir.resolve("home")
        val (c1, out1) = cli(home, "setup-code")
        assertEquals(out1, 0, c1)
        val (c2, out2) = cli(home, "setup-code")
        assertEquals(out2, 0, c2)
        Store(ServerHome(home).dbFile).use { store ->
            val setup = SetupCode(store, java.time.Clock.systemUTC())
            assertFalse("old code invalidated", setup.matches(codeFrom(out1)))
            assertTrue(setup.matches(codeFrom(out2)))
        }
        val pw = dir.resolve("pw.txt").also { Files.writeString(it, "correct horse battery\n") }
        assertEquals(0, cli(home, "init", "--admin-password-file", pw.toString()).first)
        Store(ServerHome(home).dbFile).use { assertNull("init burns a pending code", it.getSetting(Settings.SETUP_CODE_HASH)) }
        val (c3, out3) = cli(home, "setup-code")
        assertEquals(1, c3)
        assertTrue(out3, out3.contains("already set up"))
    }

    @Test fun behindProxyOptions() {
        val cfg = Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com"))
        assertTrue(cfg.behindProxy)
        assertEquals("https://orbit.example.com", cfg.publicUrl)
        assertEquals(8080, cfg.port)
        assertEquals("loopback by default", "127.0.0.1", cfg.bind)
        assertEquals(ProxyMode.DEFAULT_TRUSTED, cfg.trustedProxies)
        assertEquals("loopback only", listOf(Cidr.parse("127.0.0.0/8"), Cidr.parse("::1/128")), ProxyMode.DEFAULT_TRUSTED)
        assertTrue(Cli.proxyWarnings(cfg).isEmpty())

        // The compose form: host:port in --bind, the compose subnet trusted.
        val compose = Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "0.0.0.0:8080", "--trusted-proxy", "172.20.0.0/16"))
        assertEquals("0.0.0.0", compose.bind)
        assertEquals(8080, compose.port)
        assertTrue(Cli.proxyWarnings(compose).isEmpty())
        val v6 = Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "[::1]:9000"))
        assertEquals("::1", v6.bind)
        assertEquals(9000, v6.port)

        // Broad trusted ranges on a non-loopback bind warn (one line each); on loopback they do not.
        val broad = Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "0.0.0.0", "--trusted-proxy", "10.0.0.0/8,fd00::/8,172.20.0.0/16,fd00:1:2::/48"))
        val warnings = Cli.proxyWarnings(broad)
        assertEquals(warnings.toString(), 2, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("10.0.0.0/8") && '\n' !in warnings[0])
        assertTrue(warnings[1], warnings[1].contains("fd00::/8"))
        assertTrue(Cli.proxyWarnings(Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--trusted-proxy", "10.0.0.0/8"))).isEmpty())
        assertNull("never remembered for self-signed pairing", Cli.portToRemember(cfg))

        val custom = Cli.runConfig(listOf("--behind-proxy", "--public-url", "https://orbit.example.com:8443/", "--trusted-proxy", "172.20.0.0/16,10.9.0.0/16", "--port", "9000", "--bind", "10.9.0.2"))
        assertEquals("https://orbit.example.com:8443", custom.publicUrl)
        assertEquals(listOf(Cidr.parse("172.20.0.0/16"), Cidr.parse("10.9.0.0/16")), custom.trustedProxies)
        assertEquals(9000, custom.port)
        assertEquals("10.9.0.2", custom.bind)
    }

    @Test fun behindProxyRejectsBadCombinations() = withTempDir { dir ->
        val home = dir.resolve("home")
        for (args in listOf(
            listOf("--behind-proxy"),
            listOf("--behind-proxy", "--public-url", "http://orbit.example.com"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com/orbit"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--insecure-dev"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--trusted-proxy", "everyone"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--host", "orbit.example.com"),
            listOf("--public-url", "https://orbit.example.com"),
            listOf("--trusted-proxy", "10.0.0.0/8"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "0.0.0.0:8080", "--port", "9000"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "0.0.0.0:99999", "--trusted-proxy", "172.20.0.0/16"),
            listOf("--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", "http://0.0.0.0", "--trusted-proxy", "172.20.0.0/16"),
        )) {
            val (code, out) = cli(home, *(listOf("run") + args).toTypedArray())
            assertEquals("$args: $out", 2, code)
        }
    }

    @Test fun nonLoopbackBindBehindAProxyNeedsAnExplicitTrustedProxy() = withTempDir { dir ->
        for (bind in listOf("0.0.0.0", "0.0.0.0:8080", "172.20.0.5", "[::]:8080", "orbit-server")) {
            val (code, out) = cli(dir.resolve("home"), "run", "--behind-proxy", "--public-url", "https://orbit.example.com", "--bind", bind)
            assertEquals("$bind: $out", 2, code)
            assertTrue(out, out.contains("behind a proxy requires --trusted-proxy <cidr> naming your proxy's network"))
        }
        assertFalse("nothing was created", Files.exists(dir.resolve("home").resolve(ServerHome.DB_FILE)))
    }

    @Test fun pairAndTokensUseThePublicUrlAfterAProxyRun() = withTempDir { dir ->
        val home = initHome(dir)
        Store(ServerHome(home).dbFile).use { it.putSetting(Settings.PUBLIC_URL, "https://orbit.example.com") }
        val (code, out) = cli(home, "pair")
        assertEquals(out, 0, code)
        val uri = PairingUri.parse(out.lines().single { it.trim().startsWith("orbit://pair?") }.trim())!!
        assertEquals(dev.avijit.orbithealth.sync.TrustMode.CA, uri.mode)
        assertEquals("https://orbit.example.com", uri.url)
        assertNull(uri.fingerprint)
        assertEquals(decodeTerminalQr(out), out.lines().single { it.trim().startsWith("orbit://pair?") }.trim())
        assertEquals(2, cli(home, "pair", "--host", "192.168.1.5").first)

        val (tc, tout) = cli(home, "token", "create", "--name", "claude", "--scopes", "heart")
        assertEquals(tout, 0, tc)
        assertTrue(tout, tout.contains("https://orbit.example.com/mcp"))
        assertFalse(tout, tout.contains("NODE_EXTRA_CA_CERTS"))
    }

    @Test fun unknownCommandsAreUsageErrors() = withTempDir { dir ->
        assertEquals(2, cli(dir, "bogus").first)
        assertEquals(2, cli(dir, "token", "bogus").first)
        assertEquals(2, cli(dir, "cert").first)
    }

    private fun initHome(dir: java.nio.file.Path): java.nio.file.Path {
        val home = dir.resolve("home")
        val pw = dir.resolve("pw.txt").also { Files.writeString(it, "correct horse battery\n") }
        assertEquals(0, cli(home, "init", "--admin-password-file", pw.toString(), "--name", "Test Box").first)
        return home
    }

    @Test fun pairPrintsAValidUriAndADecodableQr() = withTempDir { dir ->
        val home = initHome(dir)
        val (code, out) = cli(home, "pair", "--host", "192.168.1.50", "--port", "18443")
        assertEquals(out, 0, code)
        assertTrue("hint matches the app's button", out.contains("Me → Agent & sync → Scan pairing code"))
        val uriLine = out.lines().single { it.trim().startsWith("orbit://pair?") }.trim()
        val uri = PairingUri.parse(uriLine)!!
        assertEquals("https://192.168.1.50:18443", uri.url)
        assertEquals("Test Box", uri.name)
        val fp = Tls.loadOrCreate(ServerHome(home), emptyList()).fingerprintSha256
        assertEquals(fp, uri.fingerprint)
        assertTrue(out, "10 minutes" in out)

        // The QR in the terminal decodes back to exactly the URI.
        assertEquals(uriLine, decodeTerminalQr(out))

        // The printed token is live and single use.
        Store(ServerHome(home).dbFile).use { store ->
            val pairing = Pairing(store, java.time.Clock.systemUTC())
            assertNotNull(pairing.redeem(uri.token, "Phone"))
            assertNull(pairing.redeem(uri.token, "Phone"))
        }
    }

    @Test fun pairWalkerPrintsAnOrbitWalkerLinkAndTheDefaultIsUnchanged() = withTempDir { dir ->
        val home = initHome(dir)
        val (code, out) = cli(home, "pair", "--host", "192.168.1.50", "--port", "18443", "--walker")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("Scan with OrbitWalker"))
        assertFalse(out, out.lines().any { it.trim().startsWith("orbit://pair?") })
        val line = out.lines().single { it.trim().startsWith("orbitwalker://pair?") }.trim()
        val uri = PairingUri.parse(line)!!
        assertEquals("https://192.168.1.50:18443", uri.url)
        assertEquals(line, uri.toUri(PairingUri.WALKER_PREFIX))
        assertEquals(line, decodeTerminalQr(out))
        Store(ServerHome(home).dbFile).use { assertNotNull(Pairing(it, java.time.Clock.systemUTC()).redeem(uri.token, "Glasses")) }

        val (c2, plain) = cli(home, "pair", "--host", "192.168.1.50", "--port", "18443")
        assertEquals(plain, 0, c2)
        assertFalse(plain, plain.contains("orbitwalker"))
        assertTrue(plain, plain.contains("Scan with Orbit Health (Me → Agent & sync → Scan pairing code):"))
    }

    @Test fun pairUrlOverrideAndDefaults() = withTempDir { dir ->
        val home = initHome(dir)
        val (code, out) = cli(home, "pair", "--url", "https://orbit.example.net:9443/")
        assertEquals(out, 0, code)
        val uri = PairingUri.parse(out.lines().single { it.trim().startsWith("orbit://pair?") }.trim())!!
        assertEquals("https://orbit.example.net:9443", uri.url)

        // No host given: a LAN address (or a fallback name) and the default port.
        val (code2, out2) = cli(home, "pair")
        assertEquals(out2, 0, code2)
        val uri2 = PairingUri.parse(out2.lines().single { it.trim().startsWith("orbit://pair?") }.trim())!!
        assertTrue(uri2.url, uri2.url.startsWith("https://") && uri2.url.endsWith(":8443"))
        assertTrue(uri2.url, "127.0.0.1" !in uri2.url)

        // IPv6 hosts are bracketed.
        val (_, out3) = cli(home, "pair", "--host", "fd00::1")
        assertTrue(out3, PairingUri.parse(out3.lines().single { it.trim().startsWith("orbit://pair?") }.trim())!!.url == "https://[fd00::1]:8443")
    }

    @Test fun pairRejectsBadUrlsAndRequiresInit() = withTempDir { dir ->
        assertNotEquals(0, cli(dir.resolve("fresh"), "pair").first)
        val home = initHome(dir)
        assertEquals(2, cli(home, "pair", "--url", "http://orbit.example.net").first)
        assertEquals(2, cli(home, "pair", "--url", "https://user:pw@orbit.example.net").first)
        assertEquals(2, cli(home, "pair", "--url", "not a url").first)
        assertEquals(2, cli(home, "pair", "--port", "99999").first)
    }

    @Test fun hostValuesAreValidated() = withTempDir { dir ->
        val home = initHome(dir)
        for (bad in listOf("nas.local:8443", "http://nas", "bad host", "a/b", "-lead.example", "999.1.1.1", "", "exa_mple.com")) {
            val (code, out) = cli(home, "pair", "--host", bad)
            assertEquals("'$bad' -> $out", 2, code)
            assertTrue(out, "--host" in out)
            assertTrue("no stack traces: $out", "\tat " !in out)
        }
        assertEquals(2, cli(home, "run", "--host", "nas.local:8443", "--port", "0").first)
        assertEquals(2, cli(dir.resolve("other"), "init", "--host", "x y").first)
        // Valid names and literals pass validation.
        for (good in listOf("nas.local", "Orbit-Box", "10.0.0.7", "fd00::1", "[fd00::1]")) {
            assertEquals(good, 0, cli(home, "pair", "--host", good).first)
        }
    }

    @Test fun ipLiteralDetectionIsStrict() {
        assertTrue(HostNames.isIpLiteral("192.168.1.1"))
        assertTrue(HostNames.isIpLiteral("::1"))
        assertTrue(HostNames.isIpLiteral("fd00::1"))
        assertFalse(HostNames.isIpLiteral("999.1.1.1"))
        assertFalse(HostNames.isIpLiteral("1.2.3"))
        assertFalse(HostNames.isIpLiteral("cafe:beef:zz::1"))
        assertFalse(HostNames.isIpLiteral("a:b"))
        assertFalse(HostNames.isIpLiteral("nas.local"))
        assertNull(HostNames.validate("999.1.1.1")) // all-numeric labels that are not an IP are rejected too
        assertEquals("nas.local", HostNames.validate("NAS.local"))
        assertEquals("fd00::1", HostNames.validate("[FD00::1]"))
    }

    @Test fun seedDemoNeedsAnAcknowledgementAndAnEmptyHome() = withTempDir { dir ->
        val home = initHome(dir)
        val (refused, msg) = cli(home, "seed-demo")
        assertEquals(2, refused)
        assertTrue(msg, "i-understand-this-is-demo-data" in msg)
        Store(ServerHome(home).dbFile).use { assertTrue(it.metricCounts().isEmpty()) }

        val (ok, out) = cli(home, "seed-demo", "--i-understand-this-is-demo-data")
        assertEquals(out, 0, ok)
        assertTrue(out, "DEMO" in out)
        Store(ServerHome(home).dbFile).use { s ->
            assertTrue(s.metricCounts().getValue("HEART_RATE") > 500)
            assertEquals(2, s.listDevices().size)
            assertTrue(s.sleepSessions(0, Long.MAX_VALUE).isNotEmpty())
            assertTrue("tokens are hashes only", s.listAgentTokens().all { it.tokenHash.length == 64 })
        }
        val (again, againOut) = cli(home, "seed-demo", "--i-understand-this-is-demo-data")
        assertEquals("never mixes into existing data", 1, again)
        assertTrue(againOut, "already holds data" in againOut)

        val env = Cli(home, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), mapOf("ORBIT_DEMO" to "1"))
        assertEquals("ORBIT_DEMO=1 also acknowledges (and still refuses a non-empty home)", 1, env.run(listOf("seed-demo")))
    }

    @Test fun publicUrlPrefersNamesTheCertificateCovers() {
        val sans = setOf("localhost", "127.0.0.1", "192.168.1.50", "mac.local")
        assertEquals("https://192.168.1.50:8443", Cli.publicUrl(Cli.runConfig(emptyList()), sans, "192.168.1.50", "mac.local"))
        assertEquals("https://mac.local:8443", Cli.publicUrl(Cli.runConfig(emptyList()), sans, "10.0.0.9", "mac.local"))
        assertEquals("https://mac.local:9443", Cli.publicUrl(Cli.runConfig(listOf("--host", "mac.local", "--port", "9443")), sans, "192.168.1.50", null))
        assertEquals("https://[fd00::5]:8443", Cli.publicUrl(Cli.runConfig(listOf("--host", "fd00::5")), sans, null, null))
        assertEquals("http://localhost:8443", Cli.publicUrl(Cli.runConfig(listOf("--insecure-dev")), emptySet(), null, null))
    }

    @Test fun insecureDevRunsAreNotRememberedForPair() {
        assertEquals(18443, Cli.portToRemember(Cli.runConfig(listOf("--port", "18443"))))
        assertNull("a plain-HTTP test port must never end up in a pairing link", Cli.portToRemember(Cli.runConfig(listOf("--insecure-dev", "--port", "18080"))))
        assertNull(Cli.portToRemember(Cli.runConfig(listOf("--port", "0"))))
    }

    @Test fun insecureDevBindsToLoopbackByDefault() {
        assertEquals("127.0.0.1", Cli.runConfig(listOf("--insecure-dev")).bind)
        assertEquals("0.0.0.0", Cli.runConfig(listOf("--insecure-dev", "--bind", "0.0.0.0")).bind)
        assertEquals("0.0.0.0", Cli.runConfig(emptyList()).bind)
        assertEquals(18443, Cli.runConfig(listOf("--port", "18443")).port)
    }

    @Test fun unexpectedErrorsPrintTypeAndMessageOnly() = withTempDir { dir ->
        // The data dir path is an existing regular file: an unexpected I/O failure deep inside.
        val notADir = dir.resolve("file").also { Files.writeString(it, "x") }
        val pw = dir.resolve("pw.txt").also { Files.writeString(it, "correct horse battery") }
        val (code, out) = cli(notADir, "init", "--admin-password-file", pw.toString())
        assertEquals(out, 1, code)
        assertTrue(out, out.startsWith("error: "))
        assertTrue(out, "Exception" in out)
        assertTrue("no stack trace: $out", "\tat " !in out && out.lines().filter { it.isNotBlank() }.size <= 2)
    }

    @Test fun tokenCreateListRevoke() = withTempDir { dir ->
        val home = initHome(dir)
        val (code, out) = cli(home, "token", "create", "--name", "Claude Code", "--scopes", "heart,sleep", "--host", "localhost", "--port", "18443")
        assertEquals(out, 0, code)
        val token = Regex("Bearer ([A-Za-z0-9_-]{43})").find(out)!!.groupValues[1]
        assertTrue(out, out.contains("claude mcp add --transport http orbit https://localhost:18443/mcp --header \"Authorization: Bearer $token\""))
        assertTrue(out, out.contains("\"url\": \"https://localhost:18443/mcp\""))
        val fp = Tls.loadOrCreate(ServerHome(home), emptyList()).fingerprintSha256
        assertTrue(out, out.contains(fp))
        assertTrue(out, out.contains("orbit-server cert export"))
        assertTrue(out, out.contains("NODE_EXTRA_CA_CERTS"))
        assertTrue(out, out.contains("shown once"))

        val row = Store(ServerHome(home).dbFile).use { store -> store.agentTokenByHash(Secrets.sha256Hex(token)) }!!
        assertEquals("Claude Code", row.name)
        assertEquals(setOf("heart", "sleep"), row.scopes)

        val (lc, list) = cli(home, "token", "list")
        assertEquals(0, lc)
        assertTrue(list, list.contains(row.id) && list.contains("Claude Code") && list.contains("heart,sleep") && list.contains("active"))
        assertFalse("list never prints a token", list.contains(token))

        val (rc, revoked) = cli(home, "token", "revoke", row.id.take(8))
        assertEquals(0, rc)
        assertTrue(revoked, revoked.contains("Revoked agent token"))
        assertTrue(cli(home, "token", "list").second.contains("revoked"))
        val (again, already) = cli(home, "token", "revoke", row.id)
        assertEquals("revoking twice is not an error", 0, again)
        assertTrue(already, already.contains("already revoked") && !already.contains("Revoked agent token"))
        assertEquals(1, cli(home, "token", "revoke", "00000000-nope").first)

        // Usage errors.
        assertEquals(2, cli(home, "token", "create", "--name", "x", "--scopes", "heart_rate").first)
        assertEquals(2, cli(home, "token", "create", "--scopes", "heart").first)
        assertEquals(2, cli(home, "token", "create", "--name", "x").first)
        assertEquals(2, cli(home, "token", "revoke").first)
        // "all" grants every scope.
        val (ac, allOut) = cli(home, "token", "create", "--name", "everything", "--scopes", "all", "--host", "localhost")
        assertEquals(allOut, 0, ac)
        assertTrue(allOut, allOut.contains("activity, heart, profile, sleep, spo2, stress"))
    }

    @Test fun certExportWritesThePemWithItsFingerprint() = withTempDir { dir ->
        val home = initHome(dir)
        val file = dir.resolve("orbit.pem")
        val (code, out) = cli(home, "cert", "export", file.toString())
        assertEquals(out, 0, code)
        val pem = Files.readString(file)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertFalse("never the private key", pem.contains("PRIVATE"))
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(file)) as java.security.cert.X509Certificate
        val fp = Tls.fingerprint(cert)
        assertEquals(Tls.loadOrCreate(ServerHome(home), emptyList()).fingerprintSha256, fp)
        assertTrue(out, out.contains(fp))
        assertEquals("refuses to overwrite", 1, cli(home, "cert", "export", file.toString()).first)
        assertEquals(0, cli(home, "cert", "export", file.toString(), "--force").first)
        assertNotEquals(0, cli(dir.resolve("fresh"), "cert", "export", dir.resolve("x.pem").toString()).first)
    }
}
