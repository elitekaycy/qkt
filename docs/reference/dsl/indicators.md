# Indicators

The technical-analysis functions you can call in conditions and expressions. qkt ships ~10 hand-implemented indicators — the most common ones, written from scratch with hand-computed tests for correctness.

## Function call shape

Every indicator is a function call:

```qkt
<indicator>(<stream_or_field>, <period>, [<extra_args>...])
```

The first argument is what to compute on. For most indicators that's `<stream>.close` (close price). For ATR it's the stream itself (uses high/low/close). For VWAP it's the stream too (uses ticks + volume).

## Catalog

### Moving averages

```qkt
ema(<value>, <period>)        -- exponential moving average
sma(<value>, <period>)        -- simple moving average
wma(<value>, <period>)        -- weighted moving average
```

`<value>` is typically `stream.close` but can be any price expression. `<period>` is the lookback length in bars (not ticks).

```qkt
ema(btc.close, 9)             -- 9-bar EMA of close
sma(btc.close, 200)           -- 200-bar SMA of close
sma((btc.high + btc.low) / 2, 20)  -- 20-bar SMA of midpoint
```

**When to use which:**

- `ema` — reacts faster to recent prices. Most common for short-term signal generation.
- `sma` — equal weighting; smoother but slower. Used for long-term filters (50, 100, 200 period).
- `wma` — linearly weighted (recent bars count more). Less common; sometimes useful when you want EMA-like responsiveness with a discrete window.

### Oscillators

```qkt
rsi(<value>, <period>)        -- Relative Strength Index, 0-100
```

```qkt
rsi(btc.close, 14)            -- standard 14-period RSI
rsi(btc.close, 2)             -- Connors-style mean-reversion RSI
```

RSI uses Wilder's smoothing. Bounded [0, 100]. Below 30 = oversold, above 70 = overbought are the conventional thresholds.

### Volatility

```qkt
atr(<stream>, <period>)       -- Average True Range
```

```qkt
atr(btc, 14)                  -- standard 14-bar ATR on btc stream
```

ATR is Wilder's smoothed average of true range. Uses high/low/close — so you pass the **stream**, not `stream.close`. Used heavily in stop-loss sizing (`STOP_LOSS BY atr(btc, 14) * 2`).

### MACD

```qkt
macd(<value>, <fast>, <slow>, <signal>)
```

Default Connors-style values: `(12, 26, 9)`. Returns the MACD line:

```qkt
macd(btc.close, 12, 26, 9)
```

To compare against the signal line, qkt provides `macd_signal` and `macd_hist`:

```qkt
WHEN macd(btc.close, 12, 26, 9) CROSSES ABOVE macd_signal(btc.close, 12, 26, 9)
THEN BUY btc SIZING 0.1
```

The three values share the same internal computation — the parser deduplicates.

### Bollinger Bands

```qkt
bollinger_upper(<value>, <period>, <stddev>)
bollinger_middle(<value>, <period>, <stddev>)    -- = SMA
bollinger_lower(<value>, <period>, <stddev>)
```

```qkt
WHEN btc.close > bollinger_upper(btc.close, 20, 2.0)
THEN LOG INFO "above upper Bollinger"
```

`<stddev>` is the band width in standard deviations; the typical value is 2.0.

### VWAP

```qkt
vwap(<stream>, <period>)      -- rolling N-tick VWAP
```

Takes the stream because it needs both price and volume. The period is in **ticks**, not bars — VWAP is a tick-level indicator.

```qkt
WHEN btc.close > vwap(btc, 1000)         -- price above 1000-tick VWAP
THEN BUY btc SIZING 0.1
```

