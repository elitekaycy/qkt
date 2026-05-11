# Phase 5 — Indicator catalog + rule framework

## Summary

Phase 5 ships 8 technical-analysis indicators (SMA, EMA, WMA, RSI, MACD, ATR, Bollinger Bands, VWAP) and a composable `Rule` framework for boolean strategy conditions. Indicators are passive value objects — strategies pull values and call `update()` directly; no event-bus subscription. Rules compose via infix operators (`gt`, `lt`, `and`, `or`) so strategy code reads like prose: `(ema9 gt ema21) and (rsi lt Money.of("70"))`.

## What's new

### Indicator core

- `IndicatorOutput` (read interface):
  - `value(): BigDecimal?` — current value, or `null` if not warm
  - `isReady: Boolean`
  - `warmupBars: Int`
- `Indicator<TIn>` (write SPI):
  - `update(input: TIn)` — feed a `BigDecimal`, `Candle`, or `Tick` depending on the indicator

### Indicator catalog

| indicator | input | description |
| --- | --- | --- |
| `SMA(period)` | `BigDecimal` (close) | Simple moving average |
| `EMA(period)` | `BigDecimal` | Exponential moving average; seeds with SMA-of-first-N |
| `WMA(period)` | `BigDecimal` | Linearly weighted moving average |
| `RSI(period)` | `BigDecimal` | Wilder's smoothing; bounded [0, 100] |
| `MACD(12, 26, 9)` | `BigDecimal` | `value()` = MACD line; `lines()` = (macd, signal, histogram) |
| `ATR(period)` | `Candle` | Wilder's true range — needs high/low/close |
| `BollingerBands(20, 2.0)` | `BigDecimal` | `value()` = middle (SMA); `bands()` = (upper, middle, lower) |
| `VWAP(period)` | `Tick` | Rolling N-tick window; needs volume |

### Rule framework

- `Rule` sealed class with `evaluate(): Boolean`
- Variants: `Over`, `Under`, `Eq`, `OverThreshold`, `UnderThreshold`, `And`, `Or`, `Not`
- Infix operators:
  - `IndicatorOutput gt IndicatorOutput` → `Over`
  - `IndicatorOutput lt IndicatorOutput` → `Under`
  - `IndicatorOutput eq IndicatorOutput` → `Eq`
  - `IndicatorOutput gt BigDecimal` → `OverThreshold`
  - `Rule and Rule`, `Rule or Rule`, `Not(Rule)` — composition
- Safety: returns `false` if any underlying indicator isn't ready (no exceptions during warmup)

### Sample strategy

- `EmaCrossoverStrategy(fastPeriod, slowPeriod)` — fast EMA, slow EMA, manual edge-detection tracking, BUY on cross-up

## Migration

Pure addition — Phase 4's `Engine`, `EventBus`, `TradingPipeline`, `Backtest` are unchanged. `Main.kt` continues to use `EveryNthTickBuyStrategy` (demo strategy from Phase 1). Indicators are passive — they don't subscribe to events; strategies own the `update()` call.

## Usage cookbook

### Simple usage — one indicator, one symbol

```kotlin
class TrendStrategy : Strategy {
    private val ema9 = EMA(period = 9)
    private val ema21 = EMA(period = 21)
    private var wasAbove = false

    override fun onCandle(candle: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        ema9.update(candle.close)
        ema21.update(candle.close)
        if (!ema9.isReady || !ema21.isReady) return

        val above = ema9.value()!! > ema21.value()!!
        if (above && !wasAbove) {
            emit(Signal.Buy(candle.symbol, BigDecimal("0.1")))
        }
        wasAbove = above
    }
}
```

### Multi-symbol via per-symbol map

Phase 5 doesn't ship `IndicatorMap` (that lands in Phase 6) — manual map for now:

```kotlin
class MultiSymbolStrategy(private val symbols: List<String>) : Strategy {
    private val emas = symbols.associateWith { EMA(period = 20) }

    override fun onCandle(candle: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        emas[candle.symbol]?.let { ema ->
            ema.update(candle.close)
            if (ema.isReady && candle.close > ema.value()!!) {
                emit(Signal.Buy(candle.symbol, BigDecimal("0.1")))
            }
        }
    }
}
```

### Composed rule

```kotlin
val ema9 = EMA(9)
val ema21 = EMA(21)
val rsi = RSI(14)

val entryRule: Rule = (ema9 gt ema21) and (rsi lt Money.of("70"))

override fun onCandle(c: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
    ema9.update(c.close); ema21.update(c.close); rsi.update(c.close)
    if (entryRule.evaluate()) {
        emit(Signal.Buy(c.symbol, BigDecimal("0.1")))
    }
}
```

The `Rule` returns `false` if any of the three indicators isn't warm — your strategy never crashes during warmup.

### Custom indicator

```kotlin
class HighestHigh(private val period: Int) : Indicator<Candle> {
    private val window = ArrayDeque<BigDecimal>()
    override fun update(input: Candle) {
        window.addLast(input.high)
        while (window.size > period) window.removeFirst()
    }
    override fun value(): BigDecimal? = if (window.size < period) null else window.max()
    override val isReady: Boolean get() = window.size >= period
    override val warmupBars: Int get() = period
}
```

Implement `Indicator<TIn>`, expose `value()` / `isReady` / `warmupBars`. The framework doesn't care about anything else.

## Testing patterns

- **Hand-compute expected values** for short input sequences and assert exact `BigDecimal` equality
- **Warmup tests**: drive `period - 1` updates, assert `!isReady`; one more, assert `isReady`
- **Invariants**: monotonicity for RSI (bounded), ATR strictly positive, EMA-of-constants = constant, etc.
- Per-indicator test counts: ~25-30 per indicator, ~8 for `Rule`, ~3 for `EmaCrossoverStrategy`

## Known limitations

- **No `Rule.CrossedUp` / `Rule.CrossedDown`** — edge detection is deferred. Strategies track previous state manually. The DSL in Phase 11 introduces `CROSSES ABOVE` / `CROSSES BELOW` as proper edge-triggered operators.
- **No central indicator registry** — strategies own their indicators. Multi-symbol cases need manual maps (Phase 6 adds `IndicatorMap`).
- **Indicators don't subscribe to events** — strategies pull values. This is a deliberate design choice: keeps indicators stateless w.r.t. the bus and testable in isolation.
- **No session-anchored VWAP** — only rolling N-tick. Phase 11+ may add session anchors.
- **No multi-output destructuring** — `MACD.lines()` returns a triple; cleanup deferred.

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase5-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase5-design.md)
