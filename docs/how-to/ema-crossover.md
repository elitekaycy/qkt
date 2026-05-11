# EMA crossover strategy

Build a complete EMA-crossover momentum strategy, backtest it on Bitcoin, and look at the report. Five minutes end-to-end.

## What you'll build

A strategy that goes long when the 9-period EMA crosses above the 21-period EMA, with a fixed bracket (50-point stop, 200-point target).

## 1. Write the strategy

Create `strategies/ema-cross.qkt`:

```qkt title="strategies/ema-cross.qkt"
STRATEGY ema_cross VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 50 PCT,
           TAKE_PROFIT BY 200 PCT
         }
         LOG INFO "long entry on cross above"

    WHEN ema(btc.close, 9) CROSSES BELOW ema(btc.close, 21)
    THEN CLOSE btc
         LOG INFO "exit on cross below"
```

What's going on:

- `SYMBOLS` declares one stream — the alias `btc` reads `BTCUSDT` from the `BACKTEST` broker on 1-minute candles
- The first `RULES` block fires on the up-cross; `BRACKET` attaches a percent-based SL/TP atomically
- The second fires on the down-cross to flatten before the bracket completes
- `LOG` lines tag entries/exits so you can audit later

## 2. Get some data

If you don't have historical BTCUSDT yet, fetch a month:

```bash
qkt fetch BTCUSDT --from 2024-01-01 --to 2024-02-01
```

qkt stores it at `~/.qkt/data/symbols/BTCUSDT/`. Subsequent runs read from cache.

## 3. Backtest it

```bash
qkt backtest strategies/ema-cross.qkt --from 2024-01-01 --to 2024-02-01
```

Output (truncated):

```
Trades:           14
Final realized:   234.50
Win rate:         0.571
Sharpe (daily):   1.34
Max drawdown:     -180.25

Report: ./reports/ema_cross-20240301-103245.html
```

Open the HTML report — equity curve, drawdown periods, Monte Carlo fan, per-trade risk.

## 4. Tune it

Try a different fast/slow pair without touching the file:

```bash
qkt backtest strategies/ema-cross.qkt --param fast=12 --param slow=26
```

The `--param` flag substitutes literals at compile time. To make the strategy fully parameterized, use `LET`:

```qkt
STRATEGY ema_cross VERSION 1

LET fast = 9
LET slow = 21

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
    THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 200 PCT }
```

Then `--param fast=12 --param slow=26` overrides the LETs.

## 5. Run a sweep

To find the best `(fast, slow)` pair, see [Run a parameter sweep](parameter-sweep.md).

## What just happened

This strategy uses three things every qkt strategy uses:

1. **One stream declaration** — `btc = BACKTEST:BTCUSDT EVERY 1m`. Multi-stream strategies just add more aliases.
2. **A `WHEN ... THEN ...` rule** — the condition is evaluated on every candle close; the action fires on the first tick after the rule transitions from `false` to `true` (edge-triggered).
3. **A bracket** — the entry, stop-loss, and take-profit go in as one atomic group. The broker handles all three; the engine never sees orphaned legs.

The same file you just ran in backtest will paper-trade with `qkt run strategies/ema-cross.qkt` and live-trade with `qkt deploy strategies/ema-cross.qkt`. The [parity contract](../concepts/determinism.md) guarantees the trades match.

## See also

- [Add a stop-loss](add-stop-loss.md) — every flavor of stop, when to use which
- [Run a parameter sweep](parameter-sweep.md) — grid-search over EMA periods
- [DSL grammar](../reference/dsl-grammar.md) — every keyword
- [Backtest model](../concepts/backtest-model.md) — what the report numbers mean
