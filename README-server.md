# Orbit Server

Orbit Server is the self-hosted companion to the Orbit Health app. You run it on a machine you control: your Mac, a home server or a small VPS. Once you pair the phone, it pushes your ring data to the server over HTTPS. The phone pins the server's certificate, and it keeps its own copy of everything. The server then gives you:

- **A dashboard** at `https://<host>:8443/`. It shows your latest readings, devices, agent settings, agent tokens, the access log and a JSON export.
- **An agent** that answers questions you ask in the app's Ask tab, using your data. It calls read-only tools such as `get_metric`, `get_sleep`, `daily_summary` and `compare_to_baseline`, and every answer says what it was based on. You choose the model: the Claude API, or a local model through Ollama or LM Studio.
- **An MCP endpoint** at `https://<host>:8443/mcp` and a small REST data API at `/v1/data/*`. With these, your own agents can read your data: Claude Code, Claude Desktop, Cursor, scripts. Each one gets its own scoped token, which you can revoke.
- **An access log** of every read of your data by the agent, an MCP client, the data API or the dashboard. The phone shows it too, under Me → Agent & sync.

Nothing leaves the server unless you set it up that way. If you choose the Claude API, the agent sends the tool results it needs for that one question to Anthropic. A local model keeps everything on your network.

Everything lives in one folder: `~/.orbit-server/`, or `$ORBIT_SERVER_HOME` if you set it. The folder is mode 0700 and each file in it is 0600.

| File | What it is |
|---|---|
| `orbit.db` (+ `-wal`, `-shm`) | SQLite database: samples, sleep, devices, token hashes, settings, access log |
| `tls.p12`, `tls.pass` | The self-signed TLS certificate and key. Phones pin its SHA-256 fingerprint. |
| `master.key` | 32-byte key that encrypts saved model API keys (AES-256-GCM) |

## Install

Orbit Server needs Java 17 or newer.

### From source (installDist)

```sh
./gradlew :server:installDist
# The runnable distribution is now in server/build/install/orbit-server/
cp -R server/build/install/orbit-server ~/orbit-server
~/orbit-server/bin/orbit-server --help
```

The examples below use `orbit-server` for `~/orbit-server/bin/orbit-server`.

### Docker

The `Dockerfile` in the repository root packages the `installDist` output. No Gradle runs inside Docker. The image:

- is based on `eclipse-temurin:17-jre`
- runs as the unprivileged user `orbit` (uid 10001)
- keeps its data in the volume `/data`, which is `ORBIT_SERVER_HOME`
- exposes port 8443 (self-signed HTTPS, the default) and 8080 (plain HTTP behind a reverse proxy)

```sh
./gradlew :server:installDist
docker build -t orbit-server .
```

