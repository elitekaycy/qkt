# EMA crossover strategy

Build a complete EMA-crossover momentum strategy, backtest it on a month of real Bitcoin prices, tune it and sweep it. Ten minutes end to end.

## What you'll build

A strategy that goes long when a fast EMA crosses above a slow one, with a percent bracket (1% stop, 2% target),
and closes on the cross back down. The two EMA lengths are parameters, so you can tune and sweep them.

## 1. Write the strategy

Create `strategies/ema-cross.qkt`:

```qkt title="strategies/ema-cross.qkt"
STRATEGY ema_cross VERSION 1

SYMBOLS
    btc = BINANCE:BTCUSDT EVERY 1m

LET fast = 9
LET slow = 21

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 1.0 PCT,
           TAKE_PROFIT BY 2.0 PCT
         }
       ; LOG "long entry on cross above"

    WHEN ema(btc.close, fast) CROSSES BELOW ema(btc.close, slow)
     AND POSITION.btc > 0
    THEN CLOSE btc
       ; LOG "exit on cross below"
```

What's going on:

- `SYMBOLS` declares one stream: the alias `btc` reads `BINANCE:BTCUSDT` on 1-minute candles
- `LET fast` and `LET slow` name the EMA lengths; `LET` comes after `SYMBOLS`
- The first rule fires on the up-cross when flat; `BRACKET` attaches a percent-based stop and target atomically
- The second fires on the down-cross to close before the bracket completes
- `LOG` lines tag entries and exits so you can audit later

## 2. Get some data

This page uses the month of BTCUSDT one-minute bars the quick start uses. Fetch it once:

```bash
qkt data sample
```

For other markets, export or download data and import it with `qkt data import`:
[Get data](../get-started/get-data.md).

## 3. Backtest it

```bash
qkt backtest strategies/ema-cross.qkt --from 2026-09-01 --to 2026-10-01 --bars
```

Output (the `Result` block):

```text
Result
  Profit             -318.67 USD   (-3.2%)
  Round trips        1025 closed, 289 won (28.2%)
  Open at the end    none
  Worst drop         8.5%    biggest fall from a peak
  Worst day          2.2%
  Sharpe ratio       -1.89     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

The line `Report saved: ~/.qkt/runs/<time>-ema_cross` names the run folder; open its `report.html` for the equity
curve, drawdown periods, Monte Carlo fan and every trade.

## 4. Tune it

Edit the `LET` lines and run the same command again:

```qkt
LET fast = 12
LET slow = 26
```

`LET` values are substituted wherever they are referenced, and the run is deterministic, so a change in the
`Result` block comes from your edit and nothing else.

## 5. Run a sweep

`qkt sweep` runs every combination and ranks them:

```bash
qkt sweep strategies/ema-cross.qkt --from 2026-09-01 --to 2026-10-01 --bars \
    --param fast=5,9,12 --param slow=21,26
```

On this month every pair loses before costs; `fast=5,slow=21` loses least (`totalPnL -177.80`). The sweep's
`trades` column counts fills, two per round trip. A month is far too little to choose parameters on: see
[Run a parameter sweep](parameter-sweep.md) and walk-forward testing before trusting a winner.

## What just happened

This strategy uses three things every qkt strategy uses:

1. **One stream declaration** — `btc = BINANCE:BTCUSDT EVERY 1m`. Multi-stream strategies just add more aliases.
2. **A `WHEN ... THEN ...` rule** — the condition is evaluated on every candle close; the action fires on the first tick after the rule transitions from `false` to `true` (edge-triggered).
3. **A bracket** — the entry, stop-loss, and take-profit go in as one atomic group. The broker handles all three; the engine never sees orphaned legs.

The same file paper-trades on live prices: `BINANCE:BTCUSDT` is also TradingView's name for the
pair, the default paper feed (or change the stream to your broker account's prefix). Run it with
`qkt run strategies/ema-cross.qkt` or start it in the daemon with
`qkt deploy strategies/ema-cross.qkt`. After editing a deployed copy, use
`qkt resync strategies/ema-cross.qkt --as <name> --dry-run` and then
`qkt resync strategies/ema-cross.qkt --as <name>`. The
[parity contract](../concepts/determinism.md) explains which backtest and live
paths share the same engine pipeline.

## See also

- [Add a stop-loss](add-stop-loss.md) — every flavor of stop, when to use which
- [Run a parameter sweep](parameter-sweep.md) — grid-search over EMA periods
- [DSL grammar](../reference/dsl-grammar.md) — every keyword
- [Backtest model](../concepts/backtest-model.md) — what the report numbers mean