If a tick has no volume, that tick contributes 0 (doesn't pollute the average).

### Donchian (rolling extremes)

```qkt
highest(<value>, <period>)    -- highest value in the last <period> bars
lowest(<value>, <period>)
```

```qkt
WHEN btc.close > highest(btc.close, 20)     -- breakout above 20-bar high
THEN BUY btc SIZING 0.1

WHEN btc.close < lowest(btc.close, 10)      -- breakdown below 10-bar low
 AND position(btc) > 0
THEN CLOSE btc
```

**`highest(close, N)` excludes the current bar.** It looks at the last N **prior** closes. This matters for breakout strategies — otherwise `close > highest(close, N)` could never fire (the current bar can't exceed itself).

## Math helpers

Available alongside indicators:

```qkt
abs(<expr>)             -- absolute value
max(<a>, <b>)           -- maximum of two values
min(<a>, <b>)           -- minimum
sqrt(<expr>)            -- square root
log(<expr>)             -- natural log
exp(<expr>)             -- e^x
floor(<expr>)           -- floor
ceil(<expr>)            -- ceiling
round(<expr>)           -- round half-even
pow(<base>, <exp>)      -- exponentiation
```

```qkt
LET vol = sqrt(252 * sum(pow(btc.close / btc.close[1] - 1, 2), 20))
```

Annualized 20-bar realized volatility from log returns. Composes the helpers and a 20-bar `sum` aggregate.

## Aggregates

```qkt
sum(<expr>, <period>)   -- rolling sum
avg(<expr>, <period>)   -- rolling mean (same as sma)
count(<predicate>, <period>)   -- rolling count of bars where predicate is true
```

```qkt
LET upDays = count(btc.close > btc.close[1], 20)
WHEN upDays >= 15 THEN LOG INFO "trend confirmed: 15 of last 20 bars were up"
```

## Warmup

Every indicator has a warmup period — bars needed before it produces a meaningful value.

| Indicator | Warmup |
| --- | --- |
| `sma(value, N)` | N bars |
| `ema(value, N)` | N bars (seeds with SMA of first N) |
| `wma(value, N)` | N bars |
| `rsi(value, N)` | N+1 bars |
| `atr(stream, N)` | N bars |
| `macd(value, F, S, sig)` | S + sig bars |
| `bollinger_*(value, N, k)` | N bars |
| `vwap(stream, N)` | N ticks |
| `highest`/`lowest(value, N)` | N bars |

During warmup the indicator returns `null`. Comparisons with `null` are `false` — your rule won't fire, but it won't crash either.

The DSL compiler **automatically infers warmup requirements** from your indicator calls and tells the engine to discard pre-warmup signals. To declare a custom warmup window explicitly:

```qkt
STRATEGY my_strat VERSION 1
WARMUP 200 BARS

SYMBOLS ...
```

Useful when you want to ensure long-period indicators (200-bar SMA) are warm even on short backtest windows.

## Composing indicators

Indicators return numbers; numbers compose freely. Common patterns:

### Difference between indicators

```qkt
LET emaSpread = ema(btc.close, 9) - ema(btc.close, 21)
WHEN emaSpread > 100 THEN LOG INFO "strong trend"
```

### Ratio

```qkt
LET ratio = ema(btc.close, 9) / ema(btc.close, 21)
WHEN ratio > 1.02 THEN LOG INFO "2% above slow MA"
```

### Multiply ATR for stops

```qkt
STOP_LOSS AT btc.close - atr(btc, 14) * 2     -- 2-ATR stop
```

### Combine across streams

```qkt
LET corr_indicator = ema(btc.close, 20) / ema(eth.close, 20)
```

## When the compiler complains

- **"Unknown indicator"** — typo, or you used a name not in the catalog. Check this page.
- **"Indicator requires Stream, got Number"** — passing `btc.close` where `btc` was expected (ATR, VWAP). Pass the stream, not the field.
- **"Indicator requires Number, got Stream"** — opposite — passing `btc` where `btc.close` was expected.
- **"Period must be positive integer"** — you wrote `ema(btc.close, -9)` or `ema(btc.close, 0)`.

## Common gotchas

- **`atr` and `vwap` take a stream, not `stream.close`.** They need OHLC / volume, not just close.
- **Periods are in bars, not ticks** — except VWAP, which is ticks.
- **Indicators are recomputed per bar.** Long-period indicators are computationally cheap (linear in period); don't worry.
- **No mutable state across rules.** Each rule's indicator references compile to independent indicator objects (deduplicated where the period + value source match). State is internal — you can't read "the previous value" via `[N]` on an indicator call (`ema(...)[1]` is **not** valid). Use a `LET` to capture the current value, then on the next bar your `LET` evaluation is for the new bar.
- **VWAP needs volume.** A tick feed with `volume=null` produces a VWAP of `null`. Most CSV/MT5 feeds have volume; some Bybit endpoints don't.

## What this composes with

- [Conditions](conditions.md) — every indicator can appear in `WHEN` clauses
- [Expressions](expressions.md) — arithmetic on indicator values
- [SIZING](sizing.md) — ATR is the canonical risk-sizing input
- [BRACKET](bracket.md) — ATR-based stops and targets
- [LET](let-defaults.md) — name reusable indicator combinations
