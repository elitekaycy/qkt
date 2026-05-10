# qkt — quickstart

Five minutes from clone to a running paper-traded strategy.

For a deeper introduction see the full docs at [`docs/`](docs/) and the [phase changelogs](docs/phases/).

---

## 1. Build qkt

Requires JDK 21+.

```sh
git clone https://github.com/elitekaycy/qkt
cd qkt
./gradlew installDist
export PATH="$PWD/build/install/qkt/bin:$PATH"
qkt --version
```

You should see `qkt 0.21.0` (or later).

## 2. Write a strategy

Create `momentum.qkt` in your working dir:

```
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 0.1 BRACKET STOP_LOSS BY 50 PCT TAKE_PROFIT BY 100 PCT
```

The `BACKTEST:` prefix means "no live broker" — orders fill against an in-process paper broker.

## 3. Backtest it

```sh
qkt backtest momentum.qkt --report ./out
```

Outputs:
- `out/result.json` — machine-readable metrics
- `out/equity_global.csv`, `out/trades.csv` — raw data
- `out/report.html` — single self-contained HTML with equity curve, drawdown table, Monte Carlo fan, per-trade risk

Open `out/report.html` in a browser.

## 4. Run it as a daemon (paper trading)

```sh
qkt daemon &                                  # starts the control plane
qkt deploy momentum.qkt --as momentum
qkt list                                      # shows momentum running
qkt status momentum                           # current PnL, positions, last trade
qkt logs momentum --follow                    # live log stream
qkt stop momentum                             # stop one strategy
qkt daemon stop                               # stop the daemon
```

State (logs, control port) lives at `~/.local/state/qkt/` by default; override with `QKT_STATE_DIR`.

## 5. Live trade via MT5 (Exness, ICMarkets, etc.)

Live trading requires `mt5-gateway` running alongside qkt. The gateway runs MT5 in Wine inside Docker and exposes an HTTP API.

### 5a. Spin up the stack

```sh
docker compose up -d
```

(See [`docker-compose.yml`](docker-compose.yml) at repo root for the stack: qkt daemon + mt5-gateway.)

### 5b. Log in to MT5

Connect to VNC at `localhost:3000`, log in to your broker account through the MT5 GUI. The gateway's `/health` endpoint becomes ready once you're logged in.

### 5c. Configure the broker profile

Edit `qkt.config.yaml`:

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://localhost:5001
```

Built-in defaults already cover Exness's symbol suffix (`m`), aliases (NAS100→USTEC), TZ, and magic. Override only what differs.

### 5d. Write a live strategy

```
STRATEGY live_eur VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m

RULES
    WHEN ema(eur, 9) crosses ABOVE ema(eur, 21)
    THEN BUY eur SIZING 0.01 BRACKET STOP_LOSS BY 30 PCT TAKE_PROFIT BY 60 PCT
```

The `EXNESS:` prefix routes orders to the configured profile (MT5 today, any other protocol tomorrow).

### 5e. Deploy

```sh
qkt deploy live_eur.qkt --as live_eur
qkt status live_eur
qkt logs live_eur --follow
```

### 5f. Pre-launch audit

Before committing real money, run the tick-feed drift check:

```sh
qkt audit-ticks --symbol EURUSD --duration 300 --mt5-profile exness
```

Reports the absolute price difference between TradingView (the tick source qkt's strategies see) and MT5 (where orders actually fill). If `p95 abs diff` is wider than your stop-loss buffer, tighten or expand stops accordingly.

---

## Common patterns

### Multiple strategies, one daemon

Drop `.qkt` files in a directory and start the daemon with `--load-dir`:

```sh
qkt daemon --load-dir ./strategies
```

Each file auto-deploys at startup.

### Multi-account on the same broker

Distinct profiles via `extends:`:

```yaml
brokers:
  exness-personal:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5001
    magic: 10005
  exness-corporate:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5006
    magic: 10006
```

Strategies reference `EXNESS-PERSONAL:` or `EXNESS-CORPORATE:`.

### Portfolio of strategies

A `.qkt` file with `PORTFOLIO` instead of `STRATEGY`:

```
PORTFOLIO mybook VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
IMPORT 'trend.qkt' AS trend
IMPORT 'range.qkt' AS range HOLD
RULES
    WHEN btc.close > 100 RUN trend
    RUN range
```

`qkt deploy mybook.qkt` fans out into per-child `LiveSession`s; `qkt status mybook` aggregates; `qkt status mybook/trend` drills in.

---

## Going further

- [`docs/phases/`](docs/phases/) — per-phase changelogs with usage cookbooks for every shipped feature
- [`docs/logging.md`](docs/logging.md) — MDC keys, log conventions, how to wire logback overrides
- [`docs/backlog.md`](docs/backlog.md) — what's done, in progress, and queued
- [`examples/docker/`](examples/docker/) — Docker recipes for production deploys
