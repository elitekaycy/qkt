# Cross-broker portfolio — Bybit + MT5 together

Run BTC on Bybit Spot and gold (XAUUSD) on Exness MT5 from one daemon, with one shared risk budget. Two venues, two products, one process.

## What it does

- **BTC strategy** routes through Bybit Spot
- **Gold strategy** routes through Exness MT5 (via the mt5-gateway container)
- **One daemon** hosts both, with shared account-level risk rules
- **Different timeframes** per strategy — BTC on 1h, gold on 15m

This is the simplest cross-venue setup. The strategies are unaware of each other; the daemon's `BrokerFactory` registry routes orders by symbol prefix.

## The strategy files

```qkt title="strategies/btc-bybit.qkt"
STRATEGY btc_bybit VERSION 1

SYMBOLS
    btc = BYBIT_SPOT:BTCUSDT EVERY 1h

LET btcStopDist = atr(btc, 14) * 2

RULES
    WHEN ema(btc.close, 12) CROSSES ABOVE ema(btc.close, 48)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.5 PCT RISK
         BRACKET {
           STOP_LOSS AT btc.close - btcStopDist,
           TAKE_PROFIT AT btc.close + btcStopDist * 3
         }

    WHEN ema(btc.close, 12) CROSSES BELOW ema(btc.close, 48)
     AND POSITION.btc > 0
    THEN CLOSE btc
```

```qkt title="strategies/gold-exness.qkt"
STRATEGY gold_exness VERSION 1

SYMBOLS
    gold = EXNESS:XAUUSD EVERY 15m

LET goldStopDist = atr(gold, 14) * 2

RULES
    WHEN rsi(gold.close, 2) < 5
     AND POSITION.gold = 0
    THEN BUY gold SIZING 0.5 PCT RISK
         BRACKET {
           STOP_LOSS AT gold.close - goldStopDist,
           TAKE_PROFIT AT gold.close + goldStopDist * 2
         }
```

Both size with `SIZING 0.5 PCT RISK`: each entry loses 0.5% of equity if its stop hits. The BTC
strategy exits on the cross-below rule or its bracket, whichever comes first.

## The config

```yaml title="qkt.config.yaml"
source: live
starting_balance: 10000

brokers:
  bybit_spot:                           # the name is the prefix: BYBIT_SPOT:...
    type: gateway                       # Bybit through the qkt-venue-gateway's Bybit adapter
    gateway_url: http://gateway-bybit-spot:8443
    api_key: ${BYBIT_TRADER_TOKEN}
    expected_adapter: bybit
    expected_account_login: "${BYBIT_ACCOUNT_LOGIN}"
    expected_trade_mode: real           # demo on testnet

  exness:
    type: mt5
    extends: exness                     # built-in profile
    gateway_url: ${QKT_BROKER_EXNESS_GATEWAY_URL}
    api_key: ${QKT_BROKER_EXNESS_API_KEY}
    magic: 4242

risk:
  max_daily_loss: "300"
  max_daily_drawdown_pct: "3"
  max_drawdown_pct: "10"
```

The `brokers:` block defines what `BYBIT_SPOT:` and `EXNESS:` mean in the strategy files. The DSL compiler resolves these at deploy time — if a strategy references a broker that's not configured, the deploy fails fast.

## The compose file

```yaml title="docker-compose.yml"
version: '3.9'

services:
  gateway-bybit-spot:
    image: qkt-venue-gateway:local
    build: https://github.com/elitekaycy/qkt-venue-gateway.git#main
    environment:
      - GATEWAY_ADAPTER=bybit
      - GATEWAY_SETTING_CATEGORY=spot
      - GATEWAY_SETTING_ENVIRONMENT=mainnet
      - GATEWAY_LOGIN=${BYBIT_API_KEY}
      - GATEWAY_SECRET=${BYBIT_API_SECRET}
      - GATEWAY_TRADER_TOKEN=${BYBIT_TRADER_TOKEN}
      - GATEWAY_GUARDIAN_TOKEN=${BYBIT_GUARDIAN_TOKEN}
    volumes:
      - bybit-spot-state:/data
    restart: unless-stopped

  mt5-gateway:
    image: elitekaycy/mt5-gateway-api:0.3.2
    environment:
      - MT5_LOGIN=${MT5_LOGIN}
      - MT5_PASSWORD=${MT5_PASSWORD}
      - MT5_SERVER=${MT5_SERVER}
      - MT5_ENABLE_ALGO_TRADING=${MT5_ENABLE_ALGO_TRADING:-1}
      - API_KEY=${MT5_API_KEY}
      - PASSWORD=${VNC_PASSWORD}
    ports:
      - "3000:3000"        # VNC diagnostic fallback
      - "5001:5001"        # HTTP API the gateway exposes
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS -H \"Authorization: Bearer $$API_KEY\" http://localhost:5001/health/ready"]
      interval: 30s
      timeout: 5s
      retries: 6
    restart: unless-stopped

  qkt:
    image: ghcr.io/elitekaycy/qkt:latest
    depends_on:
      mt5-gateway:
        condition: service_healthy
      gateway-bybit-spot:
        condition: service_healthy
    environment:
      - BYBIT_TRADER_TOKEN=${BYBIT_TRADER_TOKEN}
      - BYBIT_ACCOUNT_LOGIN=${BYBIT_ACCOUNT_LOGIN}
      - QKT_BROKER_EXNESS_GATEWAY_URL=http://mt5-gateway:5001
      - QKT_BROKER_EXNESS_API_KEY=${MT5_API_KEY}
    volumes:
      - ./strategies:/strategies:ro
      - ./qkt.config.yaml:/etc/qkt/qkt.config.yaml:ro
      - qkt-state:/var/lib/qkt
    command: ["daemon", "--load-dir", "/strategies"]
    restart: unless-stopped

volumes:
  qkt-state:
  bybit-spot-state:
```

