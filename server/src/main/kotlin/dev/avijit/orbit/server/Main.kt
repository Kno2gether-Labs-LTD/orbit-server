package dev.avijit.orbit.server

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.avijit.orbithealth.sync.PairingUri
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = Cli(ServerHome.defaultDir(), System.out, System.err).run(args.toList())
    if (code != 0) exitProcess(code)
}

/**
 * `orbit-server <command>`. Never prints the admin password, tokens or health values.
 * Exit codes: 0 ok, 1 runtime failure, 2 usage error.
 */
class Cli(
    private val homeDir: Path,
    private val out: PrintStream,
    private val err: PrintStream,
    private val env: Map<String, String> = System.getenv(),
) {

    fun run(args: List<String>): Int = try {
        when (args.firstOrNull()) {
            "init" -> init(Args.parse(args.drop(1)))
            "run" -> runServer(Args.parse(args.drop(1)))
            "setup-code" -> setupCode(Args.parse(args.drop(1)))
            "pair" -> pair(Args.parse(args.drop(1)))
            "token" -> token(args.drop(1))
            "cert" -> cert(args.drop(1))
            "seed-demo" -> seedDemo(Args.parse(args.drop(1)))
            null, "help", "--help", "-h" -> { out.println(USAGE); 0 }
            else -> usage("unknown command '${args[0]}'")
        }
    } catch (e: UsageException) {
        usage(e.message ?: "bad arguments")
    } catch (e: CliFailure) {
        err.println("error: ${e.message}")
        1
    } catch (e: Exception) {
        // Anything unexpected: type and a short message only. No stack trace (it can carry request data).
        val msg = e.message?.lineSequence()?.firstOrNull()?.take(200).orEmpty()
        err.println("error: ${e::class.java.simpleName}${if (msg.isNotEmpty()) ": $msg" else ""}")
        1
    }

    private fun init(a: Args): Int {
        a.allowOnly("admin-password-file", "force", "host", "name", "regenerate-cert")
        val hosts = validHosts(a)
        val home = ServerHome(homeDir)
        Store(home.dbFile).use { store ->
            if (store.getSetting(Settings.ADMIN_HASH) != null && !a.flag("force")) {
                throw CliFailure("already initialised at ${home.dir}. Use --force to replace the admin password.")
            }
            val password = readAdminPassword(a.value("admin-password-file"))
            try {
                validatePassword(password)
                val hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password)
                MasterKey(home)
                val tls = Tls.loadOrCreate(home, hosts, regenerate = a.flag("regenerate-cert"))
                store.putSetting(Settings.ADMIN_HASH, hash)
                // A pending first-run setup code is no longer needed.
                store.deleteSetting(Settings.SETUP_CODE_HASH)
                store.deleteSetting(Settings.SETUP_CODE_EXPIRES)
                val name = a.value("name") ?: store.getSetting(Settings.SERVER_NAME) ?: Tls.machineHostname() ?: "Orbit Server"
                store.putSetting(Settings.SERVER_NAME, name)
                out.println("Orbit Server initialised in ${home.dir}")
                out.println("Server name:      $name")
                out.println("TLS fingerprint:  ${tls.fingerprintSha256}")
                out.println("TLS names:        ${Tls.subjectAltNames(tls).joinToString(", ")}")
                out.println("Next: orbit-server run   (HTTPS on port ${RunConfig.DEFAULT_PORT})")
            } finally {
                password.fill('\u0000')
            }
        }
        return 0
    }

    private fun runServer(a: Args): Int {
        val cfg = runConfig(a)
        // `init` is optional: a fresh data directory is created here, and the admin password is set on the
        // dashboard's setup page with the one-time code printed below.
        val home = ServerHome(homeDir)
        val masterKey = MasterKey(home)
        val store = Store(home.dbFile)
        val deps: ServerDeps
        val tls: TlsMaterial?
        if (cfg.behindProxy) {
            val publicUrl = cfg.publicUrl!!
            val proxy = ProxyMode(publicUrl, cfg.trustedProxies)
            tls = null
            store.putSetting(Settings.PUBLIC_URL, publicUrl)
            if (store.getSetting(Settings.SERVER_NAME) == null) store.putSetting(Settings.SERVER_NAME, proxy.publicHost)
            out.println("Orbit Server listening on http://${cfg.bind}:${cfg.port} for a reverse proxy (TLS ends at the proxy)")
            out.println("Public URL:      $publicUrl/   (phones pair against this address; public certificate)")
            out.println("Trusted proxies: ${cfg.trustedProxies.joinToString(", ")}")
            proxyWarnings(cfg).forEach(err::println)
            deps = ServerDeps(store = store, fingerprint = null, masterKey = masterKey, originHosts = setOf(proxy.publicHost), publicUrl = publicUrl, proxy = proxy)
            issueSetupCodeIfNeeded(store, "$publicUrl/setup")
        } else {
            tls = if (cfg.insecureDev) null else Tls.loadOrCreate(home, cfg.hosts, cfg.regenerateCert)
            if (cfg.insecureDev) {
                err.println("WARNING: --insecure-dev serves plain HTTP. For tests only; never expose it to a network.")
            }
            store.deleteSetting(Settings.PUBLIC_URL)
            if (store.getSetting(Settings.SERVER_NAME) == null) store.putSetting(Settings.SERVER_NAME, Tls.machineHostname() ?: Pairing.DEFAULT_SERVER_NAME)
            val scheme = if (cfg.insecureDev) "http" else "https"
            out.println("Orbit Server listening on $scheme://${cfg.bind}:${cfg.port}")
            tls?.let { out.println("TLS fingerprint: ${it.fingerprintSha256}") }
            portToRemember(cfg)?.let { store.putSetting(Settings.SERVER_PORT, it.toString()) }
            val sans = tls?.let(Tls::subjectAltNames).orEmpty()
            val publicUrl = publicUrl(cfg, sans.map { it.lowercase() }.toSet(), HostNames.firstLanAddress(), Tls.machineHostname()?.let(HostNames::validate))
            out.println("Dashboard:       $publicUrl/   (phones pair against this address)")
            deps = ServerDeps(
                store = store, fingerprint = tls?.fingerprintSha256, masterKey = masterKey,
                originHosts = (sans + cfg.hosts).toSet(),
                publicUrl = publicUrl, certPem = tls?.let { Tls.pem(it.certificate) },
            )
            issueSetupCodeIfNeeded(store, "$publicUrl/setup")
        }
        val server = createServer(cfg, tls, deps)
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop(1_000, 5_000)
            store.close()
        })
        server.start(wait = true)
        return 0
    }

    /**
     * When no admin password exists yet: issues a fresh one-time setup code (replacing any earlier one) and prints
     * the banner to stdout. Only stdout: never a log file, the access log or the database (which keeps a hash).
     */
    internal fun issueSetupCodeIfNeeded(store: Store, setupUrl: String): Boolean {
        if (store.getSetting(Settings.ADMIN_HASH) != null) return false
        out.print(SetupCode.banner(SetupCode(store, java.time.Clock.systemUTC()).issue(), setupUrl))
        out.flush()
        return true
    }

    /** `setup-code`: a new one-time setup code (the old one stops working). Refused once the server is set up. */
    private fun setupCode(a: Args): Int {
        a.allowOnly()
        val home = ServerHome(homeDir)
        Store(home.dbFile).use { store ->
            if (store.getSetting(Settings.ADMIN_HASH) != null) {
                throw CliFailure("already set up. To replace the admin password run: orbit-server init --force")
            }
            val base = store.getSetting(Settings.PUBLIC_URL)
                ?: "https://<this server>:${store.getSetting(Settings.SERVER_PORT) ?: RunConfig.DEFAULT_PORT}"
            issueSetupCodeIfNeeded(store, "$base/setup")
        }
        return 0
    }

    /**
     * `pair [--host H | --url U] [--port P] [--walker]`: a one-time pairing URI and QR, valid for 10 minutes.
     * `--walker` prints the same kind of link for the OrbitWalker glasses app (`orbitwalker://pair`).
     */
    private fun pair(a: Args): Int {
        a.allowOnly("host", "port", "url", "walker")
        val prefix = if (a.flag("walker")) PairingUri.WALKER_PREFIX else PairingUri.ORBIT_PREFIX
        val hosts = validHosts(a)
        if (hosts.size > 1) throw UsageException("give --host once")
        val port = a.value("port")?.let(::parsePort)
        val explicitUrl = a.value("url")?.let(::parsePairUrl)
        if (explicitUrl != null && (hosts.isNotEmpty() || port != null)) throw UsageException("--url cannot be combined with --host/--port")
        val home = requireInitialised()
        val proxyUrl = Store(home.dbFile).use { it.getSetting(Settings.PUBLIC_URL) }
        if (proxyUrl != null) {
            if (hosts.isNotEmpty() || port != null) throw UsageException("this server runs behind a reverse proxy; use --url (default $proxyUrl)")
            return pairPublicCa(home, explicitUrl ?: proxyUrl, prefix)
        }
        val tls = Tls.loadOrCreate(home, hosts)
        Store(home.dbFile).use { store ->
            val url = explicitUrl ?: run {
                val host = hosts.firstOrNull() ?: HostNames.firstLanAddress() ?: Tls.machineHostname()?.let(HostNames::validate) ?: "localhost"
                "https://${HostNames.forUrl(host)}:${port ?: lastPort(store)}"
            }
            val name = store.getSetting(Settings.SERVER_NAME) ?: Pairing.DEFAULT_SERVER_NAME
            val uri = Pairing(store, java.time.Clock.systemUTC()).newToken(url, tls.fingerprintSha256, name).toUri(prefix)
            printPairing(uri, url, "Fingerprint: ${tls.fingerprintSha256}", prefix)
        }
        return 0
    }

    /** `pair` after `run --behind-proxy`: a trust-mode `ca` link (public certificate, exact host, no fingerprint). */
    private fun pairPublicCa(home: ServerHome, url: String, prefix: String): Int {
        Store(home.dbFile).use { store ->
            val name = store.getSetting(Settings.SERVER_NAME) ?: Pairing.DEFAULT_SERVER_NAME
            val uri = Pairing(store, java.time.Clock.systemUTC()).newPublicCaToken(url, name).toUri(prefix)
            printPairing(uri, url, "Certificate: public (the app checks it for this host name)", prefix)
        }
        return 0
    }

    private fun printPairing(uri: String, url: String, trustLine: String, prefix: String) {
        val expires = java.time.LocalTime.now().plusSeconds(Pairing.TOKEN_TTL_MS / 1000).withNano(0)
        if (prefix == PairingUri.WALKER_PREFIX) out.println("Scan with OrbitWalker (AR glasses):")
        else out.println("Scan with Orbit Health (Me → Agent & sync → Scan pairing code):")
        out.println()
        out.print(TerminalQr.render(uri))
        out.println()
        out.println("Or paste this pairing link into the app:")
        out.println(uri)
        out.println()
        out.println("Server:      $url")
        out.println(trustLine)
        out.println("One-time link, expires in 10 minutes (at $expires). Do not share it.")
    }

    /**
     * `seed-demo --i-understand-this-is-demo-data` (or `ORBIT_DEMO=1`): fills an initialised but empty data directory
     * with a week of invented readings, devices, tokens and access-log rows, for screenshots and trying the dashboard.
     */
    private fun seedDemo(a: Args): Int {
        a.allowOnly(DEMO_ACK)
        if (!a.flag(DEMO_ACK) && env["ORBIT_DEMO"] != "1") {
            throw UsageException("seed-demo writes invented health data. Confirm with --$DEMO_ACK (or ORBIT_DEMO=1)")
        }
        val home = requireInitialised()
        Store(home.dbFile).use { store ->
            if (store.metricCounts().isNotEmpty() || store.listDevices().isNotEmpty()) {
                throw CliFailure("${home.dir} already holds data; seed-demo only runs on an empty data directory")
            }
            val c = DemoData.seed(store, MasterKey(home), java.time.Clock.systemUTC(), java.time.ZoneId.systemDefault())
            out.println("Seeded DEMO data in ${home.dir}: ${c.samples} samples, ${c.nights} nights, 2 devices, 3 agent tokens.")
            out.println("This is invented data. Do not use this data directory for real readings.")
        }
        return 0
    }

    private fun requireInitialised(): ServerHome {
        val hint = "not set up yet. Start the server (orbit-server run) and use the setup code it prints, or run: orbit-server init"
        if (!Files.exists(homeDir.resolve(ServerHome.DB_FILE))) throw CliFailure(hint)
        val home = ServerHome(homeDir)
        val initialised = Store(home.dbFile).use { it.getSetting(Settings.ADMIN_HASH) != null }
        if (!initialised) throw CliFailure(hint)
        return home
    }

    private fun lastPort(store: Store): Int = store.getSetting(Settings.SERVER_PORT)?.toIntOrNull() ?: RunConfig.DEFAULT_PORT

    private fun token(args: List<String>): Int = when (args.firstOrNull()) {
        "create" -> tokenCreate(Args.parse(args.drop(1)))
        "list" -> { Args.parse(args.drop(1)).allowOnly(); tokenList() }
        "revoke" -> {
            val id = args.getOrNull(1)?.takeUnless { it.startsWith("--") } ?: throw UsageException("usage: token revoke <id>")
            if (args.size > 2) throw UsageException("usage: token revoke <id>")
            tokenRevoke(id)
        }
        else -> throw UsageException("unknown token command; use create, list or revoke")
    }

    /**
     * `token create --name N --scopes heart,sleep|all [--host H] [--port P] | [--url https://host:port]`: prints the
     * token once, with ready-to-paste MCP client config and how to trust the self-signed certificate.
     */
    private fun tokenCreate(a: Args): Int {
        a.allowOnly("name", "scopes", "host", "port", "url")
        val name = a.value("name") ?: throw UsageException("--name is required (it labels the token in the access log)")
        val scopes = a.value("scopes")?.let(AgentTokens::parseScopes)
            ?: throw UsageException("--scopes is required: ${dev.avijit.orbithealth.sync.Scope.ALL_WIRE.sorted().joinToString(",")} or all")
        val hosts = validHosts(a)
        if (hosts.size > 1) throw UsageException("give --host once")
        val port = a.value("port")?.let(::parsePort)
        val explicitUrl = a.value("url")?.let(::parsePairUrl)
        if (explicitUrl != null && (hosts.isNotEmpty() || port != null)) throw UsageException("--url cannot be combined with --host/--port")
        val home = requireInitialised()
        val proxyUrl = Store(home.dbFile).use { it.getSetting(Settings.PUBLIC_URL) }
        if (proxyUrl != null && (hosts.isNotEmpty() || port != null)) {
            throw UsageException("this server runs behind a reverse proxy; use --url (default $proxyUrl)")
        }
        val tls = if (proxyUrl == null) Tls.loadOrCreate(home, emptyList()) else null
        val sans = tls?.let { Tls.subjectAltNames(it).toSet() }.orEmpty()
        Store(home.dbFile).use { store ->
            val created = try {
                AgentTokens(store, java.time.Clock.systemUTC()).create(name, scopes)
            } catch (e: IllegalArgumentException) {
                throw UsageException(e.message ?: "bad token request")
            }
            var uncovered: String? = null
            val base = explicitUrl ?: proxyUrl ?: run {
                val candidates = hosts.ifEmpty {
                    listOfNotNull(HostNames.firstLanAddress(), Tls.machineHostname()?.let(HostNames::validate), "localhost")
                }
                val host = candidates.firstOrNull { it.lowercase() in sans } ?: candidates.first().also { uncovered = it }
                "https://${HostNames.forUrl(host)}:${port ?: lastPort(store)}"
            }
            val mcpUrl = "$base/mcp"
            val t = created.token
            val row = created.row
            out.println("Agent token created: ${row.name} (id ${row.id})")
            out.println("Scopes: ${row.scopes.sorted().joinToString(", ")}")
            out.println()
            out.println("Token (shown once; store it like a password):")
            out.println("  $t")
            out.println()
            out.println("MCP endpoint: $mcpUrl")
            out.println()
            out.println("Claude Code:")
            out.println("  claude mcp add --transport http orbit $mcpUrl --header \"Authorization: Bearer $t\"")
            out.println()
            out.println("JSON config (.mcp.json, Claude Desktop and other MCP clients):")
            out.println("""  {
                |    "mcpServers": {
                |      "orbit": {
                |        "type": "http",
                |        "url": "$mcpUrl",
                |        "headers": { "Authorization": "Bearer $t" }
                |      }
                |    }
                |  }""".trimMargin())
            out.println()
            out.println("REST (scripts):")
            if (tls == null) {
                out.println("  curl -H \"Authorization: Bearer $t\" \"$base/v1/data/samples?metric=HEART_RATE&agg=hourly\"")
                out.println()
                out.println("The server uses a public certificate through your reverse proxy; clients need no extra trust setup.")
                out.println("Revoke any time: orbit-server token revoke ${row.id}")
                return 0
            }
            out.println("  curl --cacert orbit-server.pem -H \"Authorization: Bearer $t\" \"$base/v1/data/samples?metric=HEART_RATE&agg=hourly\"")
            out.println()
            out.println("The server uses a self-signed certificate. SHA-256 fingerprint:")
            out.println("  ${tls.fingerprintSha256}")
            out.println("Export it and let your client trust it:")
            out.println("  orbit-server cert export orbit-server.pem")
            out.println("  NODE_EXTRA_CA_CERTS=\$PWD/orbit-server.pem claude    (Node-based clients such as Claude Code)")
            uncovered?.let {
                out.println("Note: the certificate does not list '$it', so clients that check host names will refuse it.")
                out.println("  Use --host localhost on this machine, or restart with: orbit-server run --host $it --regenerate-cert")
                out.println("  (a new certificate changes the fingerprint; phones must re-pair).")
            }
            out.println("Revoke any time: orbit-server token revoke ${row.id}")
        }
        return 0
    }

    private fun tokenList(): Int {
        val home = requireInitialised()
        val tokens = Store(home.dbFile).use { AgentTokens(it, java.time.Clock.systemUTC()).list() }
        if (tokens.isEmpty()) {
            out.println("No agent tokens. Create one: orbit-server token create --name <name> --scopes heart,sleep")
            return 0
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())
        out.println(String.format("%-36s  %-24s  %-40s  %-16s  %s", "ID", "NAME", "SCOPES", "CREATED", "STATUS"))
        for (t in tokens) {
            out.println(String.format("%-36s  %-24s  %-40s  %-16s  %s", t.id, t.name, t.scopes.sorted().joinToString(","),
                fmt.format(java.time.Instant.ofEpochMilli(t.createdMs)), if (t.revoked) "revoked" else "active"))
        }
        return 0
    }

    private fun tokenRevoke(idOrPrefix: String): Int {
        val home = requireInitialised()
        Store(home.dbFile).use { store ->
            val tokens = AgentTokens(store, java.time.Clock.systemUTC())
            val id = tokens.resolveId(idOrPrefix.trim())
                ?: throw CliFailure("no single agent token matches '${idOrPrefix.take(40)}' (see: orbit-server token list)")
            val row = tokens.list().first { it.id == id }
            if (row.revoked) {
                out.println("Agent token ${row.name} ($id) was already revoked.")
                return 0
            }
            tokens.revoke(id)
            out.println("Revoked agent token ${row.name} ($id). Requests with it are refused from now on.")
        }
        return 0
    }

    /** `cert export <file|-> [--force]`: the public TLS certificate as PEM, for MCP clients and curl to trust. */
    private fun cert(args: List<String>): Int {
        if (args.firstOrNull() != "export") throw UsageException("usage: cert export <file|-> [--force]")
        val target = args.getOrNull(1)?.takeUnless { it.startsWith("--") && it != "-" } ?: throw UsageException("usage: cert export <file|-> [--force]")
        val a = Args.parse(args.drop(2))
        a.allowOnly("force")
        val home = requireInitialised()
        val tls = Tls.loadOrCreate(home, emptyList())
        val pem = Tls.pem(tls.certificate)
        if (target == "-") {
            out.print(pem)
            err.println("SHA-256 fingerprint: ${tls.fingerprintSha256}")
            return 0
        }
        val path = Paths.get(target).toAbsolutePath()
        if (Files.exists(path) && !a.flag("force")) throw CliFailure("$path exists; use --force to overwrite")
        Files.writeString(path, pem)
        out.println("Wrote the server certificate (public, no private key) to $path")
        out.println("SHA-256 fingerprint: ${tls.fingerprintSha256}")
        out.println("Trust it in your MCP client, e.g.:")
        out.println("  NODE_EXTRA_CA_CERTS=$path claude")
        out.println("  curl --cacert $path https://<host>:<port>/health")
        return 0
    }

    private fun usage(msg: String): Int {
        err.println("error: $msg")
        err.println(USAGE)
        return 2
    }

    private fun readAdminPassword(file: String?): CharArray {
        if (file != null) {
            val p = Paths.get(file)
            if (!Files.isReadable(p)) throw CliFailure("cannot read --admin-password-file $file")
            // Strip only the trailing line break an editor or `echo` adds.
            return Files.readString(p).removeSuffix("\n").removeSuffix("\r").toCharArray()
        }
        val console = System.console() ?: throw CliFailure("no interactive console; pass --admin-password-file <file>")
        val first = console.readPassword("Admin password (min $MIN_PASSWORD chars): ") ?: throw CliFailure("no password entered")
        val second = console.readPassword("Repeat admin password: ") ?: throw CliFailure("no password entered")
        val same = first.contentEquals(second)
        second.fill('\u0000')
        if (!same) {
            first.fill('\u0000')
            throw CliFailure("passwords do not match")
        }
        return first
    }

    private fun validatePassword(pw: CharArray) {
        if (pw.size < MIN_PASSWORD) throw CliFailure("admin password must be at least $MIN_PASSWORD characters")
        // bcrypt only uses the first 72 bytes; refuse longer input rather than silently truncating it.
        if (String(pw).toByteArray(Charsets.UTF_8).size > 72) throw CliFailure("admin password must be at most 72 bytes")
    }

    companion object {
        /** Parses `run` options. `--insecure-dev` binds to loopback unless `--bind` is given explicitly. */
        fun runConfig(args: List<String>): RunConfig = runConfig(Args.parse(args))

        private fun runConfig(a: Args): RunConfig {
            a.allowOnly("port", "bind", "host", "regenerate-cert", "insecure-dev", "behind-proxy", "public-url", "trusted-proxy")
            val insecure = a.flag("insecure-dev")
            if (a.flag("behind-proxy")) {
                if (insecure) throw UsageException("--behind-proxy and --insecure-dev cannot be combined")
                if (a.values("host").isNotEmpty() || a.flag("regenerate-cert")) {
                    throw UsageException("--host and --regenerate-cert are for the self-signed certificate; behind a proxy use --public-url")
                }
                val publicUrl = a.value("public-url")?.let { parseHttpsOrigin(it, "--public-url") }
                    ?: throw UsageException("--behind-proxy needs --public-url https://<your domain>")
                val explicit = a.values("trusted-proxy").takeIf { it.isNotEmpty() }?.let {
                    try { ProxyMode.parseTrusted(it) } catch (e: IllegalArgumentException) { throw UsageException("bad --trusted-proxy: ${e.message}") }
                }
                val (bindHost, bindPort) = a.value("bind")?.let(::parseBind) ?: ("127.0.0.1" to null)
                val flagPort = a.value("port")?.let(::parsePort)
                if (bindPort != null && flagPort != null) throw UsageException("give the port once: in --bind host:port or with --port")
                if (explicit == null && !isLoopback(bindHost)) {
                    throw UsageException("binding $bindHost behind a proxy requires --trusted-proxy <cidr> naming your proxy's network")
                }
                return RunConfig(
                    bind = bindHost,
                    port = bindPort ?: flagPort ?: RunConfig.DEFAULT_PROXY_PORT,
                    behindProxy = true, publicUrl = publicUrl, trustedProxies = explicit ?: ProxyMode.DEFAULT_TRUSTED,
                )
            }
            if (a.flag("public-url") || a.flag("trusted-proxy")) throw UsageException("--public-url and --trusted-proxy need --behind-proxy")
            return RunConfig(
                bind = a.value("bind") ?: if (insecure) "127.0.0.1" else "0.0.0.0",
                port = a.value("port")?.let(::parsePort) ?: RunConfig.DEFAULT_PORT,
                hosts = validHosts(a),
                regenerateCert = a.flag("regenerate-cert"),
                insecureDev = insecure,
            )
        }

        /**
         * One-line stderr warnings for a proxy run: a trusted range wider than /16 (IPv4) or /48 (IPv6) while
         * listening on a non-loopback address lets other hosts in that range forge X-Forwarded-* headers.
         */
        fun proxyWarnings(cfg: RunConfig): List<String> {
            if (!cfg.behindProxy || isLoopback(cfg.bind)) return emptyList()
            return cfg.trustedProxies.filter { it.isBroad }.map {
                "WARNING: --trusted-proxy $it is wider than /16 (IPv4) or /48 (IPv6); every host in it can forge client addresses. Name only your proxy's network."
            }
        }

        /** `host`, `host:port`, `[v6]` or `[v6]:port` for `--bind` in proxy mode. */
        private fun parseBind(raw: String): Pair<String, Int?> {
            val t = raw.trim()
            val bad = UsageException("bad --bind '${t.take(80)}': expected host or host:port")
            val (host, port) = when {
                t.startsWith("[") -> {
                    val end = t.indexOf(']').takeIf { it > 0 } ?: throw bad
                    val rest = t.substring(end + 1)
                    t.substring(1, end) to when {
                        rest.isEmpty() -> null
                        rest.startsWith(":") -> rest.substring(1)
                        else -> throw bad
                    }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to null
            }
            val h = HostNames.validate(host) ?: throw bad
            return h to port?.let { p -> p.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw bad }
        }

        /** Loopback by name or literal (never resolved); `0.0.0.0` / `::` and every other name are not. */
        internal fun isLoopback(host: String): Boolean {
            val h = host.lowercase().removePrefix("[").removeSuffix("]")
            if (h == "localhost" || h.endsWith(".localhost")) return true
            if (!HostNames.isIpLiteral(h)) return false
            return try { java.net.InetAddress.getByName(h).isLoopbackAddress } catch (e: Exception) { false }
        }

        /**
         * The address phones and agents should use: the first `--host` the certificate covers, else the first LAN
         * address, machine name or `localhost` it covers, else the first candidate. Plain `http` only for `--insecure-dev`.
         */
        fun publicUrl(cfg: RunConfig, sans: Set<String>, lanAddress: String?, machineName: String?): String {
            val candidates = cfg.hosts.ifEmpty { listOfNotNull(lanAddress, machineName, "localhost") }
            val host = candidates.firstOrNull { it.lowercase() in sans } ?: candidates.first()
            return "${if (cfg.insecureDev) "http" else "https"}://${HostNames.forUrl(host)}:${cfg.port}"
        }

        /**
         * The port `pair` should default to after this run: the HTTPS port, or null for an ephemeral port (0) or an
         * `--insecure-dev` run (a plain-HTTP test port must never end up in a pairing link).
         */
        fun portToRemember(cfg: RunConfig): Int? = cfg.port.takeIf { it != 0 && !cfg.insecureDev && !cfg.behindProxy }

        private fun parsePort(p: String): Int = p.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw UsageException("bad --port '$p'")

        private fun validHosts(a: Args): List<String> = a.values("host").map { h ->
            HostNames.validate(h) ?: throw UsageException(
                "bad --host '${h.take(80)}': give a host name or IP address only (no scheme, port or path)",
            )
        }

        private fun parsePairUrl(raw: String): String = parseHttpsOrigin(raw, "--url")

        /** `https://host[:port]` only: no user info, path, query or fragment. Returned without a trailing slash. */
        private fun parseHttpsOrigin(raw: String, option: String): String {
            val bad = UsageException("bad $option '${raw.take(80)}': expected https://host[:port]")
            val u = try { java.net.URI(raw.trim()) } catch (e: java.net.URISyntaxException) { throw bad }
            if (u.scheme?.lowercase() != "https" || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) throw bad
            if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) throw bad
            val host = u.host?.let { HostNames.validate(it) } ?: throw bad
            if (u.port != -1 && u.port !in 1..65535) throw bad
            return "https://${HostNames.forUrl(host)}${if (u.port != -1) ":${u.port}" else ""}"
        }

        private const val DEMO_ACK = "i-understand-this-is-demo-data"
        const val BCRYPT_COST = 12
        const val MIN_PASSWORD = 10
        val USAGE = """
            |Usage: orbit-server <command> [options]
            |
            |Data directory: ${'$'}ORBIT_SERVER_HOME or ~/.orbit-server
            |
            |Commands:
            |  init   [--admin-password-file F] [--name N] [--host H]... [--force] [--regenerate-cert]
            |         Optional: create the data dir, TLS identity, master key and database and set the admin
            |         password from the console (otherwise set it on the dashboard with the setup code).
            |  run    [--port 8443] [--bind 0.0.0.0] [--host H]... [--regenerate-cert] [--insecure-dev]
            |         Serve HTTPS. --regenerate-cert changes the fingerprint (phones must re-pair).
            |         --insecure-dev (tests only) serves plain HTTP on 127.0.0.1 unless --bind is given.
            |         Before an admin password exists, prints a one-time setup code for the dashboard's setup page.
            |  run    --behind-proxy --public-url https://orbit.example.com [--trusted-proxy CIDR,...]
            |         [--bind 127.0.0.1[:8080]] [--port 8080]
            |         Plain HTTP for a TLS-terminating reverse proxy (Caddy). Listens on 127.0.0.1:8080 by default.
            |         X-Forwarded-For/Proto are trusted only from --trusted-proxy (default: loopback only).
            |         Binding a non-loopback address (e.g. --bind 0.0.0.0:8080 in Docker) requires
            |         --trusted-proxy naming the proxy's network (e.g. the Compose subnet).
            |         Phones pair against the public URL and check its public certificate.
            |  setup-code
            |         Print a new one-time setup code (the old one stops working). Only before setup.
            |  pair   [--host H] [--port P] | [--url https://host:port]  [--walker]
            |         Print a one-time pairing QR code and link (valid 10 minutes). The URL defaults to
            |         https://<first LAN IPv4 address>:<last run port, else 8443>.
            |         --walker prints an orbitwalker://pair link for the OrbitWalker glasses app instead.
            |  token create --name N --scopes heart,sleep,...|all [--host H] [--port P] | [--url U]
            |         Create an agent token for MCP / the data API. Prints the token once, with MCP client config.
            |  token list                 List agent tokens (never the secrets).
            |  token revoke <id>          Revoke a token (a unique id prefix of 8+ characters works).
            |  cert export <file|-> [--force]
            |         Write the server's TLS certificate (PEM) so MCP clients and curl can trust it.
            |  seed-demo --i-understand-this-is-demo-data
            |         Fill an empty data directory with a week of INVENTED readings (screenshots, trying it out).
        """.trimMargin()
    }
}

