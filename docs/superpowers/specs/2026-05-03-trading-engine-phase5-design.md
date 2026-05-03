# qkt — Trading Engine Phase 5 Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 5 of the qkt trading platform. Adds a stateful, incremental indicator catalog (8 indicators) and a `Rule` framework with infix operators for composing trading conditions. Strategies own and update their own indicators; rules read indicator outputs and yield boolean predicates that drive signal emission. A new sample strategy (`EmaCrossoverStrategy`) demonstrates indicator + rule composition end-to-end. No engine, bus, risk, or backtest changes.
**Phase 4 baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase4-design.md`](2026-05-03-trading-engine-phase4-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 1 shipped the core engine. Phase 2a/2b shipped the event bus, multi-strategy, candles. Phase 3/3b shipped risk + positions + P&L on a `BigDecimal` foundation. Phase 4 shipped the strategy backtest harness. Phase 5 ships indicators — the technical-analysis layer that makes strategies expressive enough to be worth backtesting.

Indicator design principle: **indicators are stateful, incremental, and pure functions of their input sequence.** Stateful so they can be updated tick-by-tick or bar-by-bar without recomputing from scratch. Incremental so the engine remains O(1) per event. Pure (no clocks, no randomness, no external state) so backtest replay determinism is automatic.

Rule design principle: **rules read, never write.** A rule never emits a signal directly; it returns `Boolean`. The strategy decides what to do with that boolean. This keeps rules side-effect free and makes them safe to compose into the future DSL.

This document is **Phase 5 only**.

## 2. Phase 5 — what we are building

A technical-indicator catalog plus a rule-composition framework. Concrete deliverables:

1. **`IndicatorOutput`** — read SPI in `com.qkt.indicators`. `value(): BigDecimal?` (null when not ready), `isReady: Boolean`, `warmupBars: Int`. The interface that `Rule` consumes.

2. **`Indicator<TIn> : IndicatorOutput`** — write SPI in `com.qkt.indicators`. Adds `update(input: TIn)`. Read/write split mirrors `PositionProvider`/`PositionTracker` from Phase 3.

3. **8-indicator catalog** in `com.qkt.indicators.catalog`:
   - `SMA(period: Int) : Indicator<BigDecimal>` — arithmetic mean of last N values.
   - `EMA(period: Int) : Indicator<BigDecimal>` — exponential moving average, SMA-of-first-N seed.
   - `WMA(period: Int) : Indicator<BigDecimal>` — linearly weighted moving average.
   - `RSI(period: Int) : Indicator<BigDecimal>` — Wilder's smoothed RSI on closes.
   - `MACD(fast: Int = 12, slow: Int = 26, signal: Int = 9) : Indicator<BigDecimal>` — MACD line, signal line, histogram. `value()` returns the MACD line; `lines()` returns the structured triple.
   - `ATR(period: Int) : Indicator<Candle>` — Wilder's average true range over OHLC.
   - `BollingerBands(period: Int = 20, stddevK: Double = 2.0) : Indicator<BigDecimal>` — `value()` returns the middle (SMA); `bands()` returns `(upper, middle, lower)`.
   - `VWAP(period: Int) : Indicator<Tick>` — rolling volume-weighted average price over the last N ticks.

4. **`Rule`** — sealed class in `com.qkt.indicators` with `evaluate(): Boolean`. Variants: `Over`, `Under`, `OverThreshold`, `UnderThreshold`, `Eq`, `And`, `Or`, `Not`. Infix operators: `gt`, `lt`, `eq`, `and`, `or`. Unary `!` (operator `not`). Threshold overloads accept `BigDecimal` directly. `evaluate()` returns `false` when any underlying indicator is not ready.

5. **`EmaCrossoverStrategy`** — new sample strategy in `com.qkt.strategy`. Buys on golden cross (fast EMA crosses above slow EMA), sells on death cross. Demonstrates indicator + rule composition. Tracks crossover state internally (`lastFastAboveSlow: Boolean?`).

6. **Tests** — hand-computed expected values + invariant tests per indicator (~3-4 per indicator × 8 = ~25-30 tests), Rule tests (~8), `EmaCrossoverStrategyTest` (~3). Total ~36-41 new tests.

End condition: 116 → ~152-157 tests, all green. `./gradlew run` output unchanged (Main.kt demo unchanged). Phase 4 acceptance criteria still hold.

**Phase 5 does NOT include** (deferred):

- `Rule.CrossedUp` / `Rule.CrossedDown` edge-detection rules — DSL phase will need these; sample strategy tracks crossover state manually.
- Central `IndicatorEngine` registry — strategies own their own indicators.
- Indicator persistence across runs — strategies are constructed fresh per backtest; indicators come fresh with them.
- Multi-output indicator destructuring sugar — `BollingerBands.bands()` and `MACD.lines()` return data classes; no `componentN` overloading.
- Streaming-percentile or rolling-quantile indicators — not in the 8-indicator catalog.
- Coroutines / parallelism — Phase 8 (live infra).
- Cross-asset multi-symbol feed merging — Phase 6 (real data ingestion). Indicators are agnostic to feed shape; they work with whatever Phase 6 produces.
- Replacing `Main.kt` demo with `EmaCrossoverStrategy` — separate decision; Phase 5 ships both strategies, leaves Main on `EveryNthTickBuyStrategy`.
- ta4j as a test dependency — hand-computed expected values + invariants are sufficient.

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Phase 5 scope = indicator catalog + Rule framework + sample strategy.** No engine, bus, risk, or backtest changes. | Indicators are a self-contained layer above the existing event pipeline. Strategies opt in by holding indicator fields; nothing about the engine changes. Tight scope keeps the phase shippable. |
| D2 | **8-indicator catalog: SMA, EMA, WMA, RSI, MACD, ATR, BollingerBands, VWAP.** | Covers trend (MAs, MACD), momentum (RSI), volatility (ATR, BB), and volume (VWAP). Standard TA-101. Larger catalogs are easy to extend later; smaller catalogs leave gaps a real strategy would hit immediately. |
| D3 | **Read/write SPI split: `IndicatorOutput` (read) + `Indicator<TIn>` (write).** | Mirrors `PositionProvider` / `PositionTracker` from Phase 3. Rules only need the read side. Lets rules compose indicators with different input types (`BigDecimal`, `Candle`, `Tick`) uniformly. |
| D4 | **Generic `Indicator<TIn>`.** Input types: `BigDecimal` (price-based), `Candle` (OHLC-based), `Tick` (price+volume). | Different indicators need different inputs. Generics force the strategy to wire each indicator with the right input — compile error if SMA gets a Candle. Type safety where it matters. |
| D5 | **`value(): BigDecimal?` returns null until ready.** `isReady` and `warmupBars` exposed on `IndicatorOutput`. | Three-state output (ready value / not-ready null) is the standard shape. Strategies check `isReady` when they care about transitions; rules treat null as "not ready → false". |
| D6 | **`Rule.evaluate(): Boolean` returns false on any not-ready underlying indicator.** | Strategies write `if (rule.evaluate()) emit(buy)`. With the false-on-not-ready convention, this just-works during warmup — no signals fire until indicators are ready. AND/OR short-circuit naturally. False is the safe default; null/exceptions force ceremony at every call site. |
| D7 | **Strategies own and update their own indicators.** No central `IndicatorEngine`. | Each strategy's indicators are its private state. Indicators are cheap to construct and have no global identity. A central registry would force naming/lifecycle concerns the DSL hasn't asked for yet. YAGNI. |
| D8 | **Indicators are stateful + incremental.** Each `update(input)` is O(1) (or O(period) at worst for MA window). No "recompute from history" path. | Live trading and backtest both run tick-by-tick. Recomputing a 20-bar SMA from scratch on every bar would be O(n²) over a session. Incremental state is the only sane default. |
| D9 | **BigDecimal precision: `MathContext.DECIMAL64` internally; scale=8 at output via `Money.SCALE`. VWAP and Bollinger stddev use `MathContext.DECIMAL128` internally for accumulator stability.** | DECIMAL64 (16 digits) is the engine's standard; matches `Money.MATH_CONTEXT`. VWAP's running `Σ(price·volume)` and BB's variance accumulator can lose precision over long windows; bumping internal precision to DECIMAL128 (34 digits) is essentially free per-bar and avoids drift. Output truncation at scale=8 keeps storage and comparison consistent. |
| D10 | **EMA seeds with SMA-of-first-N.** `warmupBars = period`. | Matches TradingView, ThinkOrSwim, ta4j. When users compare backtest EMA to a chart, values agree. The 1-bar-faster warmup of "first-close seed" isn't worth the divergence. |
| D11 | **RSI uses Wilder's smoothing.** ATR uses Wilder's smoothing. | Industry standard. Matches every charting platform. |
| D12 | **MACD: 12/26/9 default. `value()` returns MACD line. `lines(): MACDLines` returns `(macd, signal, histogram)`.** | 12/26/9 is the canonical Appel default. `value()` exposes the most common single-number reading; `lines()` exposes the rest for strategies that need histogram crossovers. Single struct return avoids three indicators ticking in lockstep. |
| D13 | **Bollinger Bands: 20-period, 2.0 stddev default. `value()` returns middle band (SMA). `bands(): BollingerBandValues` returns `(upper, middle, lower)`.** | Matches the canonical Bollinger publication. `value()` exposes the centerline as the "primary" output for Rule composition; `bands()` exposes the shoulders for breakout strategies. |
| D14 | **VWAP: rolling N-tick window.** Not session-anchored. | Session-anchored VWAP requires session-boundary state (when did "today" start?), which is a calendar concern that doesn't exist in Phase 5. Rolling N is a clean default; session anchoring is a Phase 6+ extension when calendars enter the model. |
| D15 | **Sample strategy: `EmaCrossoverStrategy` (fast=9, slow=21, size=1).** Tracks crossover state internally (`lastFastAboveSlow: Boolean?`). | The classic textbook example. Demonstrates Indicator + Rule + state-machine in one strategy. Lives in `com.qkt.strategy` alongside `EveryNthTickBuyStrategy`. |
| D16 | **`Main.kt` demo unchanged.** Phase 5 adds `EmaCrossoverStrategy` to the catalog but does not wire it into Main. | Main has been the Phase 4 acceptance demo (3 FILLED + 7 REJECTED). Replacing it changes the observable behavior of `./gradlew run`. Switching the demo is a separate decision. |
| D17 | **Test strategy: hand-computed expected values + invariant tests. No ta4j test dependency.** | Hand-computed values force deep understanding of each algorithm before implementing it (TDD). Invariant tests (SMA of constants = constant; RSI ∈ [0,100]; BB upper ≥ middle ≥ lower; etc.) catch broad bugs cheaply. ta4j as oracle is fine for one-off validation during development but doesn't belong as a permanent test dep. |
| D18 | **Indicators do NOT subscribe to events.** Strategies pull values out of ticks/candles and call `indicator.update(...)` directly. | Subscribing every indicator to the bus would couple them to the event system, complicate construction, and add registration order concerns. Strategies are already the place that decides "when do I act on this event"; indicators are passive collaborators. |
| D19 | **Strategies must be constructed fresh per backtest run.** Documented in Phase 5; `Backtest` already does this in Phase 4. | Indicators hold per-instance state. Reusing a strategy across runs would leak state. Convention is enforced by the existing `Backtest(strategies, ...)` API which takes pre-constructed strategies for a single run. |
| D20 | **No `Rule.CrossedUp` / `Rule.CrossedDown` in Phase 5.** Edge detection happens in strategy code (manual `lastFastAboveSlow` tracking). | Edge-detection rules need to track previous comparison state — a Rule with internal state breaks the "rules are pure" model. Cleanest fix is a separate concept (`StatefulRule` or `Trigger`) which the DSL phase will design properly. |

## 4. Architecture

### 4.1 SPI shape

```kotlin
package com.qkt.indicators

