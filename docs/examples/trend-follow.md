# Trend-follow — EMA crossover

The simplest momentum strategy: buy when a fast moving average crosses above a slower one, exit on the reverse cross. With a fixed bracket so a bad trade doesn't run.

## What it does

- **Trades** Bitcoin on 1-minute candles
- **Buys** when EMA(9) crosses above EMA(21) — momentum has shifted up
- **Sells** when EMA(9) crosses below EMA(21) — momentum has shifted down
- **Bracket** caps each trade at a 50% loss / 100% gain (relative to the position's stop distance, not the position value)
- **One position at a time** — won't pyramid into the trade

This is the textbook trend-follower. It catches sustained moves; it underperforms in choppy markets where the EMAs whipsaw.

## The strategy file

```qkt title="strategies/trend-follow.qkt"
STRATEGY trend_follow VERSION 1

LET fast = 9
LET slow = 21

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 50 PCT,
           TAKE_PROFIT BY 100 PCT
         }
         LOG "long entry" fast=ema(btc.close, fast) slow=ema(btc.close, slow)

    WHEN ema(btc.close, fast) CROSSES BELOW ema(btc.close, slow)
    THEN CLOSE btc
         LOG "exit on cross-below"
```

## How to run it

```bash
# Fetch a month of Bitcoin data if you don't have it
./scripts/fetch-dukascopy.sh BTCUSDT 2024-01-01 2024-02-01

# Backtest
qkt backtest strategies/trend-follow.qkt --from 2024-01-01 --to 2024-02-01

# Paper-trade
qkt run strategies/trend-follow.qkt
```

## What to expect

A typical month of BTC at 1-minute resolution produces 8–15 trades with the default `(9, 21)` parameters. Win rate hovers around 40–50% — trend-following strategies make money by letting winners run, not by winning often.

```text
Trades:           14
Final realized:   234.50
Win rate:         0.571
Sharpe (daily):   1.34
Max drawdown:     -180.25
```

If your numbers are wildly different, check the date range and that the data populated correctly (`./scripts/fetch-dukascopy.sh BTCUSDT ...` writes to `~/.qkt/data/symbols/BTCUSDT/`).

## How to adapt it

### Different fast/slow periods

Edit the `LET` lines:

```qkt
LET fast = 12
LET slow = 26
```

Or sweep them — see [Run a parameter sweep](../how-to/parameter-sweep.md).

### Different timeframe

Change `EVERY 1m` to `EVERY 5m`, `EVERY 15m`, `EVERY 1h`, etc. 1m generates noise; 1h smooths it. Most traders find 15m–1h is the sweet spot for crypto trend-follow.

### Different asset

Swap the symbol declaration:

```qkt
SYMBOLS
    eur = BACKTEST:EURUSD EVERY 15m
```

Then reference `eur` instead of `btc` in the rules. The strategy logic is symbol-agnostic.

### Different bracket

The `50 PCT` / `100 PCT` is percent-of-entry-price, not percent-of-position. To set absolute prices instead:

```qkt
BRACKET {
  STOP_LOSS AT btc.close * 0.95,    -- 5% below entry
  TAKE_PROFIT AT btc.close * 1.10   -- 10% above entry
}
```

### Add a volatility filter

Trend-followers do badly in choppy ranges. Gate entries by ATR:

```qkt
LET vol = atr(btc, 14)

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
     AND vol > 50  -- only enter when there's enough movement
    THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 50 PCT, TAKE_PROFIT BY 100 PCT }
```

## Common gotchas

- **First-bar bug.** If your start date happens to be already in an uptrend (EMA9 > EMA21 on bar 1), the rule won't fire until the next cross. This is correct edge-trigger behaviour, but surprises people. To trade from the start, add a different entry rule or backfill a few days of warmup.
- **EMAs need warmup.** The first ~21 bars produce null EMA values; rules using them won't fire. The compiler infers warmup automatically from the `21` in `ema(..., 21)`. (An explicit `WARMUP N BARS` declaration is on the [roadmap](../planned.md).)
- **Bracket fills aren't free.** Even though they appear atomic in the DSL, the broker may fill the stop with slippage on a fast move. Paper-test for at least a few weeks before live deployment.

## What this example demonstrates

- Single stream declaration (`btc = BACKTEST:BTCUSDT EVERY 1m`)
- Indicator function calls (`ema(btc.close, 9)`)
- Edge-triggered conditions (`CROSSES ABOVE`)
- Atomic bracket orders
- LOG actions for inline debugging
- `LET` variables for tunable parameters

See the [DSL reference](../reference/dsl/index.md) for everything else.
