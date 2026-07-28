---
name: hasan-bridge-diagnosis
description: >
  Diagnostic knowledge for the Hasan Android bridge deployment — relay
  server, hasan_delivery plugin, hermes-webui, and the Caddy reverse proxy
  that fronts them. Explains the four-port topology and how to check each
  component's health. Read-only diagnosis only — never restarts services,
  edits configs, or regenerates pairing codes itself.
category: general
---

# Hasan Bridge Diagnosis

Use this skill when a user reports the Hasan Android app can't connect
(chat, phone bridge/pairing, or the relay itself), or asks how the Hasan
server-side deployment is wired together.

## 1. Architecture overview — four ports

| Port | Service | Runs as | Public exposure |
|---|---|---|---|
| 8767 | relay server (Android WebSocket bridge) | Docker container (`network_mode: host`) | via Caddy `:443` (never directly) |
| 8787 | hermes-webui (chat backend) | native venv + `systemctl --user hermes-*` | via Caddy `:443` |
| 9119 | Hermes internal dashboard | native venv + `systemctl --user hermes-dashboard.service` | via Caddy `:8443`, opt-in, off by default |
| 443 / 8443 | Caddy (TLS termination) | Docker container (`network_mode: host`) | public |

The relay and Caddy are the only two containerized pieces of this
deployment, installed by `server/install-bridge.sh` (Docker Compose,
`network_mode: host` — no bridge network, no NAT, containers reach
`127.0.0.1`-bound peers exactly like native processes). Hermes itself
(gateway, webui, dashboard) stays native, unchanged by this deployment —
same venv + systemd as any other Hermes install. The `hasan_delivery`
plugin also stays native: it runs inside the existing Hermes gateway
process's venv, not a standalone service, so it was never a
containerization candidate.

Config file locations:
- `server/.env` (secrets: `RELAY_ADMIN_TOKEN`, `WEBUI_URL`/`WEBUI_PASSWORD`) — never `cat` this directly, it holds a live secret.
- `server/Caddyfile` (rendered from `server/Caddyfile.template` by `install-bridge.sh` — first line has a `# managed-by: hasan-bridge docker compose` marker; if that marker is missing, this file was NOT written by that script).
- `server/docker-compose.yml` — the compose manifest itself, versioned in git, safe to read.
- `${HERMES_HOME}/plugins/hasan_delivery/` — plugin code, installed by `plugin/hasan_delivery/install-plugin.sh`.

## 2. The dual-Caddyfile / dual-listener trap

On 2026-07-28, hours were lost to two Caddy configs disagreeing about
where to route `:443` — one loaded by a manually-started `nohup caddy run`
process, another by the systemd `caddy.service` package unit, each
pointing at a different backend port. The symptom was misleading: repeated
`401 Unauthorized` on login that looked like a credentials problem but was
actually requests landing on the wrong backend entirely.

A separate, pre-existing skill (`hermes-web-ui-remote-access`) documents
port 9119 as the Hermes dashboard's backend and recommends
`~/.hermes/Caddyfile` as canonical **for that skill's purpose** (dashboard
access). That is correct for its own use case but is NOT the config this
deployment uses — the Hasan bridge's canonical Caddy config is
`server/Caddyfile` (Docker-managed, see above). If both skills seem to
disagree about "the right Caddyfile," it's because they're describing two
different services that happen to share the same reverse-proxy tool, not
because either is wrong.

`network_mode: host` reintroduces this same ambiguity class in a new
form: a containerized Caddy and a native Caddy (or any other native
process) can both try to bind the same host port, since host networking
means the container has zero isolation from the host's port namespace.
`install-bridge.sh` checks this before starting containers (`ss -ltnp` on
its target ports) and refuses to proceed if occupied — but a process
started *after* the containers are already running could still collide
silently. If something looks wrong, always check for a second listener
first, before assuming a config or credentials problem.

## 3. Common diagnostic commands (read-only)

```bash
# Container state — both services at once
docker compose -p hasan-bridge -f server/docker-compose.yml ps

# Logs
docker compose -p hasan-bridge -f server/docker-compose.yml logs -f relay
docker compose -p hasan-bridge -f server/docker-compose.yml logs -f caddy

# Confirm network_mode: host actually holds (some Docker daemon configs
# silently ignore/override this)
docker inspect hasan-bridge-relay-1 --format '{{.HostConfig.NetworkMode}}'
docker inspect hasan-bridge-caddy-1 --format '{{.HostConfig.NetworkMode}}'

# Resolved compose config with variables substituted — confirms
# RELAY_ADMIN_TOKEN/WEBUI_URL reached the container without ever
# printing the raw .env file
docker compose -p hasan-bridge -f server/docker-compose.yml config

# Health checks
curl -sf http://127.0.0.1:8767/health          # relay, direct
curl -sfk https://<public-host>/                # via Caddy, chat/webui path
curl -sfk https://<public-host>:8443/           # via Caddy, dashboard path (if exposed)

# Which listener actually owns a contested port
ss -ltnp 'sport = :443'

# Confirm which Caddyfile is loaded and whether it's the one this
# deployment owns
head -n1 server/Caddyfile   # should start with "# managed-by: hasan-bridge docker compose"

# Hermes-side plugin connectivity (unrelated to Docker — Hermes stays native)
journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery
```

## 4. fail2ban awareness

A `hermes-webui` jail watches the Caddy access log for repeated failed
logins (`maxretry=5`, `bantime=3600`). Running repeated diagnostic curl
calls against `/api/auth/login` from the same source IP during
troubleshooting can self-inflict a ban. If a previously-working IP
suddenly gets connection-refused/reset, check:

```bash
fail2ban-client status hermes-webui
```

before assuming a routing or credentials problem.

## Non-goals

This skill is diagnostic only. It never restarts or recreates containers
(`docker compose down`/`restart`/`up --force-recreate`, `docker rm`),
never edits `server/Caddyfile`/`server/.env` by hand, and never
regenerates pairing or session tokens. For a fix, re-run
`server/install-bridge.sh` (idempotent, safe to re-run) or hand off to a
human operator.