interface IndicatorOutput {
    fun value(): BigDecimal?
    val isReady: Boolean
    val warmupBars: Int
}

interface Indicator<TIn> : IndicatorOutput {
    fun update(input: TIn)
}
```

Concrete indicators implement `Indicator<TIn>`. `value()` returns the indicator's primary scalar reading (BigDecimal, scale=8). `isReady` flips true once `warmupBars` updates have been received. Multi-output indicators (MACD, BollingerBands) expose additional accessors (`lines()`, `bands()`) returning data classes.

### 4.2 Rule SPI

```kotlin
package com.qkt.indicators

sealed class Rule {
    abstract fun evaluate(): Boolean

    infix fun and(other: Rule): Rule = And(this, other)
    infix fun or(other: Rule): Rule = Or(this, other)
    operator fun not(): Rule = Not(this)

    data class Over(val left: IndicatorOutput, val right: IndicatorOutput) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l > r
        }
    }
    data class Under(val left: IndicatorOutput, val right: IndicatorOutput) : Rule() { /* l < r */ }
    data class Eq(val left: IndicatorOutput, val right: IndicatorOutput) : Rule()    { /* l.compareTo(r) == 0 */ }
    data class OverThreshold(val left: IndicatorOutput, val threshold: BigDecimal) : Rule()  { /* l > threshold */ }
    data class UnderThreshold(val left: IndicatorOutput, val threshold: BigDecimal) : Rule() { /* l < threshold */ }
    data class And(val a: Rule, val b: Rule) : Rule() { override fun evaluate() = a.evaluate() && b.evaluate() }
    data class Or(val a: Rule, val b: Rule)  : Rule() { override fun evaluate() = a.evaluate() || b.evaluate() }
    data class Not(val r: Rule) : Rule() { override fun evaluate() = !r.evaluate() }
}