qkt reaches Bybit only through the [qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway)
running its Bybit adapter, one gateway per Bybit category; its settings are on the
[adapter's page](https://github.com/elitekaycy/qkt-venue-gateway/blob/main/adapter-bybit/README.md). MT5 goes through the Wine-in-Docker mt5-gateway service.

## The env file

```dotenv title=".env"
# Bybit (read by the gateway; qkt only holds the gateway token)
BYBIT_API_KEY=xxx
BYBIT_API_SECRET=xxx
BYBIT_TRADER_TOKEN=a-long-random-string
BYBIT_GUARDIAN_TOKEN=another-long-random-string
BYBIT_ACCOUNT_LOGIN=the-account-login-the-gateway-reports

# MT5 (Exness demo)
MT5_LOGIN=12345678
MT5_PASSWORD=demo-password
MT5_SERVER=Exness-MT5Trial
MT5_ENABLE_ALGO_TRADING=1
MT5_API_KEY=replace-with-a-long-random-value
VNC_PASSWORD=changeme
QKT_BROKER_EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

## Backtest both before deploying

A backtest reads ticks by symbol and ignores the broker prefix, so `gold-exness.qkt` runs as it is
against local `XAUUSD` ticks. There is no `BTCUSDT` in the local store used here, only `BTCUSD`, so
the BTC file runs from a copy with its stream swapped to `BACKTEST:BTCUSD` (`--symbols` filters
streams, it does not rename them), plus the `instruments.yaml` entry from the
[breakout example](breakout.md#how-to-run-it) that risk sizing needs:

```bash
qkt backtest strategies/gold-exness.qkt --from 2026-07-06 --to 2026-08-29 --no-fetch

sed 's/BYBIT_SPOT:BTCUSDT/BACKTEST:BTCUSD/' strategies/btc-bybit.qkt > btc-backtest.qkt
qkt backtest btc-backtest.qkt --from 2026-01-02 --to 2026-09-15 \
    --instruments instruments.yaml --no-fetch
```

qkt 0.56.2, the default paper broker, 10,000 USD each, run separately. Gold, 6 July to 28 August
2026:

```text
Result
  Profit             -50.04 USD   (-0.5%)
  Round trips        93 closed, 30 won (32.3%)
  Open at the end    EXNESS:XAUUSD +0.01  worth +4.85 USD
  Worst drop         8.2%    biggest fall from a peak
  Worst day          2.2%
  Sharpe ratio       -0.16     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

BTC, 2 January to 14 September 2026:

```text
Result
  Profit             +718.23 USD   (+7.2%)
  Round trips        73 closed, 22 won (30.1%)
  Open at the end    none
  Worst drop         3.6%    biggest fall from a peak
  Worst day          0.9%
  Sharpe ratio       1.47     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

Separate backtests do not share a risk budget; in the daemon both draw on the one set of global
rules in the config above.

## How to run it

```bash
# 1. Bring up the stack
docker compose up -d

# 2. Log into MT5 once via VNC at localhost:3000 (one-time)

# 3. Deploy both strategies
docker compose exec qkt qkt deploy /strategies/btc-bybit.qkt   --as btc-bybit
docker compose exec qkt qkt deploy /strategies/gold-exness.qkt --as gold-exness

# 4. Verify
docker compose exec qkt qkt list
```

Expected output:

```text
NAME              KIND       UPTIME   PORT     TRADES   STATE
btc-bybit         strategy   00:01:23 47291    0        running
gold-exness       strategy   00:01:23 47292    0        running
```

## What to expect

Each strategy runs independently — they don't share signals or coordinate. They share **only** the daemon-level risk budget. If `btc-bybit` breaches the global `max_daily_loss`, `gold-exness` also halts. This is by design — risk is account-level.

When the daemon shuts down (`docker compose down`):

```bash
docker compose exec qkt qkt stop btc-bybit --flatten
docker compose exec qkt qkt stop gold-exness --flatten
docker compose down
```

`--flatten` closes any open positions at market before stopping. Without it, positions stay open and are reconciled on the next daemon start.

## How to adapt it

### Add a third venue

Bybit + MT5 + TradingView (for free-tier ticks):

Bybit + MT5 already gives you crypto + FX/indices/commodities. Adding more venues is mostly a matter of writing a gateway adapter and adding a `type: gateway` entry named for its prefix.

!!! info "TradingView is a data source, not a broker"
    TradingView is supported as a **market-data vendor** for ticks (used in paper trading and the `qkt run` foreground mode), but it's not a broker — you can't route orders through it. Strategies that want SPX exposure today need a broker that supports it (IBKR adapter is on the [roadmap](../planned.md)).

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
