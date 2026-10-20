# Quickstart

Ten minutes from download to a backtest report, then the same strategy paper-traded on live prices. qkt fetches a
month of real Bitcoin prices to practise on, so nothing here needs an account.

## 1. Install qkt

Download `qkt-<version>-linux-x64.tar.gz` from the [latest release](https://github.com/elitekaycy/qkt/releases/latest).
It bundles Java, so you need nothing else:

```bash
tar xzf qkt-*-linux-x64.tar.gz
export PATH="$PWD/qkt/bin:$PATH"
qkt --version
```

You should see `qkt <version> (<commit>) built <time>`. Windows, Docker and building from source are in
[Install](install.md).

## 2. Get the sample data

```bash
qkt data sample
```

This downloads one month of BTCUSDT one-minute bars (September 2026) from Binance's public archive, checks the file
against its published SHA-256 so every machine gets the same bytes, and imports it with `qkt data import` into qkt's
data store, `~/.qkt/data`. It reports `Wrote 30 day(s)` and the data's terms. It also notes that `BINANCE:BTCUSDT`
has no instrument spec: that only matters once you model costs, in [Instruments](../instruments/index.md).

## 3. Write your first strategy

```bash
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

`btc` is a 15-minute stream of `BINANCE:BTCUSDT`. In a backtest that name reads the bars you imported; live, it
is TradingView's name for the pair, so the same file runs in both.

## 4. Backtest it

```bash
qkt backtest momentum.qkt --from 2026-09-01 --to 2026-10-01 --bars --report-dir ./out
```

`--bars` runs on the one-minute bars, filling stops and targets at their levels inside each bar. The run is
deterministic: the same data and strategy give the same numbers on every machine, so you should see

```text
Result
  Profit             -622.01 USD   (-6.2%)
  Round trips        25 closed, 6 won (24.0%)
```

and these files in `./out` (without `--report-dir` they go to `~/.qkt/runs/<time>-momentum`):

- `report.html`: one self-contained page with the equity curve, drawdowns, a Monte Carlo fan and every trade
- `result.json`: the metrics, for tools
- `trades.csv`, `orders.jsonl`, `equity_global.csv`: every trade, order and equity point
- `financing.csv`: swap paid and its P&L impact

Open `out/report.html` in a browser. The figures are hypothetical: fills at the bar's price, no costs. Add
`--broker mt5-sim` for spread and slippage.

## 5. Change something and run again

Edit the strategy, for example `ema(btc.close, 20)` and `ema(btc.close, 50)`, and run the same command with
`--report-dir ./out2`. Compare the two `Result` blocks, or the two `report.html` pages. Because runs are
deterministic, any difference comes from your change.

## 6. Paper-trade it on live prices

```bash
qkt run momentum.qkt
```

This runs the strategy in the foreground on live TradingView prices with paper fills. It first loads recent
history to warm up the EMAs, then trades as bars close; Ctrl-C stops it. To keep it running in the background,
use the daemon:

```bash
qkt daemon &                                  # starts the control plane
qkt deploy momentum.qkt --as momentum
qkt list                                      # shows momentum running
qkt status momentum                           # current PnL, positions, last trade
qkt logs momentum --follow                    # live log stream
qkt resync momentum.qkt --as momentum --dry-run # validate an edited file
qkt resync momentum.qkt --as momentum         # replace only this running name
qkt stop momentum
qkt daemon stop
```

State (logs, control port) lives at `~/.local/state/qkt/` by default; override with `QKT_STATE_DIR`.

## Next

- [Get data](get-data.md): your own data from MetaTrader 5, HistData, Dukascopy, Binance and others
- [Deploy paper](deploy-paper.md): go deeper on paper trading
- [Deploy MT5](deploy-mt5.md): connect to a real broker
- [Concepts: architecture](../concepts/architecture.md): what happens when you `qkt deploy`