infix fun IndicatorOutput.gt(other: IndicatorOutput): Rule = Rule.Over(this, other)
infix fun IndicatorOutput.lt(other: IndicatorOutput): Rule = Rule.Under(this, other)
infix fun IndicatorOutput.eq(other: IndicatorOutput): Rule = Rule.Eq(this, other)
infix fun IndicatorOutput.gt(threshold: BigDecimal): Rule = Rule.OverThreshold(this, threshold)
infix fun IndicatorOutput.lt(threshold: BigDecimal): Rule = Rule.UnderThreshold(this, threshold)
```

Composition example:
```kotlin
val ema9 = EMA(9)
val ema21 = EMA(21)
val rsi = RSI(14)
val rule = (ema9 gt ema21) and (rsi lt Money.of("70"))
if (rule.evaluate()) emit(Signal.Buy(symbol, size))
```

### 4.3 Indicator update flow (one strategy, mixed sources)

```
TickEvent → strategy.onTick(tick)
            → vwap.update(tick)                  (Indicator<Tick>)

CandleEvent → strategy.onCandle(candle)
              → ema9.update(candle.close)        (Indicator<BigDecimal>)
              → ema21.update(candle.close)
              → rsi.update(candle.close)
              → atr.update(candle)               (Indicator<Candle>)
              → if (rule.evaluate()) emit(...)
