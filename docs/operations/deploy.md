# Production deploy: qkt-prod

The runbook for the managed `qkt-prod` deployment. If you're standing up a fresh
docker-compose host instead of using the existing prod box, start with
[Deploy with Docker](deploy-docker.md) — this page is specific to the
Dokploy/Swarm host that runs the live `qkt` instance.

## Stack overview

| Piece | Where |
| --- | --- |
| Host | `173.249.58.247` (SSH as `root`) |
| Orchestrator | [Dokploy](https://dokploy.com) on Docker Swarm |
| qkt image | `ghcr.io/elitekaycy/qkt:<tag>` (built by `.github/workflows/release.yml` on tag push) |
| MT5 gateway image | `elitekaycy/mt5-gateway-api@sha256:...` (pinned by digest in `compose.yml`) |
| Compose source | [`elitekaycy/qkt-prod`](https://github.com/elitekaycy/qkt-prod) repo, cloned at `/etc/dokploy/compose/qkt-prod-sqvjvo/code/` |
| qkt state | `./state/` bind mount → `/var/lib/qkt` inside container |
| qkt logs | `./logs/` bind mount → `/var/lib/qkt/logs` inside container |
| Dokploy state | `dokploy-postgres` container (project env vars, deploy history) |

`QKT_IMAGE_TAG` is the **single source of truth for what's deployed**. It is set
in Dokploy's per-project env (UI) and written into `/etc/dokploy/compose/.../code/.env`
on each deploy. The compose file requires it to be set (`${QKT_IMAGE_TAG:?...}`);
there is no fallback — a missing env fails the deploy loud.

## "What's deployed right now?"

Three ways, in order of preference:

```bash
# 1. Ask the running binary. This is the version-drift fix's whole point:
ssh root@173.249.58.247 'docker exec qkt qkt --version'
# qkt 0.28.9 (5ada43f) built 2026-05-22T11:59:00Z

# 2. Read the running image tag:
ssh root@173.249.58.247 "docker inspect qkt --format '{{.Config.Image}}'"
# ghcr.io/elitekaycy/qkt:v0.28.9

# 3. Read the Dokploy-managed env (canonical, but requires SSH):
ssh root@173.249.58.247 'grep QKT_IMAGE_TAG /etc/dokploy/compose/qkt-prod-sqvjvo/code/.env'
```

(1) is the operator default. The version string maps directly to a git SHA, so
`git show 5ada43f` tells you exactly what's running.

## Releasing a new version

Tagging triggers the image build; bumping `QKT_IMAGE_TAG` in Dokploy triggers
the redeploy. The `qkt-prod` repo no longer needs a "bump" commit per release —
the image tag lives in Dokploy's env, not in `compose.yml`.

1. **Tag the release** on the `qkt` repo:

    ```bash
    git checkout main && git pull
    # Update VERSION file to the new version, commit, push, then:
    git tag v0.28.10 && git push origin v0.28.10
    ```

    The `release.yml` workflow builds and pushes `ghcr.io/elitekaycy/qkt:v0.28.10`.
    Wait for the workflow to finish before continuing.

2. **Bump `QKT_IMAGE_TAG` in Dokploy:**

    - Dokploy UI → `qkt-prod` project → Environment → set `QKT_IMAGE_TAG=v0.28.10`.
    - Click "Deploy" (or use the API).

3. **Verify:**

    ```bash
    ssh root@173.249.58.247 'docker exec qkt qkt --version'
    # Should report v0.28.10 with the corresponding SHA.
    ```

## Rolling back

Same path, in reverse. Set `QKT_IMAGE_TAG` back to the prior version and redeploy:

```text
Dokploy UI → qkt-prod → Environment → QKT_IMAGE_TAG=v0.28.9 → Deploy
```

The image is already in GHCR (releases are immutable), so the redeploy is just
a container restart against the existing image. ETA: ~30s.

If you don't know the prior version, check the deploy history in the Dokploy
UI, or the image label of any saved container:

```bash
docker images ghcr.io/elitekaycy/qkt --format '{{.Tag}} {{.CreatedAt}}'
```

## Tailing logs

```bash
# qkt daemon stdout (per-strategy events, control plane errors):
ssh root@173.249.58.247 'docker logs -f --tail 200 qkt'

# Per-strategy file logs (logback-rotated, mounted at ./logs/):
ssh root@173.249.58.247 'tail -F /etc/dokploy/compose/qkt-prod-sqvjvo/code/logs/<strategy>.log'

# MT5 gateway:
ssh root@173.249.58.247 'docker logs -f --tail 200 qkt-mt5'
```

The container `json-file` driver is capped at 10 MB × 5 files per service, so
docker-level logs are bounded. The per-strategy logback appenders rotate
independently (50 MB / 7-day) and live on the bind mount, so they survive
container rebuilds.

## State recovery

The `./state/` bind mount under the compose directory holds the daemon's
control-port file, strategy state snapshots, and trade history. The
[State backup](state-backup.md) runbook covers cadence and restore.

If you nuke the container but keep `./state/`, the daemon restarts in the same
state. If you lose `./state/` you lose the trade-history ring buffer and any
pending in-flight orders — see the backup runbook for the restore path.

## MT5 broker login

The bundled MT5 gateway (`qkt-mt5` container) runs MetaTrader 5 in a VNC
desktop. If MT5 logs out (broker session timeout, credential change), the
daemon's `qkt brokers list` will show `gateway: down`.

```bash
# SSH tunnel the VNC port locally:
ssh -L 3020:127.0.0.1:3020 root@173.249.58.247
# Then open http://127.0.0.1:3020 in a local browser, log in to MT5.
```

VNC password lives in `MT5_VNC_PASSWORD` in Dokploy env.

## Required: external deadman watchdog

The daemon cannot report its own death — a host reboot or OOM on a Friday evening
goes unnoticed until someone looks, with open leveraged positions protected only
by venue-side stops. Run `scripts/deadman-watchdog.sh` from a SECOND machine via
cron (every minute), pointed at the daemon's `/health` through an SSH tunnel or
private network:

```cron
* * * * * QKT_HEALTH_URL=http://127.0.0.1:8200/health TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... /opt/qkt/deadman-watchdog.sh
```

It pages once per outage (daemon down, or any running strategy silent past 15
minutes — the wedged-session signature) and once on recovery. A hosted dead-man
service (healthchecks.io) works as an alternative; the point is that the checker
does not share the trading host's fate.

## Trust boundary and host clock

Two assumptions the deployment relies on — written down so a future change can't
silently break them:

- **Control plane and gateway are loopback-only and unauthenticated.** The daemon's
  control HTTP (deploy/halt/kill/flatten) binds `127.0.0.1`, and the mt5-gateway HTTP
  has no auth of its own. That is safe exactly as long as nothing rebinds them to a
  routable interface or port-forwards them. Anyone who can reach those ports can
  submit orders. If remote control is ever needed, put an authenticated reverse proxy
  in front — do not just change the bind address.
- **The host clock must be NTP-disciplined** (`chrony` on the prod host). `SCHEDULE`
  firing and the daily-PnL UTC-midnight rollover use host time; candle boundaries use
  broker tick time and are immune. A drifting clock fires schedules at the wrong time
  and rolls daily loss budgets early or late.

## Disaster: full host loss

1. Provision a new host (any Docker Swarm / Compose-capable Linux).
2. Install Dokploy from the [official install script](https://docs.dokploy.com).
3. Restore Dokploy's `dokploy-postgres` from backup → all project env vars
   (including `QKT_IMAGE_TAG`) come back.
4. Reconnect the `qkt-prod` GitHub repo to Dokploy → it re-clones the compose
   source.
5. Restore the `state/` bind mount from `state-backup`.
6. Trigger deploy → container starts on the same image tag as before.
7. Log back in to MT5 via VNC (broker session is stored in `mt5_config` named
   volume — if that's lost, full re-login is required).

The container itself is stateless; everything that matters is in `./state/` and
Dokploy's postgres.

## See also

- [Deploy with Docker](deploy-docker.md) — generic compose deployment, not
  Dokploy-specific.
- [State backup](state-backup.md) — backup/restore cadence for `./state/`.
- [Monitoring](monitoring.md) — what to scrape, what to page on.
- [Troubleshooting](troubleshooting.md) — symptom → cause → fix.
