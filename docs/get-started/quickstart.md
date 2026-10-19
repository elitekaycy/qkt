# Quickstart

Five minutes from clone to a running paper-traded strategy. The full canonical version lives at [`QUICKSTART.md`](https://github.com/elitekaycy/qkt/blob/main/QUICKSTART.md) at the repo root — that file is the source of truth and gets updated with every release.

## 1. Build qkt

Requires JDK 21+.

```bash
git clone https://github.com/elitekaycy/qkt
cd qkt
./gradlew installDist
export PATH="$PWD/build/install/qkt/bin:$PATH"
qkt --version
```

You should see `qkt <version> (<commit>) built <time>`, with the version in the repo's `VERSION` file.

## 2. Write your first strategy

Create `momentum.qkt` in your working dir:

```qkt title="momentum.qkt"
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 2 PCT,
           TAKE_PROFIT BY 4 PCT
         }
```

The `BACKTEST:` prefix means the local historical data store: backtests read it, and it has no live prices.

## 3. Backtest it

```bash
qkt backtest momentum.qkt \
    --data-root data/sample \
    --from 2024-01-15 --to 2024-01-17 \
    --report-dir ./out
```

Outputs:

- `out/result.json` — machine-readable metrics
- `out/equity_global.csv`, `out/trades.csv` — raw market/trade data
- `out/financing.csv` — swap paid and its signed P&L impact; add `netPnlImpact` to trade realized values when reconciling CSV totals
- `out/report.html` — single self-contained HTML with equity curve, drawdown table, Monte Carlo fan, per-trade risk

Open `out/report.html` in a browser.

## 4. Run it as a daemon

Paper trading runs the same rules on live prices, which the `BACKTEST:` stream does not have
(qkt refuses to deploy it and names the symbol to use). Make a copy on TradingView's name for the
pair, the daemon's default live feed:

```bash
sed 's/BACKTEST:BTCUSDT/BINANCE:BTCUSDT/' momentum.qkt > momentum-paper.qkt
```

Then start the daemon and deploy the copy:

```bash
qkt daemon &                                  # starts the control plane
qkt deploy momentum-paper.qkt --as momentum
qkt list                                      # shows momentum running
qkt status momentum                           # current PnL, positions, last trade
qkt logs momentum --follow                    # live log stream
qkt resync momentum-paper.qkt --as momentum --dry-run # validate an edited file
qkt resync momentum-paper.qkt --as momentum   # replace only this running name
qkt stop momentum
qkt daemon stop
```

State (logs, control port) lives at `~/.local/state/qkt/` by default; override with `QKT_STATE_DIR`.

## Next

- [Deploy paper](deploy-paper.md) — go deeper on paper trading
- [Deploy MT5](deploy-mt5.md) — connect to a real broker
- [Concepts: architecture](../concepts/architecture.md) — what's actually happening when you `qkt deploy`