```

Indicators are passive: they don't subscribe to anything. The strategy is the integrator. This keeps indicators trivially testable (call `update()` in a loop, assert `value()`) and free of bus concerns.

### 4.4 Sample strategy: EmaCrossoverStrategy

```kotlin
class EmaCrossoverStrategy(
    private val symbol: String,
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val size: BigDecimal = Money.of("1"),
) : Strategy {
    private val fast = EMA(fastPeriod)
    private val slow = EMA(slowPeriod)
    private var lastFastAboveSlow: Boolean? = null

    override fun onTick(tick: Tick, emit: (Signal) -> Unit) { /* no-op; bar-driven */ }

    override fun onCandle(candle: Candle, emit: (Signal) -> Unit) {
        if (candle.symbol != symbol) return
        fast.update(candle.close)
        slow.update(candle.close)
        if (!fast.isReady || !slow.isReady) return

        val fastAbove = fast.value()!! > slow.value()!!
        val prev = lastFastAboveSlow
        lastFastAboveSlow = fastAbove
        if (prev == null) return  // no transition on first ready bar

        if (!prev && fastAbove) emit(Signal.Buy(symbol, size))
        if (prev && !fastAbove) emit(Signal.Sell(symbol, size))
    }
}
```

Edge detection (golden/death cross) uses local boolean state. A future `Rule.CrossedUp(fast, slow)` would replace this; deferred.

### 4.5 Component dependencies (Phase 5 delta)

```
indicators/Indicator.kt         ──▶ common (Money for scale)
indicators/Rule.kt              ──▶ indicators/Indicator.kt, common (Money)
indicators/catalog/SMA.kt       ──▶ indicators/Indicator.kt, common
indicators/catalog/EMA.kt       ──▶ indicators/Indicator.kt, indicators/catalog/SMA.kt, common
indicators/catalog/WMA.kt       ──▶ indicators/Indicator.kt, common
indicators/catalog/RSI.kt       ──▶ indicators/Indicator.kt, common
indicators/catalog/MACD.kt      ──▶ indicators/Indicator.kt, indicators/catalog/EMA.kt, common
indicators/catalog/ATR.kt       ──▶ indicators/Indicator.kt, marketdata (Candle), common
indicators/catalog/BollingerBands.kt ──▶ indicators/Indicator.kt, indicators/catalog/SMA.kt, common
indicators/catalog/VWAP.kt      ──▶ indicators/Indicator.kt, marketdata (Tick), common
strategy/EmaCrossoverStrategy.kt ──▶ strategy (Strategy, Signal), indicators/catalog/EMA.kt, marketdata, common
```

No new top-level packages outside `com.qkt.indicators`. No cycles. `marketdata` already depended on by `strategy`; no new cross-package edges.

### 4.6 What changes from Phase 4

- New: `indicators/Indicator.kt`, `indicators/Rule.kt`.
- New: 8 files in `indicators/catalog/`.
- New: `strategy/EmaCrossoverStrategy.kt`.
- New tests: `indicators/RuleTest.kt`, 8 catalog tests, `strategy/EmaCrossoverStrategyTest.kt`.

### 4.7 What does NOT change

- `Engine`, `EventBus`, all event types — unchanged.
- `Strategy` interface, `Signal`, `EveryNthTickBuyStrategy` — unchanged.
- `RiskRule`, `RiskEngine`, `MaxPositionSize`, `MaxOpenPositions` — unchanged.
- `MarketPriceProvider`, `PositionTracker`, `PnLCalculator`, `MockBroker`, `Money`, `Clock`/`FixedClock`, `IdGenerator`, `SequenceGenerator` — unchanged.
- `MockTickFeed`, `HistoricalTickFeed`, `OrderFactory.toOrder` — unchanged.
- `CandleAggregator`, `TimeWindow` — unchanged.
- `TradingPipeline`, `Backtest`, `BacktestResult`, `TradeRecord` — unchanged.
- `Main.kt` — unchanged.
- `EndToEndTest.kt` — unchanged.
- All Phase 1+2a+2b+3+3b+4 tests — pass unchanged.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/                                        # unchanged
├── broker/                                     # unchanged
├── bus/                                        # unchanged
├── candles/                                    # unchanged
├── common/                                     # unchanged
├── engine/                                     # unchanged
├── events/                                     # unchanged
├── execution/                                  # unchanged
├── indicators/                                 # NEW package
│   ├── Indicator.kt                            # NEW (IndicatorOutput + Indicator<TIn>)
│   ├── Rule.kt                                 # NEW (sealed Rule + infix operators)
│   └── catalog/                                # NEW subpackage
│       ├── ATR.kt                              # NEW
│       ├── BollingerBands.kt                   # NEW
│       ├── EMA.kt                              # NEW
│       ├── MACD.kt                             # NEW
│       ├── RSI.kt                              # NEW
│       ├── SMA.kt                              # NEW
│       ├── VWAP.kt                             # NEW
│       └── WMA.kt                              # NEW
├── marketdata/                                 # unchanged
├── pnl/                                        # unchanged
├── positions/                                  # unchanged
├── risk/                                       # unchanged
└── strategy/
    ├── EmaCrossoverStrategy.kt                 # NEW
    ├── EveryNthTickBuyStrategy.kt              # unchanged
    ├── Signal.kt                               # unchanged
    └── Strategy.kt                             # unchanged

src/test/kotlin/com/qkt/
├── indicators/
│   ├── RuleTest.kt                             # NEW
│   └── catalog/
│       ├── ATRTest.kt                          # NEW
│       ├── BollingerBandsTest.kt               # NEW
│       ├── EMATest.kt                          # NEW
│       ├── MACDTest.kt                         # NEW
│       ├── RSITest.kt                          # NEW
│       ├── SMATest.kt                          # NEW
│       ├── VWAPTest.kt                         # NEW
│       └── WMATest.kt                          # NEW
└── strategy/
    └── EmaCrossoverStrategyTest.kt             # NEW
```

