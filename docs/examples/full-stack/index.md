# Full-stack starter

A complete, runnable bundle: every file you need to deploy two strategies live on a real broker, through Docker, with risk discipline. Clone the structure, edit credentials, run.

This is what a production-shape qkt deployment looks like. The recipe pages cover individual tasks; this page is the whole picture.

## What's in the bundle

```text
my-trading-stack/
├── .env                          # broker credentials (gitignored)
├── .env.example                  # template for new contributors
├── docker-compose.yml            # qkt + mt5-gateway services
├── qkt.config.yaml               # broker profiles + risk rules
├── strategies/
│   ├── btc-trend.qkt             # Bybit-side strategy
│   └── eur-meanrev.qkt           # MT5-side strategy
└── reports/                      # backtest outputs (gitignored)
```

Every file is real. Copy-paste into a fresh directory and follow the [walkthrough](walkthrough.md).

## `.env.example`

```dotenv title=".env.example"
# Copy this to `.env` and fill in real values.
# .env is gitignored — never commit credentials.

# ---- Bybit ----
# Generate an API key at https://www.bybit.com/app/user/api-management
# Permissions: Read + Trade. NO Withdrawal. Set spending limits.
BYBIT_API_KEY=
BYBIT_API_SECRET=
BYBIT_TESTNET=false                # set true for paper-testing first

# ---- Exness MT5 (demo) ----
# Get demo credentials from https://my.exness.com/
MT5_LOGIN=
MT5_PASSWORD=
MT5_SERVER=Exness-MT5Trial

# ---- VNC for one-time MT5 login ----
# Pick anything; you'll use this when you connect to the gateway via VNC.
VNC_PASSWORD=

# ---- Internal (don't change unless you know why) ----
EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

## `docker-compose.yml`

```yaml title="docker-compose.yml"
version: '3.9'

services:
  mt5-gateway:
    image: mt5-gateway:latest
    container_name: mt5-gateway
    environment:
      MT5_LOGIN: ${MT5_LOGIN}
      MT5_PASSWORD: ${MT5_PASSWORD}
      MT5_SERVER: ${MT5_SERVER}
      VNC_PASSWORD: ${VNC_PASSWORD}
    ports:
      - "3000:3000"        # VNC: one-time MT5 login
      - "5001:5001"        # HTTP API
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5001/health"]
      interval: 30s
      timeout: 5s
      retries: 6
      start_period: 60s
    restart: unless-stopped
    networks: [qkt-net]

  qkt:
    image: ghcr.io/elitekaycy/qkt:latest
    container_name: qkt
    depends_on:
      mt5-gateway:
        condition: service_healthy
    environment:
      BYBIT_API_KEY: ${BYBIT_API_KEY}
      BYBIT_API_SECRET: ${BYBIT_API_SECRET}
      BYBIT_TESTNET: ${BYBIT_TESTNET}
      EXNESS_GATEWAY_URL: ${EXNESS_GATEWAY_URL}
      QKT_STATE_DIR: /var/lib/qkt
    volumes:
      - ./strategies:/strategies:ro
      - ./qkt.config.yaml:/etc/qkt/qkt.config.yaml:ro
      - ./reports:/var/lib/qkt/reports
      - qkt-state:/var/lib/qkt
    ports:
      - "47000-47100:47000-47100"      # per-strategy observability
      - "47999:47999"                  # daemon control plane (internal)
    command:
      - daemon
      - --config
      - /etc/qkt/qkt.config.yaml
      - --load-dir
      - /strategies
    restart: unless-stopped
    networks: [qkt-net]

networks:
  qkt-net:
    driver: bridge

volumes:
  qkt-state:
```

## `qkt.config.yaml`

```yaml title="qkt.config.yaml"
# qkt configuration — broker profiles + risk rules.
# Edit the values, restart the daemon to pick up changes.

source: live                          # 'live' or 'backtest'
starting_balance: 10000               # USD (paper accounting baseline)
log_level: info                       # debug | info | warn | error

# ---- Broker profiles ----------------------------------------------------
# Each key here becomes a prefix usable in strategy SYMBOLS blocks:
#   SYMBOLS
#       btc = BYBIT_SPOT:BTCUSDT EVERY 1h
brokers:

  # Bybit Spot — REST + WebSocket; no gateway container needed
  bybit_spot:
    type: bybit
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
    testnet: ${BYBIT_TESTNET}
    account_type: UNIFIED

  # Exness MT5 — via mt5-gateway service in compose
  exness:
    type: mt5
    extends: exness                   # inherits built-in suffix + tz settings
    gateway_url: ${EXNESS_GATEWAY_URL}
    magic: 4242                       # unique per qkt instance
    deviation_points: 30              # max slippage on market orders

# ---- Account-level risk rules ------------------------------------------
# These apply across every strategy hosted in the daemon.
risk:
  rules:
    # Stop trading on a bad day (auto-resets at UTC midnight)
    - type: max-daily-loss
      pct: 3.0
      reset: daily

    # Permanent halt on deep drawdown (manual operator resume)
    - type: max-drawdown
      pct: 10.0
      reset: manual

    # Reject single trades that would exceed 5% of equity
    - type: max-position-pct
      pct: 5.0

    # Cap simultaneous open positions across all strategies
    - type: max-open-positions
      count: 5
```

## `strategies/btc-trend.qkt`

```qkt title="strategies/btc-trend.qkt"
-- BTC trend-follow on Bybit Spot, 1h
-- Risk: 1% per trade, ATR-sized stop

STRATEGY btc_trend VERSION 1

LET fast = 12
LET slow = 48
LET riskPct = 1.0

SYMBOLS
    btc = BYBIT_SPOT:BTCUSDT EVERY 1h

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
     AND position(btc) = 0
    THEN BUY btc SIZING riskPct PCT RISK
         STOP_LOSS AT btc.close - atr(btc, 14) * 2
         LOG INFO "long entry"
             fast=ema(btc.close, fast)
             slow=ema(btc.close, slow)
             atr=atr(btc, 14)

    WHEN ema(btc.close, fast) CROSSES BELOW ema(btc.close, slow)
     AND position(btc) > 0
    THEN CLOSE btc
         LOG INFO "exit on cross-below"
```

## `strategies/eur-meanrev.qkt`

```qkt title="strategies/eur-meanrev.qkt"
-- EUR/USD mean-reversion on Exness MT5, 15m
-- Risk: 1% per trade, scale-out at 3 targets

STRATEGY eur_meanrev VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 15m

RULES
    WHEN rsi(eur.close, 2) < 10
     AND eur.close > sma(eur.close, 200)        -- trend filter
     AND position(eur) = 0
    THEN BUY eur SIZING 1.0 PCT RISK
         BRACKET {
           STOP_LOSS AT eur.close - atr(eur, 14) * 2,
           TAKE_PROFIT {
             0.33 AT eur.close * 1.001,
             0.33 AT eur.close * 1.002,
             0.34 AT eur.close * 1.004
           }
         }
         LOG INFO "oversold entry" rsi=rsi(eur.close, 2)
```

## See also

- [Walkthrough](walkthrough.md) — step-by-step deploy of this bundle
- [Recipes: Deploy live on Exness](../../how-to/deploy-exness.md) — focused MT5 deploy
- [Cross-broker portfolio](../cross-broker.md) — same idea, fewer adaptation points
- [Risk-managed strategy](../risk-managed.md) — what the risk rules above actually do
