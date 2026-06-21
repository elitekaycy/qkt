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
 AND POSITION.btc > 0
THEN CLOSE btc
```

**`highest(close, N)` excludes the current bar.** It looks at the last N **prior** closes. This matters for breakout strategies — otherwise `close > highest(close, N)` could never fire (the current bar can't exceed itself).

### Statistical

```qkt
zscore(<series>, <period>)    -- rolling z-score over the last <period> values
```

`zscore` answers "how far is the latest value from its recent average, in standard deviations." It is `(latest − mean) / stddev` over the last `<period>` values, using the **sample (n−1)** standard deviation. A z of `+2` means the newest value sits two standard deviations above its window mean — the canonical mean-reversion / pairs-spread entry.

```qkt
WHEN zscore(btc.close, 100) >= 2.0     -- 100-bar return spike, two sigma high
THEN SELL btc SIZING 0.1
```

Warmup is `<period>` bars. While warming up `zscore` returns `null`, and it also returns `null` when the window is flat (zero standard deviation — the z-score is undefined). As everywhere, `null` makes the comparison `false`, so the rule simply doesn't fire.

**The series can be any arithmetic expression that references at least one stream** (expression-fed binding, #174) — not just a bare `stream.close`. This is what makes `zscore` a pairs-trading primitive: feed it a ratio or spread of two streams.

```qkt
-- Pairs spread: trade gold against silver when their ratio reaches an extreme.
WHEN zscore(gold.close / silver.close, 100) >= 2.0 AND POSITION.gold = 0
THEN SELL gold
WHEN zscore(gold.close / silver.close, 100) <= -2.0 AND POSITION.gold = 0
THEN BUY gold
```

A cross-stream series like `gold.close / silver.close` mixes two streams. The binding gates updates on the **primary alias** — the first stream the expression references (here `gold`) — and reads the other stream's latest closed bar. For that read to be the **same-window** value rather than the previous window's, put the two streams in a shared `SYNCHRONIZE` group so their bars are delivered together:

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    SYNCHRONIZE gold silver
```

Without the `SYNCHRONIZE`, the spread is still computed, but `silver.close` may lag `gold.close` by one bar — the same cross-stream alignment caveat that applies to `sma(silver.close, …)` inside a gold-anchored rule.

### Cross-series (two-stream)

Two indicators take **two** series and measure how a pair of streams move together over a rolling window. They follow the same primary-alias / `SYNCHRONIZE` alignment rules as a cross-stream `zscore` — put the two streams in a shared `SYNCHRONIZE` group so each bar reads the same-window value from both.

```qkt
correlation(<a>, <b>, <period>)   -- rolling Pearson correlation, in [-1, +1]
beta(<a>, <b>, <period>)          -- rolling OLS slope of <a> on <b> (hedge ratio)
```

`correlation` is the Pearson correlation coefficient of the two series over the last `<period>` bars: `+1` means they move in lockstep, `0` unrelated, `-1` opposite. Use it to gate on a correlation regime — for example, only act once two normally-coupled pairs **decouple**, betting they re-couple.

```qkt
-- Fade a EUR/GBP decoupling: realized correlation has collapsed below its baseline
-- AND the ratio is extended, so bet on re-coupling.
WHEN correlation(eur.close, gbp.close, 48) < 0.40
 AND abs(zscore(eur.close / gbp.close, 48)) > 2.0
THEN SELL eur
```

`beta` is the slope of an ordinary-least-squares fit of `<a>` on `<b>` over the window — how many units `<a>` moves per unit move in `<b>`. It is the classic hedge ratio: to be market-neutral against `<b>`, hold `beta` units of `<b>` per unit of `<a>`. e.g. `beta(stock.close, index.close, 60)` over closes that move 2-for-1 → ~2.

```qkt
LET hedgeRatio = beta(stock.close, index.close, 60)
```

Both warm up over `<period>` bars, returning `null` until the window is full (and when a series has zero variance, where the statistic is undefined). Either `<a>` or `<b>` may be any arithmetic expression that references a stream, exactly like `zscore`.

### Regression residual