## 6. Algorithms (canonical formulas)

### 6.1 SMA(N)
```
value = (x[t-N+1] + ... + x[t]) / N
isReady when count >= N
```
Implementation: rolling window of last N values; sum maintained incrementally as values shift in/out.

### 6.2 EMA(N)
```
α = 2 / (N + 1)
seed (at bar N): EMA = SMA of first N values
thereafter: EMA = α · close + (1 - α) · EMA_prev
isReady when count >= N
warmupBars = N
```

### 6.3 WMA(N)
```
weights: 1, 2, ..., N (most recent has weight N)
denom = N · (N + 1) / 2
value = Σ(w[i] · x[i]) / denom
isReady when count >= N
```

### 6.4 RSI(N) — Wilder
```
gain[t] = max(0, close[t] - close[t-1])
loss[t] = max(0, close[t-1] - close[t])
seed avgGain, avgLoss as simple averages of first N gains/losses
thereafter (Wilder smoothing):
  avgGain = (avgGain · (N-1) + gain[t]) / N
  avgLoss = (avgLoss · (N-1) + loss[t]) / N
RS = avgGain / avgLoss
RSI = 100 - 100 / (1 + RS)
edge: avgLoss = 0 → RSI = 100
isReady when N+1 closes seen (need N differences)
warmupBars = N + 1
```

