# Deploy with Docker

The repo ships a [`docker-compose.yml`](https://github.com/elitekaycy/qkt/blob/main/docker-compose.yml) at the root that runs `qkt` + `mt5-gateway` together. This is the recommended production deployment for live MT5 trading.

## Prerequisites

- Docker + Docker Compose v2
- An `mt5-gateway:latest` image — build from [github.com/elitekaycy/mt5-gateway](https://github.com/elitekaycy/mt5-gateway) or pull from your private registry
- `.env` populated with broker credentials (copy from `.env.example`)
- `qkt.config.yaml` with at least one broker profile (copy from `qkt.config.yaml.example`)

## Stack layout

```bash
docker compose up -d
   │
   ├─ mt5-gateway   (Wine + MT5 + Flask)
   │     • port 3000 → VNC, log in to MT5 GUI on first start
   │     • port 5001 → HTTP API for qkt to talk to
   │
   └─ qkt           (daemon)
         • depends_on: mt5-gateway healthy
         • mounts: ./strategies:/strategies, ./qkt.config.yaml:/etc/qkt/qkt.config.yaml
         • port 47000-47100 → per-strategy observability
```

## First run

1. **Bring up the stack:**

    ```bash
    docker compose up -d
    ```

2. **Log in to MT5 once:** connect to `localhost:3000` with a VNC client, use `VNC_PASSWORD` from `.env`, log in to your broker through the MT5 GUI. The healthcheck will go green within a minute.

3. **Verify:**

    ```bash
    curl http://localhost:5001/health
    docker compose exec qkt qkt brokers list
    ```

4. **Deploy a strategy:** drop a `.qkt` file into `./strategies/` — the daemon's `--load-dir /strategies` auto-deploys it on next restart. For hot-deploy:

    ```bash
    docker compose exec qkt qkt deploy /strategies/momentum.qkt --as momentum
    ```

## Persistence

The compose file declares a named volume `qkt-state` mounted at `/var/lib/qkt`. Logs land at `qkt-state:/logs/<name>.log`; the control port + state files live there too.

To inspect logs from outside the container:

```bash
docker run --rm -v qkt-state:/data alpine cat /data/logs/momentum.log
```

## Healthchecks + restart

`qkt` declares `restart: unless-stopped` and `depends_on: mt5-gateway: condition: service_healthy`. If the gateway dies, qkt waits for its healthcheck to recover before restarting.

## Tearing down

```bash
docker compose down            # keeps qkt-state volume
docker compose down -v         # also wipes the volume (state + logs lost)
```

## Common issues

- **`qkt brokers list` shows the profile but `gateway: down`.** MT5 isn't logged in. VNC at `:3000` and log in.
- **Compose can't pull `mt5-gateway:latest`.** The image isn't on Docker Hub. Build it locally or use a private registry.
- **Symbol not found errors.** Verify the broker's actual symbol via the MT5 market watch. The default `exness` profile assumes the `m` suffix; other brokers vary.
- **Container loses ports on restart.** Add `restart: unless-stopped` to both services (already in the shipped compose file).

## See also

- [Get started: deploy MT5](../get-started/deploy-mt5.md) — first-time walkthrough
- [Reference: config schema](../reference/config-schema.md) — `qkt.config.yaml` fields
- [Existing example](https://github.com/elitekaycy/qkt/tree/main/examples/docker) — variant deployments
