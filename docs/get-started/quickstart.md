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

You should see `qkt 0.22.0` or later.

## 2. Write your first strategy

Create `momentum.qkt` in your working dir:

```qkt title="momentum.qkt"
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 0.1 BRACKET STOP_LOSS BY 50 PCT TAKE_PROFIT BY 100 PCT
```

The `BACKTEST:` prefix means "no live broker" — orders fill against an in-process paper broker.

## 3. Backtest it

```bash
qkt backtest momentum.qkt --report ./out
```

Outputs:

- `out/result.json` — machine-readable metrics
- `out/equity_global.csv`, `out/trades.csv` — raw data
- `out/report.html` — single self-contained HTML with equity curve, drawdown table, Monte Carlo fan, per-trade risk

Open `out/report.html` in a browser.

## 4. Run it as a daemon

```bash
qkt daemon &                                  # starts the control plane
qkt deploy momentum.qkt --as momentum
qkt list                                      # shows momentum running
qkt status momentum                           # current PnL, positions, last trade
qkt logs momentum --follow                    # live log stream
qkt stop momentum
qkt daemon stop
```

State (logs, control port) lives at `~/.local/state/qkt/` by default; override with `QKT_STATE_DIR`.

## Next

- [Deploy paper](deploy-paper.md) — go deeper on paper trading
- [Deploy MT5](deploy-mt5.md) — connect to a real broker
- [Concepts: architecture](../concepts/architecture.md) — what's actually happening when you `qkt deploy`
