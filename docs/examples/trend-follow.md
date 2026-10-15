# Trend-follow — EMA crossover

The simplest momentum strategy: buy when a fast moving average crosses above a slower one, exit on the reverse cross. With a fixed bracket so a bad trade doesn't run.

## What it does

- **Trades** Bitcoin (`BTCUSD`) on 1-minute candles
- **Buys** when EMA(9) crosses above EMA(21) — momentum has shifted up
- **Closes** when EMA(9) crosses below EMA(21) — momentum has shifted down
- **Bracket** on every entry: a stop 1% below the fill and a target 2% above it, so a bad trade
  cannot run
- **One position at a time** — the entry rule only fires while flat

This is the textbook trend-follower. It catches sustained moves; it underperforms in choppy markets where the EMAs whipsaw.

## The strategy file

```qkt title="strategies/trend-follow.qkt"
STRATEGY trend_follow VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1m

PARAM fast = 9
PARAM slow = 21

RULES
    WHEN ema(btc.close, fast) CROSSES ABOVE ema(btc.close, slow)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
         BRACKET {
           STOP_LOSS BY 1.0 PCT,
           TAKE_PROFIT BY 2.0 PCT
         }
       ; LOG "long entry" fast=ema(btc.close, fast) slow=ema(btc.close, slow)

    WHEN ema(btc.close, fast) CROSSES BELOW ema(btc.close, slow)
     AND POSITION.btc > 0
    THEN CLOSE btc
       ; LOG "exit on cross-below"
```

The blocks come in a fixed order (`SYMBOLS`, `PARAM`, `LET`, `RULES`), a rule is always
`WHEN ... THEN ...`, and several actions in one rule are joined with `;`. `PARAM` makes `fast` and
`slow` overridable from the command line (`--param fast=12`) without editing the file.

## How to run it

```bash
qkt parse strategies/trend-follow.qkt
qkt backtest strategies/trend-follow.qkt --from 2026-07-01 --to 2026-08-01 --no-fetch

# Paper-trade it in the foreground
qkt run strategies/trend-follow.qkt
```

`--no-fetch` makes the backtest fail on a missing day instead of downloading it; drop it to let qkt
fetch ticks into `~/.qkt/data`.

## What to expect

qkt 0.56.2, July 2026 of `BTCUSD` ticks, the default paper broker and a 10,000 USD account:

```text
Backtest   2026-07-01 to 2026-08-01 (32 trading days)
Account    10,000.00 USD

Result
  Profit             +154.88 USD   (+1.5%)
  Round trips        1020 closed, 292 won (28.6%)
  Open at the end    none
  Worst drop         3.6%    biggest fall from a peak
  Worst day          0.9%
  Sharpe ratio       1.25     return per unit of risk
  Costs              none modeled (no spread, commission or swap)
```

A 1-minute EMA cross fires often: about 33 round trips a day here, fewer than 3 in 10 of them
winners. The small profit is before any cost. The paper broker fills at the mid price with no
spread or commission. The ticks in this window quote BTCUSD about 10 USD wide, which is 1 USD per
0.1 BTC round trip: roughly 1,020 USD over these trades, more than the profit. Rerun with `--broker mt5-sim` and your venue's costs before reading anything into it, and try
a slower timeframe (below) to cut the trade count.

## How to adapt it

### Different fast/slow periods

Edit the `PARAM` lines, or override them for one run with `--param fast=12 --param slow=26`:

```qkt
PARAM fast = 12
PARAM slow = 26
```

Or sweep them — see [Run a parameter sweep](../how-to/parameter-sweep.md).

### Different timeframe

Change `EVERY 1m` to `EVERY 5m`, `EVERY 15m`, `EVERY 1h`, etc. 1m generates noise; 1h smooths it. Most traders find 15m–1h is the sweet spot for crypto trend-follow.

### Different asset

Swap the symbol declaration:

```qkt
SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 15m
```

Then reference `gold` instead of `btc` in the rules. The strategy logic is symbol-agnostic.

### Different bracket

`1.0 PCT` / `2.0 PCT` are percentages of the entry price, not of the position value (a stop-loss
percentage must be below 50). To set prices from the signal bar instead:

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
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1 BRACKET { STOP_LOSS BY 1.0 PCT, TAKE_PROFIT BY 2.0 PCT }
```

## Common gotchas

- **The first cross waits for a cross.** If the window starts with EMA9 already above EMA21, the rule won't fire until the next cross. This is correct edge-trigger behaviour, but surprises people.
- **EMAs need warmup.** qkt infers it from the `21` in `ema(..., 21)` and loads that history from before `--from`. To ask for more, declare it on the stream: `btc = BACKTEST:BTCUSD EVERY 1m WARMUP 200 BARS`.
- **Bracket fills aren't free.** Even though they appear atomic in the DSL, the broker may fill the stop with slippage on a fast move. Paper-test for at least a few weeks before live deployment.

## What this example demonstrates

- Single stream declaration (`btc = BACKTEST:BTCUSD EVERY 1m`)
- Indicator function calls (`ema(btc.close, 9)`)
- Edge-triggered conditions (`CROSSES ABOVE`)
- Atomic bracket orders
- LOG actions chained with `;` for inline debugging
- `PARAM` values for tunable parameters

See the [DSL reference](../reference/dsl/index.md) for everything else.