### 6.5 MACD(fast, slow, signal)
```
emaFast = EMA(fast).update(close)
emaSlow = EMA(slow).update(close)
macdLine = emaFast - emaSlow         (defined once both EMAs ready)
signalLine = EMA(signal) over macdLine values
histogram = macdLine - signalLine
isReady when signalLine is ready
warmupBars = slow + signal - 1   (slow EMA warmup, then signal EMA warmup over the difference series)
value() returns macdLine
lines(): MACDLines(macd, signal, histogram)
```

### 6.6 ATR(N) — Wilder
```
trueRange[t] = max(
    high[t] - low[t],
    |high[t] - close[t-1]|,
    |low[t]  - close[t-1]|
)
seed ATR as simple average of first N true ranges
thereafter (Wilder):
  ATR = (ATR_prev · (N-1) + trueRange[t]) / N
isReady when N+1 candles seen
warmupBars = N + 1
```

### 6.7 BollingerBands(N, k)
```
middle = SMA(N)
stddev = sqrt( Σ(x[i] - middle)² / N )       (population stddev; N divisor, not N-1)
upper = middle + k · stddev
lower = middle - k · stddev
isReady when SMA ready
warmupBars = N
value() returns middle
bands(): BollingerBandValues(upper, middle, lower)
```
Internal precision: `MathContext.DECIMAL128` for the variance accumulator. `BigDecimal.sqrt(MathContext.DECIMAL64)` for the final stddev (DECIMAL64 for output consistency).

