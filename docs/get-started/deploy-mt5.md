# Deploy MT5

Live trading routes orders through a per-broker `mt5-gateway` HTTP service that runs MT5 in Wine inside Docker. Phase 17 shipped the broker; Phase 18 wired the dispatch into `LiveSession`; Phase 20 packaged the full stack.

## Prerequisites

- Docker + Docker Compose
- An MT5 broker account (Exness, ICMarkets, FTMO, Pepperstone, etc.)
- Server name from your broker (e.g. `Exness-MT5Real8`)

## 1. Spin up the stack

```bash
cp .env.example .env
# Edit .env: MT5_LOGIN, MT5_PASSWORD, MT5_SERVER, VNC_PASSWORD

cp qkt.config.yaml.example qkt.config.yaml
# Adjust profile entries if you're not using Exness

docker compose up -d
```

The compose file ([`docker-compose.yml`](https://github.com/elitekaycy/qkt/blob/main/docker-compose.yml)) starts:

- `mt5-gateway` on ports 3000 (VNC) + 5001 (HTTP API)
- `qkt` daemon, depends-on healthy gateway

## 2. Log in to MT5

The first time you start the gateway, you have to log in to MT5 through the VNC GUI:

1. Connect to `localhost:3000` with a VNC client.
2. Use the `VNC_PASSWORD` from `.env`.
3. In the MT5 window, log in with your broker account.

After login, `curl http://localhost:5001/health` should return `{"status":"ok"}`. The qkt daemon's `depends_on` healthcheck waits for this.

## 3. Configure the broker profile

Built-in defaults cover Exness, ICMarkets, FTMO, Pepperstone. To use Exness, the minimum config is:

```yaml title="qkt.config.yaml"
brokers:
  exness:
    type: mt5
    gateway_url: http://mt5-gateway:5001
```

The `gateway_url` matches the Docker service name. On a non-Docker setup, use `http://localhost:5001`.

For multi-account, distinct profiles via `extends:` — see the [configure-mt5-broker](../how-to/index.md) recipe.

## 4. Verify what's loaded

```bash
docker compose exec qkt qkt brokers list
```

Should show your `exness` profile with the resolved gateway URL.

## 5. Write a live strategy

```qkt title="strategies/live_eur.qkt"
STRATEGY live_eur VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m

RULES
    WHEN ema(eur, 9) crosses ABOVE ema(eur, 21)
    THEN BUY eur SIZING 0.01 BRACKET STOP_LOSS BY 30 PCT TAKE_PROFIT BY 60 PCT
```

The `EXNESS:` prefix routes orders to the configured profile.

## 6. Deploy

```bash
docker compose exec qkt qkt deploy /strategies/live_eur.qkt --as live_eur
docker compose exec qkt qkt status live_eur
docker compose exec qkt qkt logs live_eur --follow
```

## 7. Pre-launch tick audit

Before committing real money, run the tick-feed drift check:

```bash
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 300 --mt5-profile exness
```

Reports the absolute price difference between TradingView ticks (what your strategies see) and MT5 ticks (where orders fill). If `p95 abs diff` is wider than your stop-loss buffer, tighten or widen accordingly.

## Tear down

```bash
docker compose down            # keeps state volume
docker compose down -v         # also wipes the volume
```

## Common issues

- **Gateway returns 502.** MT5 isn't logged in. Connect to VNC and log in through the GUI.
- **Symbol not found.** Verify the broker's actual symbol via the MT5 market watch — Exness uses `m` suffix (`EURUSDm`), ICMarkets uses `.raw`. The `MT5Symbol` translator handles this if your profile is right.
- **Orders rejected with retcode 10018.** Market closed (weekend / outside session). Wait for the broker's session.

## Next

- [Concepts: broker integration](../concepts/broker-integration.md) — capability matrix, fallback paths, magic semantics
- [Operations: deploy with Docker](../operations/deploy-docker.md) — production-grade deploy patterns
