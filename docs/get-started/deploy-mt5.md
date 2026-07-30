# Deploy MT5

Live trading routes orders through a per-broker `mt5-gateway` HTTP service that runs MT5 in Wine inside Docker. Phase 17 shipped the broker; Phase 18 wired the dispatch into `LiveSession`; Phase 20 packaged the full stack.

## Prerequisites

- Docker + Docker Compose
- An MT5 broker account (Exness, ICMarkets, FTMO, Pepperstone, etc.)
- Server name from your broker (e.g. `Exness-MT5Real8`)

## 1. Spin up the stack

```bash
cp .env.example .env
# Edit .env: MT5 credentials, API key, broker server clock, and symbol suffix

cp qkt.config.yaml.example qkt.config.yaml

docker compose up -d
```

The compose file ([`docker-compose.yml`](https://github.com/elitekaycy/qkt/blob/main/docker-compose.yml)) starts:

- `mt5-gateway` on ports 3000 (VNC) + 5001 (HTTP API)
- `qkt` daemon, depends-on healthy gateway

## 2. Verify the headless login

The gateway resolves the broker server and logs in using the three `MT5_*` values:

```bash
curl -H "Authorization: Bearer $MT5_API_KEY" http://localhost:5001/health/ready
```

A 200 response with `"status":"ready"` confirms the API, terminal, and account. Use VNC on `localhost:3000` only to diagnose an automatic-login failure.

## 3. Configure the broker profile

The default stack uses a neutral profile and requires broker-specific behavior
to be explicit:

```yaml title="qkt.config.yaml"
brokers:
  mt5:
    type: mt5
    gateway_url: ${QKT_BROKER_GATEWAY_URL:-http://mt5-gateway:5001}
    api_key: ${QKT_BROKER_API_KEY}
    server_time_zone: ${QKT_BROKER_SERVER_TIME_ZONE}
    symbol_suffix: ${QKT_BROKER_SYMBOL_SUFFIX:-}
    magic: ${QKT_BROKER_MAGIC:-10001}
```

The `gateway_url` matches the Docker service name. On a non-Docker setup, use `http://localhost:5001`.
Keep the gateway's `MT5_SERVER_UTC_OFFSET_SECONDS=0`; qkt performs the single
broker-wall-to-UTC conversion for both historical bars and live ticks. Set
`server_time_zone` to `UTC`, `new_york_close`, an IANA zone id, or a fixed
offset such as `+02:00`.

For multi-account, distinct profiles via `extends:` — see the [configure-mt5-broker](../how-to/index.md) recipe.

## 4. Verify what's loaded

```bash
docker compose exec qkt qkt brokers list
```

Should show your `mt5` profile with the resolved gateway URL.

## 5. Write a live strategy

```qkt title="strategies/live_eur.qkt"
STRATEGY live_eur VERSION 1

SYMBOLS
    eur = MT5:EURUSD EVERY 1m

RULES
    WHEN ema(eur.close, 9) CROSSES ABOVE ema(eur.close, 21)
    THEN BUY eur SIZING 0.01
         BRACKET {
           STOP_LOSS BY 0.5 PCT,
           TAKE_PROFIT BY 1 PCT
         }
```

The `MT5:` prefix routes orders to the configured profile.

## 6. Deploy

```bash
docker compose exec qkt qkt deploy /strategies/live_eur.qkt --as live_eur
docker compose exec qkt qkt status live_eur
docker compose exec qkt qkt logs live_eur --follow
```

For later strategy edits, do not stop unrelated daemon work. Validate the
replacement first, then resync the same deployed name:

```bash
docker compose exec qkt qkt resync /strategies/live_eur.qkt --as live_eur --dry-run
docker compose exec qkt qkt resync /strategies/live_eur.qkt --as live_eur
```

`resync` creates a replacement session through the same live pipeline, swaps it
under `live_eur`, and leaves the old session registered if validation or
reconciliation fails.

## 7. Pre-launch tick audit

Before committing real money, run the tick-feed drift check:

```bash
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 300 \
  --mt5-profile mt5 --reference mt5-history
```

Reports the absolute price difference between TradingView ticks (what your strategies see) and MT5 ticks (where orders fill). If `p95 abs diff` is wider than your stop-loss buffer, tighten or widen accordingly.

## Tear down

```bash
docker compose down            # keeps state volume
docker compose down -v         # also wipes the volume
```

## Common issues

- **Readiness returns 503.** Check the container logs for broker resolution/login errors, then use VNC if needed.
- **Symbol not found.** Verify the actual symbol in MT5 Market Watch and set `QKT_BROKER_SYMBOL_SUFFIX` or explicit aliases to match it.
- **Orders rejected with retcode 10018.** Market closed (weekend / outside session). Wait for the broker's session.

## Next

- [Concepts: broker integration](../concepts/broker-integration.md) — capability matrix, fallback paths, magic semantics
- [Operations: deploy with Docker](../operations/deploy-docker.md) — production-grade deploy patterns
