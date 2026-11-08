# Orbit Server container image.
#
# Build the distribution first (the image only packages it, so no Gradle runs inside Docker):
#   ./gradlew :server:installDist
#   docker build -t orbit-server .
#
# Behind Caddy with a public certificate (recommended for a VPS): use deploy/docker-compose.yml,
# which runs `run --behind-proxy --public-url https://<domain> --bind 0.0.0.0:8080 --trusted-proxy <caddy>`.
#
# Self-signed HTTPS on 8443 (the default CMD; phones pin the certificate fingerprint):
#   docker run -d --name orbit -p 127.0.0.1:8443:8443 -v orbit-data:/data --restart unless-stopped \
#     orbit-server run --host orbit.example.com
#   docker logs orbit     # first start prints a one-time setup code; open https://<host>:8443/setup
# This publishes on 127.0.0.1 only: reach it through a VPN or tunnel (Tailscale, WireGuard,
# ssh -L; see README.md), or publish on the tunnel's address.
# --host is the name or IP phones and MCP clients use (it goes into the TLS certificate).
# There is no default password. For scripted installs, `init --admin-password-file F --host H`
# still sets the password up front instead of the setup code.
#
# See README.md for pairing, MCP clients, backups and security notes.
FROM eclipse-temurin:17-jre

# Unprivileged user; the data directory is the only writable place it needs.
RUN groupadd --system --gid 10001 orbit \
 && useradd --system --uid 10001 --gid orbit --home-dir /data --shell /usr/sbin/nologin orbit \
 && mkdir -p /data \
 && chown orbit:orbit /data \
 && chmod 0700 /data

COPY --chown=root:root server/build/install/orbit-server /opt/orbit-server

ENV ORBIT_SERVER_HOME=/data
VOLUME ["/data"]
# 8443: self-signed HTTPS (default). 8080: plain HTTP behind a reverse proxy (--behind-proxy).
EXPOSE 8443 8080

USER orbit:orbit
WORKDIR /data
ENTRYPOINT ["/opt/orbit-server/bin/orbit-server"]
CMD ["run"]
