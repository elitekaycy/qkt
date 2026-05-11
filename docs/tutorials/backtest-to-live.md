# Tutorial 2 — From backtest to live

You have a strategy that backtests reasonably ([Tutorial 1](first-strategy.md)). Now you need to validate it works in real-time before risking money. This tutorial walks through the full validation pipeline: **backtest → paper-trade → live demo broker**. About 30 minutes.

By the end you'll know:

- Why paper-trading is a separate step from backtesting
- What changes between modes (and what stays the same — that's qkt's whole point)
- How to deploy on a live MT5 demo account through Docker
- What to monitor while a strategy runs

You need everything from Tutorial 1 plus:

- **Docker** + **Docker Compose v2** installed
- A free **Exness MT5 demo account** ([sign up here](https://www.exness.com/) — takes 60 seconds, gives you fake $10,000 to play with)

## Why three modes?

Most people think: "I have a backtest result, why can't I just go live?"

Three reasons why backtests aren't enough:

1. **Backtests fill at clean prices.** Real venues have spreads, slippage, partial fills. A 50% win rate in backtest can become 40% live if your stops fire on the wrong side of the spread.
2. **Backtests don't experience downtime, fat-finger orders, broker outages, login expiry, or network blips.** You need a few weeks of paper-trading to catch the operational edge cases.
3. **Real markets have news events your historical data may not capture.** A perfectly-curved backtest equity line gets a 20% drawdown when an unexpected Fed announcement hits.

qkt's design guarantee — bit-identical fills between backtest and paper given the same ticks — means the gap between paper and backtest is small. The gap between paper and live is what you need to measure.

## Mode 1 — backtest

You already did this in Tutorial 1. The command:

```bash
qkt backtest strategies/momentum.qkt --data-root data/sample --from 2024-01-15 --to 2024-01-17
```

This is the **fastest** mode. It runs the strategy as fast as the data can stream through it (tens of thousands of ticks per second). It always uses **historical data**.

Limitations:

- No live data, no real fills
- Slippage / spread / partial fills not modeled by default
- Clock advances by tick timestamp, not wall time

Backtests are for **research**: tuning parameters, picking promising strategies, computing risk-adjusted returns.

## Mode 2 — paper-trade (foreground)

Paper-trading runs the **same strategy file** against a live data feed, but the broker is in-process (no real venue, no real money).

```bash
qkt run strategies/momentum.qkt
```

A few things will happen:

1. **A live tick feed starts.** By default, qkt connects to TradingView's free-tier ticks. You'll see ticks flowing.
2. **An observability HTTP server starts** on a random high port. The terminal prints `QKT_PORT=47291` (or similar).
3. **The strategy listens to live ticks** and emits trades into an in-memory paper broker.
4. **Trades print to stdout** as they happen.

To exit, press `Ctrl-C`. The strategy stops cleanly.

While it's running, in another terminal:

```bash
curl http://localhost:47291/status | python3 -m json.tool
```

You'll see the current positions, recent trades, equity, etc. This is the same observability surface your live deployment will expose.

**What this proves:** the strategy correctly responds to a live tick stream in real-time. If it never trades, see [Debug a strategy that isn't firing](../how-to/debug-not-firing.md).

**Run paper-trade for at least a few days** before moving to live. Watch for:

- Trades you didn't expect (rule misfiring)
- Trades you expected but didn't get (warmup, threshold issue)
- Disconnections / reconnections (resilience of the feed)
- Strategy behavior around news events, weekends, market opens

## Mode 3 — live demo broker (MT5)

When paper-trading has been clean for a while, you're ready for a live demo broker. **Demo, not real money** — qkt is pre-1.0; we'll save real funds for after 1.0 ships.

### Set up the Docker stack

The repo includes a `docker-compose.yml` for the full stack. Copy the templates:

```bash
cp .env.example .env
cp qkt.config.yaml.example qkt.config.yaml
```

Edit `.env` with your Exness demo credentials (you got them when you signed up at exness.com):

```dotenv title=".env"
MT5_LOGIN=12345678
MT5_PASSWORD=your-demo-password
MT5_SERVER=Exness-MT5Trial
VNC_PASSWORD=changeme
EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

The `qkt.config.yaml.example` ships with an `exness` profile pre-configured.

### Bring up the stack

```bash
docker compose up -d
```

Two containers start:

- **mt5-gateway** — a Wine container running the MT5 desktop terminal with a Flask HTTP API exposing its functionality
- **qkt** — the trading daemon, depends on the gateway being healthy

The first time, the gateway needs you to log in interactively. Open a VNC viewer at `localhost:3000` (password from `.env`), then inside the MT5 GUI: **File → Login to Trade Account**, enter your demo credentials, click Login. The "connected" indicator at the bottom-right of MT5 turns green.

Verify from your host:

```bash
curl http://localhost:5001/health
```

If you see `{"ok": true, ...}` with your account number, the gateway is alive.

### Adapt the strategy for live

Tutorial 1's strategy uses `BACKTEST:BTCUSDT`. For live trading, swap the broker prefix and pick a symbol your demo account has:

```qkt title="strategies/momentum-live.qkt"
STRATEGY momentum_live VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 5m

RULES
    WHEN ema(eur.close, 9) CROSSES ABOVE ema(eur.close, 21)
     AND POSITION.eur = 0
    THEN BUY eur SIZING 0.01
         BRACKET {
           STOP_LOSS BY 30 PCT,
           TAKE_PROFIT BY 60 PCT
         }
         LOG "long entry" price=eur.close

    WHEN ema(eur.close, 9) CROSSES BELOW ema(eur.close, 21)
     AND POSITION.eur > 0
    THEN CLOSE eur
         LOG "exit"
```

Changes from Tutorial 1:

- `BACKTEST:BTCUSDT` → `EXNESS:EURUSD` (real broker, real symbol — but a demo account)
- `1m` → `5m` (live trading at 1-minute resolution is noisy; 5m is more comfortable)
- `SIZING 0.1` → `SIZING 0.01` (0.01 lots is the minimum on most MT5 demo accounts, about $1,000 of notional)
- Added a `BRACKET` to cap loss per trade
- Used `POSITION.eur` (not `POSITION.btc`) to match the new stream alias

Drop the file into `./strategies/` (mounted into the qkt container at `/strategies`).

### Audit the feed first

Before deploying, verify the broker's ticks are clean:

```bash
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness
```

If you see gaps, out-of-order ticks, or duplicates — investigate before deploying. A flaky feed wrecks even a good strategy.

### Deploy

```bash
docker compose exec qkt qkt deploy /strategies/momentum-live.qkt --as momentum-live
```

You should see:

```text
[INFO] deployed momentum-live
QKT_PORT=47291
```

Verify with `qkt list`:

```bash
docker compose exec qkt qkt list
```

```text
NAME              KIND       UPTIME    PORT     TRADES   STATE
momentum-live     strategy   00:00:18  47291    0        running
```

### Watch it work

Tail the logs:

```bash
docker compose exec qkt qkt logs momentum-live -f
```

You'll see live EURUSD ticks arriving, indicators warming up, and eventually the cross-up condition firing.

Check status from outside the container:

```bash
curl http://localhost:47291/status | python3 -m json.tool
```

The MT5 GUI (via VNC) will show your demo account's positions in the **Trade** tab tagged with `magic=4242` (the magic number from `qkt.config.yaml`).

### Stop cleanly

```bash
docker compose exec qkt qkt stop momentum-live --flatten
```

`--flatten` closes any open positions at market before stopping. Without it, positions stay open and qkt reconciles them on next start.

To tear down the whole stack:

```bash
docker compose down            # keeps state + logs
docker compose down -v         # full wipe
```

## What you learned

- **Three modes, one strategy file.** Backtest, paper, live — the strategy file doesn't change; only the broker prefix does (e.g. `BACKTEST` → `EXNESS`).
- **Each mode has a purpose.** Backtest = research, paper = real-time validation, live demo = operational practice.
- **The Docker stack runs MT5 in a Wine container** with an HTTP gateway, abstracting the platform from qkt.
- **Observability is uniform.** Every mode exposes the same `/status`, `/logs`, `/events` endpoints. You debug the same way regardless of mode.

## What's still missing

You've gone backtest → paper → live demo. To go to **live real money**:

- qkt needs to reach 1.0 (it isn't yet — we're at 0.25)
- The strategy needs to paper-trade and demo-trade for weeks without surprises
- Your risk rules in `qkt.config.yaml` need to be tight
- You need a monitoring story (alerts when the daemon crashes, when a stop hits, when equity drops)

[Tutorial 3](compose-portfolio.md) covers the next step in strategy complexity — composing several strategies into a regime-gated portfolio.

## If something went wrong

- **`docker compose up -d` fails on `mt5-gateway:latest`** — the image isn't on Docker Hub. Build it locally from [github.com/elitekaycy/mt5-gateway](https://github.com/elitekaycy/mt5-gateway) first.
- **`curl localhost:5001/health` returns `{"ok": false}`** — you haven't logged into MT5 yet. VNC at `localhost:3000` and log in via the GUI.
- **Strategy deploys but doesn't trade** — see [debug a strategy that isn't firing](../how-to/debug-not-firing.md). Most often the EMAs haven't warmed up yet — the first 21 candles produce no signal.
- **MT5 logs out periodically** — some brokers force daily re-auth. VNC back in. Add a healthcheck alert in production.