```qkt
resid(<dependent>, <regressor1>, …, <period>)   -- rolling multi-regressor OLS residual
```

`resid` fits an ordinary-least-squares regression of the **dependent** series on one or more **regressor** series over the last `<period>` bars and reports the latest bar's residual — the part of the dependent series the regressors do not explain. The first argument is the dependent series, every argument before the trailing integer is a regressor, and the last argument is the lookback. At least one regressor is required, and `<period>` must exceed the number of regressors plus one (you need more observations than coefficients to fit).

It generalizes the pairs spread: regressing one instrument on the others it co-moves with leaves a residual that is the instrument's idiosyncratic move — usually a flow shock that reverts, not new information. A residual far from zero means the dependent moved on its own.

```qkt
-- Trade GBP's idiosyncratic move: the part not explained by the broad-dollar factor
-- (EUR + AUD). z-score the residual and fade the extremes.
SYMBOLS
    gbp = EXNESS:GBPUSD EVERY 15m WARMUP 200 BARS
    eur = EXNESS:EURUSD EVERY 15m
    aud = EXNESS:AUDUSD EVERY 15m
    SYNCHRONIZE gbp eur aud
RULES
    WHEN zscore(resid(gbp.close, eur.close, aud.close, 96), 96) > 2.0 AND POSITION.gbp = 0
    THEN SELL gbp
```

Each series may be any arithmetic expression that references a stream, exactly like `zscore`. `resid` returns `null` until the window is full and when the regressors are collinear or constant (the fit is undefined). Because `zscore(resid(...))` chains two rolling windows, set an explicit `WARMUP` covering both (the residual period plus the z-score period) — the compiler infers only the outer window for chained indicators.

### Confirmation ratio (cross-symbol)

```qkt
confirm_ratio(<signal>, <peer1>, …, <lookback>)   -- fraction of peers confirming, in [0, 1]
```

`confirm_ratio` measures how much a basket agrees with a move in the signal series. It returns the fraction of peer series whose return over the last `<lookback>` bars is the **same sign** as the signal's return over the same window. A low ratio means the signal moved while the peers did not — an idiosyncratic move to fade; a high ratio means the whole basket moved together (a broad factor move). The first argument is the signal, every argument before the trailing integer is a peer, and the last argument is the lookback.

To flip polarity for an inverse pair, **negate the peer** rather than passing a polarity list: `-usdchf.close` rises exactly when the dollar weakens, so it confirms a EURUSD rally.

```qkt
-- Fade an unconfirmed EURUSD spike: the dollar basket did not follow, so it is EUR noise.
WHEN zscore(eur.close, 48) > 2.0
 AND confirm_ratio(eur.close, gbp.close, aud.close, -chf.close, 4) < 0.5
 AND POSITION.eur = 0
THEN SELL eur
```

Like `resid`, `confirm_ratio` is bound through the multi-series path and reads the peers' latest closed bars; put the streams in a `SYNCHRONIZE` group for same-window alignment. It returns `null` until `<lookback> + 1` bars are seen.

## Session-anchored indicators

These reset on a fixed UTC clock boundary rather than sliding over a fixed bar count.

### Session VWAP

```qkt
vwap_session(<stream>, <anchorHour>)        -- volume-weighted average since anchorHour UTC
vwap_session_stdev(<stream>, <anchorHour>)  -- volume-weighted stddev around that VWAP
```

`vwap_session` is the volume-weighted average of typical price `(high+low+close)/3`, accumulated since the most recent `<anchorHour>:00` UTC and reset each day at that hour — `anchorHour = 0` is the classic session-open VWAP, `anchorHour = 12` anchors at the London/NY overlap. Pass the **stream** (it needs volume). `vwap_session_stdev` is the volume-weighted standard deviation of price around that running VWAP, so a strategy bands the VWAP and fades touches of the bands back toward it.

```qkt
-- Fade the upper 2-sigma band of the overlap-anchored session VWAP back to VWAP.
LET vwap = vwap_session(gold, 12)
LET band = vwap + 2 * vwap_session_stdev(gold, 12)
WHEN gold.close >= band AND POSITION.gold = 0 THEN SELL gold
```

