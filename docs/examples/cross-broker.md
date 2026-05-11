# Cross-broker portfolio — Bybit + MT5 together

Run BTC on Bybit Spot and EURUSD on Exness MT5 from one daemon, with one shared risk budget. Two venues, two products, one process.

## What it does

- **BTC strategy** routes through Bybit Spot
- **EURUSD strategy** routes through Exness MT5 (via the mt5-gateway container)
- **One daemon** hosts both, with shared per-trade risk rules
- **Different timeframes** per child — BTC on 1h, EUR on 15m

This is the simplest cross-venue setup. The strategies are unaware of each other; the daemon's `BrokerFactory` registry routes orders by symbol prefix.

## The strategy files

```qkt title="strategies/btc-bybit.qkt"
STRATEGY btc_bybit VERSION 1

SYMBOLS
    btc = BYBIT_SPOT:BTCUSDT EVERY 1h

RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
     AND position(btc) = 0
    THEN BUY btc SIZING 0.5 PCT RISK
         STOP_LOSS AT btc.close - atr(btc, 14) * 2

    WHEN ema(btc.close, 12) CROSSES BELOW ema(btc.close, 48)
     AND position(btc) > 0
    THEN CLOSE btc
```

```qkt title="strategies/eur-exness.qkt"
STRATEGY eur_exness VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 15m

RULES
    WHEN rsi(eur.close, 2) < 5
     AND position(eur) = 0
    THEN BUY eur SIZING 0.5 PCT RISK
         BRACKET {
           STOP_LOSS AT eur.close - atr(eur, 14) * 2,
           TAKE_PROFIT AT eur.close + atr(eur, 14) * 4
         }
```

## The config

```yaml title="qkt.config.yaml"
source: live
starting_balance: 10000

brokers:
  bybit_spot:
    type: bybit
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
    testnet: false

  exness:
    type: mt5
    extends: exness                     # built-in profile
    gateway_url: ${EXNESS_GATEWAY_URL}
    magic: 4242

risk:
  rules:
    - type: max-daily-loss
      pct: 3.0
    - type: max-drawdown
      pct: 10.0
    - type: max-position-pct
      pct: 5.0
```

The `brokers:` block defines what `BYBIT_SPOT:` and `EXNESS:` mean in the strategy files. The DSL compiler resolves these at deploy time — if a strategy references a broker that's not configured, the deploy fails fast.

## The compose file

```yaml title="docker-compose.yml"
version: '3.9'

services:
  mt5-gateway:
    image: mt5-gateway:latest
    environment:
      - MT5_LOGIN=${MT5_LOGIN}
      - MT5_PASSWORD=${MT5_PASSWORD}
      - MT5_SERVER=${MT5_SERVER}
      - VNC_PASSWORD=${VNC_PASSWORD}
    ports:
      - "3000:3000"        # VNC for one-time MT5 login
      - "5001:5001"        # HTTP API the gateway exposes
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5001/health"]
      interval: 30s
      timeout: 5s
      retries: 6
    restart: unless-stopped

  qkt:
    image: ghcr.io/elitekaycy/qkt:latest
    depends_on:
      mt5-gateway:
        condition: service_healthy
    environment:
      - BYBIT_API_KEY=${BYBIT_API_KEY}
      - BYBIT_API_SECRET=${BYBIT_API_SECRET}
      - EXNESS_GATEWAY_URL=http://mt5-gateway:5001
    volumes:
      - ./strategies:/strategies:ro
      - ./qkt.config.yaml:/etc/qkt/qkt.config.yaml:ro
      - qkt-state:/var/lib/qkt
    ports:
      - "47000-47100:47000-47100"      # per-strategy observability ports
    command: ["daemon", "--load-dir", "/strategies"]
    restart: unless-stopped

volumes:
  qkt-state:
```

Bybit doesn't need a gateway — it's accessed via REST + WebSocket directly from the qkt container. Only MT5 needs the Wine-in-Docker gateway service.

## The env file

