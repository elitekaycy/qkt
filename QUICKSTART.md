# qkt — quickstart

Ten minutes from clone to a backtest report on real sample data, then the same strategy paper-traded.

For a deeper introduction see the full docs at [`docs/`](docs/) and the [phase changelogs](docs/phases/).

---

## 1. Build qkt

Requires JDK 21+. (The release download bundles Java instead: see the [README](README.md#quick-start).)

```sh
git clone https://github.com/elitekaycy/qkt
cd qkt
./gradlew installDist
export PATH="$PWD/build/install/qkt/bin:$PATH"
qkt --version
```

You should see `qkt <version> (<commit>) built <time>`, with the version in the repo's `VERSION` file.

## 2. Get the sample data

```sh
qkt data sample
```

This downloads one month of BTCUSDT one-minute bars (September 2026) from Binance's public archive, checks its
SHA-256 and imports it into qkt's data store, `~/.qkt/data`.

## 3. Write a strategy

```sh
cat > momentum.qkt <<'QKT'
STRATEGY momentum VERSION 1
SYMBOLS
    btc = BINANCE:BTCUSDT EVERY 15m
RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
     BRACKET { STOP_LOSS BY 1.0 PCT, TAKE_PROFIT BY 2.0 PCT }
QKT
qkt parse momentum.qkt
```

In a backtest `BINANCE:BTCUSDT` reads the bars you imported; live, it is TradingView's name for the pair, so the
same file runs in both.

## 4. Backtest it

```sh
qkt backtest momentum.qkt --from 2026-09-01 --to 2026-10-01 --bars --report-dir ./out
```

The run is deterministic; you should see `Profit -622.01 USD (-6.2%)` and `Round trips 25 closed, 6 won`.
Outputs:
- `out/result.json`: machine-readable metrics
- `out/equity_global.csv`, `out/trades.csv`, `out/orders.jsonl`: raw data
- `out/report.html`: single self-contained HTML with equity curve, drawdown table, Monte Carlo fan, per-trade risk

Open `out/report.html` in a browser. Your own data: [Get data](docs/get-started/get-data.md).

## 5. Run it as a daemon (paper trading)

The same file trades on live prices with paper fills:

```sh
qkt daemon &                                  # starts the control plane
qkt deploy momentum.qkt --as momentum
qkt list                                      # shows momentum running
qkt status momentum                           # current PnL, positions, last trade
qkt logs momentum --follow                    # live log stream
qkt resync momentum.qkt --as momentum --dry-run # validate an edited file
qkt resync momentum.qkt --as momentum         # replace only this running name
qkt stop momentum                             # stop one strategy
qkt daemon stop                               # stop the daemon
```

State (logs, control port) lives at `~/.local/state/qkt/` by default; override with `QKT_STATE_DIR`.

## 6. Live trade via MT5 (Exness, ICMarkets, etc.)

Live trading requires `mt5-gateway` running alongside qkt. The gateway runs MT5 in Wine inside Docker and exposes an HTTP API.

### 6a. Spin up the stack

```sh
cp .env.example .env
cp qkt.config.yaml.example qkt.config.yaml
# Fill the MT5 credentials, QKT_BROKER_SERVER_TIME_ZONE, and VNC password in .env.
docker compose up -d
```

(See [`docker-compose.yml`](docker-compose.yml) at repo root for the stack: qkt daemon + mt5-gateway.)

### 6b. Verify the headless login

Set `MT5_LOGIN`, `MT5_PASSWORD`, and `MT5_SERVER` in `.env`; the gateway resolves the broker and logs in on boot. Verify readiness with:

```sh
curl -H "Authorization: Bearer $MT5_API_KEY" http://localhost:5001/health/ready
```

VNC at `localhost:3000` remains available for diagnosis when automatic resolution fails.

### 6c. Configure the broker profile

Edit `qkt.config.yaml`; inside Compose the gateway hostname is
`mt5-gateway`, not `localhost`:

```yaml
brokers:
  mt5:
    type: mt5
    gateway_url: ${QKT_BROKER_GATEWAY_URL:-http://mt5-gateway:5001}
    api_key: ${QKT_BROKER_API_KEY}
    server_time_zone: ${QKT_BROKER_SERVER_TIME_ZONE}
    symbol_suffix: ${QKT_BROKER_SYMBOL_SUFFIX:-}
    magic: ${QKT_BROKER_MAGIC:-10001}
```

Set the server clock from your broker's MT5 contract. Use `UTC`,
`new_york_close`, an IANA zone, or a fixed offset such as `+02:00`. Set the
symbol suffix only when the broker appends one.

### 6d. Write a live strategy

```
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

The `MT5:` prefix routes orders to the neutral broker profile.

### 6e. Deploy

```sh
qkt deploy live_eur.qkt --as live_eur
qkt resync live_eur.qkt --as live_eur --dry-run
qkt resync live_eur.qkt --as live_eur
qkt status live_eur
qkt logs live_eur --follow
```

### 6f. Pre-launch audit

Before committing real money, run the tick-feed drift check:

```sh
qkt audit-ticks --symbol EURUSD --duration 300 --mt5-profile mt5
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
  mt5-personal:
    type: mt5
    extends: mt5
    gateway_url: http://localhost:5001
    magic: 10005
  mt5-corporate:
    type: mt5
    extends: mt5
    gateway_url: http://localhost:5006
    magic: 10006
```

Strategies reference `MT5-PERSONAL:` or `MT5-CORPORATE:`.

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