### 6.8 VWAP(N) — rolling
```
window: last N (price, volume) pairs
numerator = Σ(price[i] · volume[i])
denominator = Σ volume[i]
value = numerator / denominator
isReady when count >= N
edge: denominator = 0 → value() returns null (not ready in spirit)
```
Internal precision: `MathContext.DECIMAL128` for both accumulators.

## 7. Test plan

For each indicator:
1. **Hand-computed happy path.** Feed a known short series; assert `value()` matches a pen-and-paper computation. Series length = `warmupBars + 2` typically.
2. **Not-ready before warmup.** Feed `warmupBars - 1` updates; assert `value() == null` and `isReady == false`.
3. **At least one invariant.** E.g.:
   - SMA: SMA(N) of N copies of X equals X.
   - EMA: EMA on a constant series converges to the constant.
   - WMA: WMA(N) of N copies of X equals X.
   - RSI: bounded `[0, 100]` for any input. RSI with all gains == 100. RSI with all losses == 0.
   - MACD: on a constant series, MACD line = 0 and histogram = 0.
   - ATR: > 0 for any series with non-zero range. = 0 for a flat series.
   - BollingerBands: `upper >= middle >= lower`. Width = 0 when all values equal.
   - VWAP: equals arithmetic mean when all volumes are equal.

Rule tests:
1. `gt` returns true when left > right.
2. `lt` returns true when left < right.
3. `eq` returns true when left == right.
4. Threshold overloads (`indicator gt BigDecimal`).
5. `and` short-circuits on false.
6. `or` short-circuits on true.
7. `!rule` inverts.
8. Rule returns `false` when any underlying indicator is not ready.

`EmaCrossoverStrategy` tests:
1. No signal during warmup.
2. Golden cross emits Buy.
3. Death cross emits Sell.

Total: ~36-41 new tests. Existing 116 → ~152-157.

## 8. Determinism

Indicators are pure functions of input sequence:
- No randomness.
- No clock reads.
- No external state (files, env, network).
- No global state.

Two runs with identical input sequences produce byte-identical indicator values. This makes Phase 4 backtest replay determinism free for Phase 5: as long as the input event stream is identical, indicator values are identical, rule evaluations are identical, signals are identical, trades are identical.

Strategy invariant (carried from Phase 4, reinforced here): **strategies must be constructed fresh per backtest run.** Indicators hold per-instance state; reusing a strategy would leak indicator state across runs. `Backtest(strategies, ...)` in Phase 4 already takes a list of pre-constructed strategies for a single run; this convention is sufficient and needs no API change.

## 9. Acceptance criteria

- 8 indicators implemented, each with hand-computed + invariant tests passing.
- `Rule` framework with infix operators (`gt`, `lt`, `eq`, `and`, `or`, `!`) and threshold overloads.
- `Rule.evaluate()` returns `false` when any underlying indicator is not ready.
- `EmaCrossoverStrategy` ships in `com.qkt.strategy`; not wired into `Main.kt`.
- Total test count: 116 → ~152-157, all green.
- `./gradlew run` output unchanged (Main.kt demo unchanged).
- `./gradlew build` clean (ktlint passes; no warnings).
- All Phase 1-4 tests still pass.

## 10. Out of scope (explicit deferrals)

- `Rule.CrossedUp` / `Rule.CrossedDown` — DSL phase will design stateful rules properly.
- Central `IndicatorEngine` registry — strategies own their indicators directly.
- Session-anchored VWAP — needs calendar concepts not yet in the model.
- ta4j as test dependency — hand-computed tests sufficient.
- Replacing `Main.kt` demo with `EmaCrossoverStrategy` — separate decision.
- More indicators (Stochastic, ADX, Ichimoku, Keltner, etc.) — extend catalog when a real strategy needs them.
- Multi-timeframe indicators (e.g., EMA on 5m candles while strategy reads 1m) — Phase 6+ when multi-symbol/multi-feed lands.
- Coroutines / parallelism — Phase 8.
- Cross-asset multi-symbol feeds — Phase 6.