```dotenv title=".env"
# Bybit
BYBIT_API_KEY=xxx
BYBIT_API_SECRET=xxx

# MT5 (Exness demo)
MT5_LOGIN=12345678
MT5_PASSWORD=demo-password
MT5_SERVER=Exness-MT5Trial
VNC_PASSWORD=changeme
EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

## How to run it

```bash
# 1. Bring up the stack
docker compose up -d

# 2. Log into MT5 once via VNC at localhost:3000 (one-time)

# 3. Deploy both strategies
docker compose exec qkt qkt deploy /strategies/btc-bybit.qkt   --as btc-bybit
docker compose exec qkt qkt deploy /strategies/eur-exness.qkt  --as eur-exness

# 4. Verify
docker compose exec qkt qkt list
```

Expected output:

```text
NAME              KIND       UPTIME   PORT     TRADES   STATE
btc-bybit         strategy   00:01:23 47291    0        running
eur-exness        strategy   00:01:23 47292    0        running
```

## What to expect

Each strategy runs independently — they don't share signals or coordinate. They share **only** the daemon-level risk budget. If `btc-bybit` triggers `max-daily-loss`, `eur-exness` also halts. This is by design — risk is account-level.

When the daemon shuts down (`docker compose down`):

```bash
docker compose exec qkt qkt stop btc-bybit --flatten
docker compose exec qkt qkt stop eur-exness --flatten
docker compose down
```

`--flatten` closes any open positions at market before stopping. Without it, positions stay open and are reconciled on the next daemon start.

## How to adapt it

### Add a third venue

Bybit + MT5 + TradingView (for free-tier ticks):

```yaml title="qkt.config.yaml additions"
brokers:
  tradingview:
    type: tradingview                  # free-tier, paper-only
```

```qkt title="strategies/spy-trend.qkt"
STRATEGY spy_trend VERSION 1
SYMBOLS
    spx = TRADINGVIEW:SPX500 EVERY 5m
RULES
    ...
```

TradingView is **paper-only** at the qkt level — it provides ticks but doesn't execute orders. To trade SPX live you'd need to route through a broker that supports it (IBKR, when supported).

### Same symbol, two venues (arbitrage)

Currently **not supported**. The DSL compiler rejects a strategy that declares the same underlying symbol on two different brokers because position reconciliation becomes ambiguous. See the [broker integration page](../concepts/broker-integration.md) for the deferred limitation.

### Different daemon ports

By default the daemon allocates ports 47000–47100 for child strategies' observability servers. If you need a different range:

```bash
docker compose exec qkt qkt daemon --port-range 50000-50100
```

Update the `ports:` mapping in `docker-compose.yml` to match.

## Common gotchas

- **Different magic numbers per MT5 profile.** If you ever connect two qkt daemons to the same Exness account, give them different `magic` numbers so position reconciliation doesn't collide.
- **Bybit's API keys need permissions.** Trade + read; no withdrawal. Set spending limits at the venue level too.
- **MT5 gateway logout.** Some brokers force daily re-auth. Reconnect via VNC, or restart the gateway container to trigger a re-login attempt.
- **Bybit symbol naming.** Bybit uses `BTCUSDT` for spot but `BTCUSDT` for linear perps too — they're different products. Use `BYBIT_SPOT:BTCUSDT` vs `BYBIT_LINEAR:BTCUSDT` explicitly.
- **Position currencies differ.** Bybit P&L is in USDT; MT5 P&L is in the account currency (often USD). When reading aggregate P&L, the daemon converts everything to the account's reporting currency — make sure that's set in `qkt.config.yaml`.

## What this example demonstrates

- Two strategies, two brokers, one daemon
- Broker prefix syntax (`BYBIT_SPOT:`, `EXNESS:`) and how it resolves to a configured profile
- The `extends:` shorthand for built-in MT5 broker templates
- Docker Compose orchestration of qkt + mt5-gateway
- Per-strategy observability ports
- Daemon-wide risk rules applied across all hosted strategies
- The full deployment loop: config → compose → deploy → list → stop
