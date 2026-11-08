# Security policy

Orbit Server holds your health data, your devices' tokens and your AI and voice provider keys, so security reports matter a lot to us.

## Reporting a vulnerability
Please report privately through GitHub: **Security → Report a vulnerability** on this repository. Don't open a public issue for security problems.

Include what you found, how to reproduce it, and the server version. We'll acknowledge your report and keep you updated while we fix it.

## Design notes
- No default password: the first admin password is set with a one-time setup code printed on the server's console, and only its bcrypt hash is stored.
- Provider API keys (AI model, voices) are encrypted at rest with the server's master key and never shown back in full.
- Devices pair with one-time links that expire after 10 minutes; each device gets its own token, which you can revoke from the dashboard.
- The dashboard uses same-origin and CSRF checks; logins, pairing and the device APIs are rate limited.
- Every read of your data by the agent, an MCP client, the data API or the dashboard is written to the access log (what was read, never the values).
- Nothing leaves the server except the requests you set up: to the AI and voice providers you configure, with your own keys.
- Recommended deployment: behind Caddy, reachable only on your Tailscale network (see `deploy/docker-compose.tailscale.yml`).