Volume-less candles contribute nothing, like `vwap`. Both return `null` until a volume-bearing candle is seen in the current session. (Note: a broker that does not report volume — e.g. the MT5 gateway on FX/metals — leaves these inert live; they are backtest-faithful where the data carries volume.)

### Session range

```qkt
session_range_high(<stream>, <sh>, <sm>, <eh>, <em>)   -- high of the prior completed UTC window
session_range_low(<stream>, <sh>, <sm>, <eh>, <em>)    -- low of the prior completed UTC window
```

These latch the high and low of the most recent **completed** instance of the daily UTC window `[sh:sm, eh:em)` and hold them as constant price levels until the next instance completes. Unlike `highest`/`lowest`, which slide forward every bar, this freezes a prior session's boundaries — e.g. the overnight Asian range stays fixed through the London morning. The window wraps midnight when the start is after the end. Mid and width compose: mid = `(high + low) / 2`, width = `high - low`.

```qkt
-- Fade a poke above the 00:00-07:00 UTC Asian range during the 07:00-11:30 London window.
LET asianHigh = session_range_high(gold, 0, 0, 7, 0)
WHEN session_window(7, 0, 11, 30) AND gold.close > asianHigh AND POSITION.gold = 0
THEN SELL gold
```

The level is `null` until the first window completes (a warmup delay, not a bug).

### Percentile rank

```qkt
percentile_rank(<value>, <lookback>)   -- fraction of the trailing window below the current value
```

`percentile_rank` returns the fraction of the trailing `<lookback>` window strictly below the current value, in `[0, 1)`. It is distribution-free, so it separates a **bimodal** series where `zscore` cannot — on a realized-vol series that splits into a calm cluster and a hot cluster, the mean sits in the empty trough between them, but the rank still puts the calm bars below 0.5.

```qkt
-- Trade the mean-reversion band only in the calm half of the realized-vol regime.
WHEN percentile_rank(stddev(xag.close, 30), 200) < 0.5
 AND xag.close <= keltner_lower(xag, 20, 2.0)
THEN BUY xag
```

Warmup is `<lookback>` bars.

## Math helpers

Available alongside indicators:

```qkt
abs(<expr>)             -- absolute value
sqrt(<expr>)            -- square root
log(<expr>)             -- natural log
exp(<expr>)             -- e^x
pow(<base>, <exp>)      -- exponentiation
floor(<expr>)           -- round down to the nearest integer
ceil(<expr>)            -- round up to the nearest integer
round(<expr>)           -- round to the nearest integer (half to even)
mod(<a>, <b>)           -- floored modulo; for a positive step, distance past the grid below
```

`mod` is the round-number / big-figure primitive: `mod(price, step)` is how far price sits
past the nearest multiple of `step` below it, and the nearest grid level is
`round(price / step) * step`.

```qkt
WHEN mod(gold.close, 10) < 1        -- price within $1 of a round $10 figure
THEN LOG INFO "near a big figure"
```

`max` and `min` are **windowed aggregates** (see Aggregates), not scalar two-argument
functions. For the larger or smaller of two values, use a `CASE` expression — e.g. the
upper wick of a bar is `high - (CASE WHEN open > close THEN open ELSE close END)`.

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
| `highest`/`lowest(value, N)` | N + 1 bars (N prior bars plus the evaluating bar) |
| `zscore(series, N)` | N bars |
| `correlation(a, b, N)` | N bars |
| `beta(a, b, N)` | N bars |
| `percentile_rank(value, N)` | N bars |
| `confirm_ratio(signal, …, N)` | N+1 bars |
| `vwap_session(stream, h)` | resets daily at hour h |
| `session_range_*(stream, …)` | until the first window completes |

During warmup the indicator returns `null`. Comparisons with `null` are `false` — your rule won't fire, but it won't crash either.

The DSL compiler **automatically infers warmup requirements** from your indicator calls and tells the engine to discard pre-warmup signals. To declare a custom warmup window explicitly, use the per-stream `WARMUP N BARS` clause in `SYMBOLS` (see [streams](streams.md)):

```qkt
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m WARMUP 200 BARS
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