private class UsageException(msg: String) : Exception(msg)
private class CliFailure(msg: String) : Exception(msg)

/** Minimal `--key value` / `--key=value` / `--flag` parser; repeated keys collect. */
private class Args(private val map: Map<String, List<String>>) {
    fun value(k: String): String? = map[k]?.lastOrNull()?.takeIf { it != FLAG }
    fun values(k: String): List<String> = map[k].orEmpty().filter { it != FLAG }
    fun flag(k: String): Boolean = map.containsKey(k)
    fun allowOnly(vararg keys: String) {
        val unknown = map.keys - keys.toSet()
        if (unknown.isNotEmpty()) throw UsageException("unknown option(s): ${unknown.joinToString { "--$it" }}")
    }

    companion object {
        private const val FLAG = "\u0000flag"
        private val FLAGS = setOf("force", "regenerate-cert", "insecure-dev", "behind-proxy", "i-understand-this-is-demo-data", "walker")

        fun parse(args: List<String>): Args {
            val m = LinkedHashMap<String, MutableList<String>>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (!a.startsWith("--") || a.length == 2) throw UsageException("unexpected argument '$a'")
                val body = a.substring(2)
                val eq = body.indexOf('=')
                when {
                    eq >= 0 -> m.getOrPut(body.substring(0, eq)) { mutableListOf() } += body.substring(eq + 1)
                    body in FLAGS -> m.getOrPut(body) { mutableListOf() } += FLAG
                    i + 1 < args.size -> { m.getOrPut(body) { mutableListOf() } += args[i + 1]; i++ }
                    else -> throw UsageException("missing value for --$body")
                }
                i++
            }
            return Args(m)
        }
    }
}