For a VPS, prefer the Compose bundle with Caddy: see [VPS with a domain (recommended)](#vps-with-a-domain-recommended). The rest of this page describes the self-signed setup (a Mac, a LAN server, or a VPS reached over a VPN).

## VPS with a domain (recommended)

On a VPS, run Orbit Server behind [Caddy](https://caddyserver.com) with the Compose bundle in `deploy/`. Caddy gets a real Let's Encrypt certificate for your domain and renews it by itself, so browsers, MCP clients and curl trust the server with no extra setup. Phones pair against the public certificate and accept it only for your exact host name (trust mode "public certificate"); they never fall back to plain HTTP.

What runs:

- **caddy** publishes ports 80 and 443 and terminates TLS. It forwards to Orbit Server and sets `X-Forwarded-For` and `X-Forwarded-Proto` itself, dropping any such header a client sends.
- **orbit** runs `run --behind-proxy --public-url https://$ORBIT_DOMAIN --bind 0.0.0.0:8080 --trusted-proxy 172.31.250.2/32`. It serves plain HTTP on the private Compose network only (`expose`, never `ports`) and trusts forwarded headers only from Caddy's fixed address. Port 8080 is never published.
- Volumes: `orbit-data` (your data, `/data`), `caddy-data` (certificates and the ACME account) and `caddy-config`.

The Compose network uses the fixed subnet `172.31.250.0/24`. If that range is taken on your machine, change the subnet and both fixed addresses (Caddy's `ipv4_address` and the `--trusted-proxy` value) together.

### 1. DNS

Create an `A` record for your name, for example `orbit.example.com`, pointing at the VPS. Wait until `dig +short orbit.example.com` returns the VPS address: Caddy needs it to get the certificate.

**Create an `A` record only, no `AAAA`.** The bundled Compose network is IPv4-only. If the name also has an `AAAA` record, IPv6 clients still reach Caddy, but through Docker's userland proxy, so every IPv6 client appears as the network's gateway address. All IPv6 clients would then share one login, setup and pairing limit, and a stranger could use up that limit and lock you out of your own dashboard. See [IPv6 (advanced, untested)](#ipv6-advanced-untested) below.

### 2. Firewall

Allow only 80/tcp, 443/tcp and, if you want HTTP/3, 443/udp (plus your SSH port) from the internet. Port 80 is needed for the Let's Encrypt HTTP challenge and redirects to https. Never publish 8080: Orbit Server's plain-HTTP port must stay on the Compose network.

Docker-published ports bypass `ufw`, so the provider's firewall (security group) is the one that counts. The bundle publishes only Caddy's 80/tcp, 443/tcp and 443/udp. Without 443/udp in the firewall, clients simply stay on HTTP/2.

Rootless Docker is not supported: its port forwarding hides the real client address from Caddy, so every client shares one rate-limit bucket.

### 3. Build and start

On the VPS, with a checkout of this repository (or a copy of `deploy/`, `Dockerfile`, `.dockerignore` and `server/build/install/orbit-server/`):

```sh
./gradlew :server:installDist          # or build on another machine and copy server/build/install/orbit-server
cd deploy
cp .env.example .env                   # set ORBIT_DOMAIN and ACME_EMAIL
chmod 600 .env
docker compose up -d --build
```

The image only packages the `installDist` output; no Gradle runs inside Docker.

**Check that the server sees real client addresses.** Orbit Server keys its login, setup and pairing rate limits on the client address Caddy reports. Docker normally passes the real address through to Caddy, but this bundle has not been tested on a public VPS, so check once. Open the dashboard from your own computer, and while the page is open, run:

```sh
docker compose exec caddy netstat -tn | grep ':443 '
```

The remote column should show your computer's public address. If it shows only `172.31.250.1` (the Compose network's gateway) for every client, Docker is proxying the connections and the limits are shared by all clients. That is not unsafe, but per-client limits won't work. The usual cause is rootless Docker, or a Docker daemon running with its userland proxy for that port. If you add an `AAAA` record (see IPv6 below), test over both IPv4 and IPv6: run the command while a browser on an IPv4 connection and one on an IPv6 connection each have the dashboard open.

#### IPv6 (advanced, untested)

To serve IPv6 clients with their real addresses, the Compose network itself needs IPv6, so that Docker routes IPv6 to Caddy instead of proxying it. In outline (not tested with this bundle):

- Under `networks: orbit:` add `enable_ipv6: true` and a second ipam subnet from a ULA range you pick, for example `fd00:6f72:6269::/64`.
- Give Caddy a fixed `ipv6_address` in it, for example `fd00:6f72:6269::2`.
- Add that address as a `/128` to orbit's `--trusted-proxy`: `172.31.250.2/32,fd00:6f72:6269::2/128`.
- Only then add the `AAAA` record, and run the netstat check above over IPv6.

Depending on your Docker version, the daemon may also need IPv6 enabled (`"ipv6": true` and `"ip6tables": true` in `/etc/docker/daemon.json`).

### 4. Set the admin password

There is no default password. On its first start the server prints a one-time setup code:

```sh
docker compose logs --tail 30 orbit
```

Each start before setup prints a new banner and the older codes stop working, so use the most recent banner in the output.

Open `https://orbit.example.com/` (it redirects to `/setup`), enter the code and choose the admin password (10 to 72 bytes). The code works once and expires after 24 hours. For a new one, run `docker compose exec orbit /opt/orbit-server/bin/orbit-server setup-code`, or restart the container (`docker compose restart orbit`); each start before setup prints a fresh code and the old one stops working.

The code appears only in the container's standard output (`docker compose logs`), never in a log file.

### 5. Pair the phone

In the dashboard, open **Pair a phone** and scan the QR code from Orbit Health (Me → Agent & sync → Scan pairing code). The link carries `m=ca` and your https address, with no fingerprint. The app then shows **Secured · public certificate**.

From a shell: `docker compose exec orbit /opt/orbit-server/bin/orbit-server pair`.

### 6. MCP clients

Create a token under **Agent tokens**. With a public certificate, no `NODE_EXTRA_CA_CERTS` or `--cacert` is needed:

```sh
claude mcp add --transport http orbit https://orbit.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

### Backups (Compose)

```sh
cd deploy
docker compose stop orbit
mkdir -p ~/orbit-backups && cd ~/orbit-backups
(umask 077 && docker run --rm -v orbit_orbit-data:/data:ro eclipse-temurin:17-jre \
  tar -czf - -C /data . > orbit-server-backup-$(date +%F).tgz)
cd - && docker compose start orbit
```

Run these in `deploy/`, so that `docker compose` reads `.env` (and, on Tailscale, its `COMPOSE_FILE` line). The archive is written by your shell, not the container, so it belongs to you and is created mode 0600. The volume name is `<project>_orbit-data`; the bundle's project is `orbit`. See [Backups](#backups) for what to keep and how to restore.

### Upgrades

```sh
git pull && ./gradlew :server:installDist   # or copy the new server/build/install/orbit-server
cd deploy
docker compose up -d --build
# Caddy: the image is pinned to a minor version (caddy:2.11). To move on, edit the tag in
# docker-compose.yml, then:
docker compose pull caddy && docker compose up -d caddy
```

Data, certificates and pairings live in the volumes and survive upgrades. Take a backup first. Run the commands in `deploy/`; on the Tailscale variant, the `COMPOSE_FILE` line in `.env` keeps the override in effect (check with `docker compose ps` that the ports still show only the Tailscale address).

## Private server on Tailscale

If the server should be reachable only from your own devices, use Tailscale instead of a public domain. `deploy/docker-compose.tailscale.yml` is an override that:

- publishes 443 on the machine's Tailscale address only (`TAILSCALE_IP`), replacing the public 80/443 ports, so nothing is reachable from the LAN or the internet;
- mounts the host's `/var/run/tailscale/tailscaled.sock` into Caddy. For a `*.ts.net` name Caddy then fetches a real Let's Encrypt certificate from tailscaled by itself, with no ACME challenge and no public port.

**The socket gives Caddy control of the host's tailscaled.** The `:ro` on the mount does not restrict it: a read-only bind mount still lets the container connect to the socket, and Caddy runs as root, which tailscaled treats as an administrator. Anything that takes over the Caddy container could use tailscaled's local API as root does, for example to change `tailscale serve` or turn on Funnel (exposing services to the internet), or to read the tailnet's peer list. If you don't want that, use the certificate-file setup below instead of the socket.

Before you start:

1. In the Tailscale admin console, turn on MagicDNS and **HTTPS Certificates**.
2. Find the machine's name and address: `tailscale status --json | jq -r .Self.DNSName` (drop the trailing dot) and `tailscale ip -4`.

The override replaces the base file's ports with `ports: !override`, which needs Docker Compose 2.24.4 or newer (`docker compose version`). With an older Compose, don't rely on the override: check that `docker compose config` shows `host_ip: 100.x.y.z` on every published port before you run `up`.

Put all four lines in `deploy/.env`. The `COMPOSE_FILE` line is what makes every plain `docker compose` command in `deploy/` (up, logs, exec, the backup and upgrade commands above) use the override:

```sh
COMPOSE_FILE=docker-compose.yml:docker-compose.tailscale.yml
ORBIT_DOMAIN=myserver.tail1234.ts.net
ACME_EMAIL=you@example.com     # required by the Caddyfile, unused for ts.net certificates
TAILSCALE_IP=100.x.y.z
```

```sh
cd deploy
docker compose config | grep -A3 published   # check: every published port shows host_ip 100.x.y.z
docker compose up -d --build
docker compose ps                             # PORTS: 100.x.y.z:443->443 only
```

**Without the `COMPOSE_FILE` line, `docker compose up -d` uses the base file alone and publishes 80 and 443 on every address of the machine, which bypasses `ufw`.** Don't drop that line, and don't run the base file on a Tailscale-only machine. If you did, run `docker compose down` and start again with the line in place.

Then follow steps 4 to 6 above with `https://myserver.tail1234.ts.net/`. Phones and computers must be on your tailnet to reach it.

### After a reboot

Docker can only publish on `100.x.y.z` once tailscaled has given the machine that address. If Docker starts first, Caddy's container fails to start with "cannot assign requested address", and Docker may not retry it, because restart policies cover containers that exit, not ones that never started. `docker compose up -d` in `deploy/` brings it back. To avoid the race, choose one of these (both need root, once):

- Order Docker after Tailscale with a systemd drop-in:

  ```sh
  sudo systemctl edit docker.service
  # add:
  [Unit]
  Wants=tailscaled.service
  After=tailscaled.service
  ```

  `After=` only waits for tailscaled to start, not for it to be connected, so the address can still be late on a slow network. The second option avoids that.
- Let processes bind addresses the machine doesn't have yet: `echo 'net.ipv4.ip_nonlocal_bind = 1' | sudo tee /etc/sysctl.d/60-nonlocal-bind.conf && sudo sysctl --system`. This applies to every program on the host.

### Certificate files instead of the socket

If you don't want to hand Caddy the tailscaled socket, or tailscaled refuses Caddy's certificate requests (Caddy's log shows a tailscale certificate error):

1. Delete the `tailscaled.sock` line from `docker-compose.tailscale.yml`, and add `- ./certs:/certs:ro` under the caddy service's `volumes` there.
2. In `deploy/`, run `mkdir -p certs && sudo tailscale cert --cert-file certs/cert.pem --key-file certs/key.pem <name>`, then `sudo chown "$USER" certs/*`.
3. Add `tls /certs/cert.pem /certs/key.pem` to the site block in the Caddyfile, then run `docker compose up -d`.
4. The certificate lasts 90 days. Re-run step 2 before then (a monthly cron job works), followed by `docker compose restart caddy`.

### Rate limits on Tailscale

On the machine this was tested on, Caddy saw every tailnet client's connection come from the Compose network's gateway address (`172.31.250.1`). The host also ran Docker's userland proxy (`docker-proxy`) on the published `100.x.y.z:443`, which is the likely cause, but this was not confirmed. Orbit Server keys its login, setup and pairing limits on the address Caddy reports, so on such a host those limits apply to all your devices together rather than to each one. Anything on the host itself that connects to the container's address directly also arrives from that gateway address and shares the same bucket. That is harmless for one person and lets no one get around a limit: a spoofed `X-Forwarded-For` still changes nothing.

## Initialise: `init --host`

```sh
orbit-server init --host <your Mac's .local name or LAN IP> --name "Home Mac"
```

`init` asks for the admin password twice. For scripts, use `--admin-password-file FILE` instead; the password must be 10 to 72 bytes. `init` creates the data folder, the master key, the database and the TLS certificate, then prints the certificate fingerprint and the names the certificate covers.

**Why `--host` matters.** The server uses a self-signed certificate. Clients only accept it under a name listed inside the certificate, its Subject Alternative Names.

- **The phone** doesn't depend on the name, because it pins the exact certificate fingerprint from the pairing code.
- **MCP clients, curl and browsers** check that the certificate is valid for the host name in the URL, even after you trust the certificate. If you connect to `https://192.168.1.20:8443/mcp` and the certificate doesn't list `192.168.1.20`, the connection fails.

The certificate always covers `localhost`, `127.0.0.1`, `10.0.2.2` (the Android emulator's address for the host) and the machine's own host name. Add every name or IP that other devices will use to reach the server:

- **On a Mac:** the Bonjour name, which works on any LAN and survives DHCP changes. Find it with `scutil --get LocalHostName`, then add `.local`, for example `--host studio.local`. A fixed LAN IP works too.
- **On a VPS:** its DNS name, for example `--host orbit.example.com`.
- **Over Tailscale:** the tailnet name or the 100.x address.

You can repeat `--host`. To change the names later, run `orbit-server run --host <name> --regenerate-cert`. A new certificate has a new fingerprint, so every phone must pair again and every MCP client must trust the new certificate.

If a regenerate is interrupted (killed, power loss) after it wrote the new `tls.pass` but before the new `tls.p12`, the next start fails because the old keystore no longer opens with the new password. Recover by running the same command again with `--regenerate-cert`: it writes a fresh pair of files. Phones must pair again, exactly as after any regenerate.

In Docker:

```sh
docker run -it --rm -v orbit-data:/data orbit-server init --host orbit.example.com --name "Orbit VPS"
```

## Run

```sh
orbit-server run            # HTTPS on 0.0.0.0:8443
orbit-server run --port 9443 --bind 192.168.1.20
```

Orbit Server only serves HTTPS. `--insecure-dev` serves plain HTTP on 127.0.0.1 and exists only for automated tests.

`init` is optional. Without it, the first `run` creates the data folder, the master key, the database and the TLS certificate itself (add `--host` for the names, as for `init`), and because no admin password exists yet it prints a one-time setup code. Open `https://<host>:8443/setup`, enter the code and choose the admin password. The code works once and expires after 24 hours; `orbit-server setup-code` (or restarting the server) issues a new one, and the old one stops working.

When the server starts, it prints the fingerprint and the dashboard address. Open the dashboard and sign in with the admin password, the one you gave `init` or chose on the setup page. Your browser will warn about the self-signed certificate. Check that the fingerprint the browser shows matches the one `run` (or `init`) printed, then continue. Alternatively, open `/cert.pem` in the dashboard and add it to your keychain.

On a Mac, the first `run` may trigger the firewall prompt "Do you want the application java to accept incoming network connections?". Allow it, or phones on the LAN can't connect.

### Keep it running on a Mac (launchd)

Save this as `~/Library/LaunchAgents/dev.avijit.orbit-server.plist`. Replace `YOU` with your user name. Replace `PATH-FROM-java_home` with the path `/usr/libexec/java_home -v 17` (or your installed version) prints, because launchd doesn't load your shell's `PATH` or `JAVA_HOME`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>dev.avijit.orbit-server</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/YOU/orbit-server/bin/orbit-server</string>
    <string>run</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>JAVA_HOME</key>
    <string>PATH-FROM-java_home</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>/Users/YOU/Library/Logs/orbit-server.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/YOU/Library/Logs/orbit-server.log</string>
</dict>
</plist>
```

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.avijit.orbit-server.plist
launchctl kickstart -k gui/$(id -u)/dev.avijit.orbit-server   # restart after an upgrade
launchctl bootout gui/$(id -u)/dev.avijit.orbit-server        # stop and unload
```

A LaunchAgent runs while you are logged in. The phone syncs whenever it can reach the server and catches up after gaps. To have the Mac answer at night, turn on System Settings → Displays → Advanced → "Prevent automatic sleeping on power adapter when the display is off" (on a laptop, under Battery → Options).

Logs never contain health values or tokens.

### Keep it running on a VPS (Docker + systemd)

```sh
docker run -it --rm -v orbit-data:/data orbit-server init --host orbit.example.com
```

Then save this as `/etc/systemd/system/orbit-server.service`:

```ini
[Unit]
Description=Orbit Server
After=docker.service network-online.target
Requires=docker.service

[Service]
ExecStartPre=-/usr/bin/docker rm -f orbit-server
ExecStart=/usr/bin/docker run --rm --name orbit-server -p 127.0.0.1:8443:8443 -v orbit-data:/data orbit-server
ExecStop=/usr/bin/docker stop orbit-server
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now orbit-server
journalctl -u orbit-server -f
```

On a VPS:

- **Docker bypasses `ufw`.** A port published with `-p 8443:8443` is reachable from the internet even when `ufw` denies it, because Docker writes its own iptables rules. The unit above publishes on `127.0.0.1` only; reach it through Tailscale/WireGuard (`tailscale serve`, or publish on the tunnel address, e.g. `-p 100.x.y.z:8443:8443`) or an SSH tunnel (`ssh -L 8443:127.0.0.1:8443 vps`). Use `-p 8443:8443` only if you really want the server on the public internet, and then restrict it with the provider's firewall rather than `ufw`.
- Open only port 8443 in the firewall (with the Caddy bundle instead: only 80 and 443, and never 8080).
- Use a provider with full-disk encryption, or put the volume on an encrypted disk.
- Prefer reaching the server through Tailscale or WireGuard over exposing it to the whole internet.

Running the other commands inside the container works the same way: `docker exec -it orbit-server /opt/orbit-server/bin/orbit-server pair --host orbit.example.com`.

## Pair a phone

Pair in either of these ways:

- **Dashboard:** open **Pair a phone**. It shows a one-time QR code, the link, the server address and the fingerprint. The code is valid for 10 minutes and can be used once.
- **Terminal:** run `orbit-server pair --host <the same name you passed to init>`. It prints the QR code and the `orbit://pair?...` link.

Then, on the phone:

1. Open Orbit Health → Me → **Agent & sync**.
2. Choose what to share: heart, sleep, activity, stress, SpO₂, ring & profile. You can change this later, and the server enforces it.
3. Tap **Scan pairing code** and scan the QR code.

The app pins the certificate fingerprint from the code, so it talks only to *this* server and never trusts other certificates. After pairing, it pushes your data after each ring sync. **Sync now** pushes at once.

The terminal's QR code says the same: "Me → Agent & sync → Scan pairing code".

**One person, one phone.** The server keeps a single "newest stored time" per metric, shared by everything that pushes to it, and each phone sends only data newer than that. It is built for one person syncing from one phone. If a second phone pushes to the same server, or the ring's clock ran ahead (the server accepts timestamps up to a day in the future), the mark moves past readings this phone has not sent yet, and those readings are not pushed (only activity totals from the last 24 hours are re-sent). The phone keeps its own copy either way. Use one server per person and one phone per server.

If the server's certificate changes, for example after `--regenerate-cert`, a restore onto a new machine without `tls.p12`, or someone in the middle, the app stops and shows **"Server identity changed — re-pair"**. It sends nothing until you scan a new code. After re-pairing, remove the old entry under **Devices** in the dashboard, because the app can't revoke it through the changed identity.

## Choose a model

Open **Agent** in the dashboard:

- **Claude API:** pick the `anthropic` provider and paste an API key from console.anthropic.com. The default model is `claude-sonnet-5-5`. The key is stored encrypted with `master.key` and is never shown again; the field reads "•••• saved key".
- **Local model, Ollama or LM Studio:** pick `openai_compat` and set the base URL:
  - **Ollama:** `http://localhost:11434/v1`. Pull a tool-capable model first, for example `ollama pull qwen3`, and enter its name.
  - **LM Studio:** `http://localhost:1234/v1`. Start the local server in LM Studio and load a model.
  - **From the Docker image:** use `http://host.docker.internal:11434/v1`. On Linux, also add `--add-host=host.docker.internal:host-gateway` to `docker run`.
  - A key is optional; it is sent only if you set one.
  - Plain `http://` is accepted only for this machine (`localhost`, `127.0.0.1`, `::1`), private network addresses (`10.x`, `172.16–31.x`, `192.168.x`, IPv6 `fc00::/7`) and `.local` names. Any other host needs `https://`, because the key and your health data would otherwise cross the network unencrypted. Names are taken as written, not resolved.

**Test** asks "What was my resting heart rate yesterday?" and shows the answer, the tools it used and the model. It reads only what your paired (not revoked) phones share with the server, the same data a phone's question could reach. Changing the provider or host removes the saved key, so a key never travels to a host it wasn't entered for.

## Connect Claude Code (or any MCP client)

Create a token in the dashboard (**Agent tokens**) or on the command line. Give it only the scopes it needs:

```sh
orbit-server token create --name claude-code --scopes heart,sleep,activity --host studio.local
orbit-server cert export ~/orbit-server.pem
```

The token is shown once. Only its SHA-256 hash is stored.

Claude Code is a Node program. It must trust the self-signed certificate through `NODE_EXTRA_CA_CERTS`, and the URL must use a name the certificate covers (see `init --host`):

```sh
claude mcp add --transport http orbit https://studio.local:8443/mcp \
  --header "Authorization: Bearer <token>"
NODE_EXTRA_CA_CERTS=~/orbit-server.pem claude
```

The token on that command line lands in your shell history, and `claude mcp add` stores it in plain text in `~/.claude.json`. Treat both like the token itself: start the command with a space if your shell ignores such lines (`HISTCONTROL=ignorespace`, or zsh's `setopt HIST_IGNORE_SPACE`), or remove the line from history afterwards, and keep `~/.claude.json` private. Revoke the token if either file leaks.

Then ask Claude Code, for example, "Using orbit, how did I sleep this week?". Each tool call appears in the access log under the token's name.

Other clients use the same URL and header:

```json
{
  "mcpServers": {
    "orbit": {
      "type": "http",
      "url": "https://studio.local:8443/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

How the endpoint behaves:

- It is MCP Streamable HTTP with JSON responses, and it is stateless.
- It supports protocol versions 2024-11-05 to 2025-11-25.
- There are five read-only tools: `list_metrics`, `get_metric`, `get_sleep`, `daily_summary` and `compare_to_baseline`.
- Each token is limited to 120 requests per minute.

For scripts, the REST data API takes the same tokens:

```sh
curl --cacert ~/orbit-server.pem -H "Authorization: Bearer <token>" \
  "https://studio.local:8443/v1/data/samples?metric=HEART_RATE&from=today&agg=hourly"
```

Revoke a token with `orbit-server token revoke <id>`, or in the dashboard. Revocation takes effect on the next request.

## Backups

Everything is in the data folder (`~/.orbit-server`, or the `orbit-data` volume in Docker).

```sh
# Stop the server first (launchctl bootout … / systemctl stop orbit-server), then:
(umask 077 && tar -czf orbit-server-backup-$(date +%F).tgz -C ~ .orbit-server)
# Docker volume (stop the container first; the Compose bundle's volume is orbit_orbit-data):
(umask 077 && docker run --rm -v orbit-data:/data:ro eclipse-temurin:17-jre \
  tar -czf - -C /data . > orbit-server-backup-$(date +%F).tgz)
```

`umask 077` makes the archive readable only by you from the moment it is created. In the Docker form the container only reads the volume (as root, because the data is owned by the image's uid 10001 with mode 0700) and streams the archive to your shell, which writes the file. So the file belongs to you, not root, and needs no `sudo` to move or delete.

To restore into an empty volume (the server stopped), extract as root so the files keep their uid 10001 owner:

```sh
docker run --rm -i -v orbit-data:/data eclipse-temurin:17-jre tar -xzf - -C /data < orbit-server-backup-YYYY-MM-DD.tgz
```

- Keep `tls.p12` and `tls.pass` if you want paired phones to keep working after a restore. Without them, a new certificate is made and every phone must pair again.
- Keep `master.key` with `orbit.db`. Without it, the saved model API key can't be decrypted, and you will need to enter it again.
- The backup holds your health data and the keys above. Encrypt it, for example with an encrypted disk image or `age`, and store it as carefully as the server itself.
- The phone always keeps its own copy, so a lost server never loses your data. For a file you can read, the dashboard's **Export** gives a JSON download.

## Security notes

- **The server is a trusted endpoint.** Anyone with shell access to the machine, or with the admin password, can read your data. Use a machine you control, a strong admin password, and an OS account only you use.
- **Encrypt the disk.** Turn on FileVault on a Mac, LUKS or the provider's disk encryption on a VPS. The database itself is not encrypted at rest; it relies on the 0700/0600 permissions and the disk.
- **Network:**
  - The phone pins the certificate.
  - MCP clients and browsers trust it explicitly.
  - Plain HTTP is never served.
  - Each phone may push 60 times and ask 20 questions a minute (then 429). A question gets at most 150 s for all its model calls; past that the phone shows the model error and the access log records `outcome=timeout`.
  - Keep the port off the open internet when you can, and use the LAN or a VPN such as Tailscale.
  - Behind Caddy (`--behind-proxy`), the server speaks plain HTTP only to the proxy. Keep its port unpublished, and name only the proxy in `--trusted-proxy`: forwarded headers from any other address are ignored, and dashboard forms are refused unless the trusted proxy reports https.
- **Tokens:**
  - Device and agent tokens are 256-bit random values, and only their SHA-256 hashes are stored.
  - Pairing codes are single use and expire after 10 minutes.
  - Give each agent its own token with the smallest set of scopes, and revoke tokens you no longer use (`orbit-server token revoke`, or the dashboard).
  - Remove a lost phone under **Devices**.
- **Model choice is a privacy choice.** With the Claude API, the tool results for each question go to Anthropic. With Ollama or LM Studio they stay on your machine.
- **Check the access log.** It lists who read what and how many rows, but never the values. Revoked or out-of-scope attempts are logged as `DENIED`.
- **Logs** contain no health values and no tokens.
