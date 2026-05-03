# qkt — Trading Engine Phase 3b Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 3b of the qkt trading platform. Adds realized + unrealized P&L computation alongside a project-wide migration from `Double` to `BigDecimal` for prices and quantities. Extends `Position` with `avgEntryPrice` and weighted-average update logic. New `PnLProvider` read interface + `PnLCalculator` writer in a new `com.qkt.pnl` package.
**Phase 3 baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase3-design.md`](2026-05-03-trading-engine-phase3-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 1 shipped the core engine. Phase 2a shipped the event bus + multi-strategy + SLF4J. Phase 2b shipped the candle aggregator. Phase 3 shipped risk + positions. Phase 3b ships P&L on a `BigDecimal` foundation.

The decision to migrate `Double` → `BigDecimal` *together with* the P&L feature (rather than separately) is deliberate: the P&L work is born numerically exact, and no "Double-era" decisions get baked into a permanent foundation.

This document is **Phase 3b only**.

## 2. Phase 3b — what we are building

Two coupled changes:

1. **`BigDecimal` migration.** Every monetary or quantity field across the codebase moves from `Double` to `BigDecimal`. A new `com.qkt.common.Money` object exposes `MathContext.DECIMAL64`, `SCALE = 8`, `RoundingMode.HALF_EVEN`, `ZERO`, and `of(String/Long/Int)` builders. Tests migrate assertions to `isEqualByComparingTo(Money.of("..."))`.

2. **P&L feature.** `Position` gains `avgEntryPrice: BigDecimal`. `PositionTracker.apply(trade): BigDecimal` returns realized P&L from the trade and updates the running position via weighted-average semantics. New `PnLProvider` (read interface) + `PnLCalculator` (writer) in `com.qkt.pnl` package compute realized total + unrealized per-symbol/total + total P&L. Realized P&L is fed via a single TradeEvent subscriber that calls `positions.apply` and forwards the returned value to `pnl.recordRealized`. Unrealized is pull-on-demand: `pnl.unrealizedFor(symbol)` reads positions + `MarketPriceProvider`.

User-visible: `./gradlew run` shows extended FILLED log lines with `(position, realized, unrealized)`.

End condition: 90 → 107 tests, all green. Phase 3 acceptance criteria still hold (3 FILLED + 7 REJECTED + Done.). Numeric values displayed via `stripTrailingZeros().toPlainString()` for clean log output.

**Phase 3b does NOT include** (deferred):

- Lot-by-lot tracking (FIFO/LIFO) — tax-accounting concern, future regulated phase
- Per-trade `RealizedPnLEvent` — `apply` return value sufficient
- Cumulative drawdown / peak P&L analytics — future analytics phase
- Multi-currency P&L — single-currency assumed
- P&L attribution by strategy / signal source — future analytics
- Slippage modeling in MockBroker — real-broker phase
- Position persistence across restarts — future
- Async / coroutines — post-Phase 5
- Run-end summary log line — could be added but not in scope

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Phase 3b scope = P&L feature + BigDecimal migration together.** | Doing them separately means either (a) P&L code is born wrong on Double then re-migrated, or (b) BigDecimal migration ships as a feature-less phase. Together = ~1.5 days of one-time work; the foundation is correct from day one. |
| D2 | **BigDecimal scope: prices AND quantities. Counts/indices stay Int/Long.** | Mixed-type arithmetic is a footgun (`BigDecimal price * Double quantity` requires conversion at every site). Real broker SDKs return decimals for both. Counts (max-open-positions, sequence ids) are genuinely integers. |
| D3 | **`com.qkt.common.Money` object.** `MathContext.DECIMAL64`, `SCALE = 8`, `RoundingMode.HALF_EVEN`, `ZERO`, `of(String/Long/Int)`. NO `of(Double)`. | DECIMAL64 matches real broker SDK conventions (8 dp covers crypto satoshi precision). HALF_EVEN (banker's rounding) eliminates the upward bias of HALF_UP. NO `of(Double)` forces callers (especially tests) to use string literals, avoiding the Double-imprecision-in-BigDecimal-constructor footgun. |
| D4 | **`Position(symbol, quantity, avgEntryPrice)`.** Average-cost (weighted-average) policy. Sells don't change avgEntryPrice. Flipping handled in single `apply` call. | Average cost is the universal trading-runtime convention; FIFO/LIFO is for tax reporting (separate concern). Flipping handled per-trade because brokers deliver flipping trades as one trade. |
| D5 | **`PositionTracker.apply(trade): BigDecimal`** returns realized P&L from this trade. Tracker stays single-responsibility. | The realized number exists during the apply call; capturing it in the return preserves it without adding internal accumulator state. New event types (`RealizedPnLEvent`) are redundant — the realized number is derivable from the trade. |
| D6 | **`com.qkt.pnl` package.** `PnLProvider` (read interface) + `PnLCalculator` (writer with `recordRealized` and pull-on-demand `unrealizedFor` / `unrealizedTotal` / `totalPnL`). | P&L is conceptually distinct from positions or risk. DSL queries P&L separately. Pull-on-demand for unrealized: stateless, deterministic, cheap (one map lookup + arithmetic per call). |
| D7 | **Single TradeEvent subscriber does positions + P&L update together, FIRST.** Subsequent subscribers (FILLED logger) read consistent post-trade state. | The realized number is an output of `apply`; capturing and forwarding it in the same handler is the natural shape. Separate subscribers can't access the return value. |
| D8 | **Lockstep migration: one production file + its test file per task.** | Granular commits aid review and bisecting. TDD discipline preserved. Avoids backwards-compat shims. Surfaces test-ergonomic patterns up front. |
| D9 | **`Signal.toOrder` extracted to `com.qkt.execution.OrderFactory.kt`** as a shared top-level function. | Phase 3b touches every Order construction; this is the right time to deduplicate the conversion logic between Main.kt and EndToEndTest.kt (a known carried-over issue). |
| D10 | **Display formatting via `stripTrailingZeros().toPlainString()`** for quantities/prices in log lines. P&L formatted at scale=2 for cents. | `1.00000000` is ugly. Display formatting is per-log-line; internal precision stays at SCALE=8. |

## 4. Architecture

### 4.1 Pipeline (Phase 3b)

```
[unchanged from Phase 3]
... TickEvent → SignalEvent → risk gate → OrderEvent | RiskRejectedEvent
... OrderEvent → broker → TradeEvent

TradeEvent subscribers (in order):
  1. positions.apply(trade) returns realized; pnl.recordRealized(realized)   ← FIRST: data updates
  2. FILLED logger reads positions + pnl                                      ← reads consistent state
  3. (any future trade observers)
```

`PnLCalculator` does NOT subscribe to anything. Pure query target.

### 4.2 One TradeEvent through the pipeline

```
TradeEvent (SELL XAUUSD 1.0 @ $120; orderId=ORD-7) arrives
└─ subscriber 1: data update
     1. realized = positions.apply(trade)
          - reads current = (qty=1, avg=$100)
          - SELL on long → realized = 1 × ($120 - $100) = $20
          - position closed; entry removed from map
          - returns BigDecimal("20.00000000")
     2. pnl.recordRealized(BigDecimal("20.00000000"))
          - realizedTotal = realizedTotal.add(20).setScale(8, HALF_EVEN)
└─ subscriber 2: FILLED log line
     reads positions.positionFor("XAUUSD") → null (closed)
     reads pnl.realizedTotal() → 20.00 (formatted at scale 2)
     reads pnl.unrealizedTotal() → 0.00 (no positions)
     log.info("FILLED: SELL 1 XAUUSD @ 120 (position: 0, realized: 20.00, unrealized: 0.00)")
```

### 4.3 Component dependencies (Phase 3b delta)

```
common/Money.kt        ──▶ (only stdlib BigDecimal/MathContext/RoundingMode)
positions/             ──▶ marketdata, common, execution     (types swap to BigDecimal)
risk/                  ──▶ positions, execution, common      (unchanged)
risk/rules/            ──▶ positions, execution              (types swap)
pnl/                   ──▶ positions, marketdata, common     (NEW package)
events/                ──▶ ... (unchanged; payload types swap)
execution/             ──▶ common (gains OrderFactory.kt — shared Signal.toOrder)
app/                   ──▶ ... pnl, execution.OrderFactory   (Main wires PnLCalculator)
```

New package `com.qkt.pnl`. New file `com.qkt.execution.OrderFactory.kt`. No cycles.

### 4.4 What changes from Phase 3

- `common/Money.kt` — NEW.
- `marketdata/Tick.kt`, `Candle.kt`, `MarketPriceProvider.kt`, `MockTickFeed.kt` — types migrated to BigDecimal.
- `execution/Order.kt`, `Trade.kt` — types migrated.
- `execution/OrderFactory.kt` — NEW (extracted `Signal.toOrder`).
- `strategy/Signal.kt`, `EveryNthTickBuyStrategy.kt` — types migrated.
- `broker/MockBroker.kt` — types migrated (price math via Money policy).
- `engine/Engine.kt` — types flow through; minimal logic change.
- `candles/CandleAggregator.kt` (incl. private MutableCandle) — types migrated.
- `positions/Position.kt` — gains `avgEntryPrice`; types migrated.
- `positions/PositionProvider.kt` (PositionTracker) — `apply(trade): BigDecimal` returns realized; weighted-average + flipping logic.
- `risk/rules/MaxPositionSize.kt` — `maxQty: BigDecimal`; arithmetic via Money policy.
- `pnl/PnLProvider.kt` — NEW (interface + PnLCalculator).
- `app/Main.kt` — wires PnLCalculator; single TradeEvent subscriber for both updates; extended FILLED log.

### 4.5 What does NOT change

- Event sealed-class structure (6 variants from Phase 3).
- Bus + dispatch + stamping.
- Strategy interface signatures (Tick/Candle types change but interface shape identical).
- All wiring invariants (aggregator before strategies; tracker update before logger reads).
- `risk/RiskEngine`, `risk/RiskRule`, `risk/Decision`, `risk/rules/MaxOpenPositions`.
- `common/Clock`, `common/IdGenerator` (and SequenceGenerator/MonotonicSequenceGenerator), `common/Side`.
- `broker/Broker` interface.
- `marketdata/TickFeed` interface.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/Main.kt                              # extended
├── broker/
│   ├── Broker.kt                            # unchanged
│   └── MockBroker.kt                        # migrated
├── bus/EventBus.kt                          # unchanged
├── candles/
│   ├── CandleAggregator.kt                  # migrated
│   └── TimeWindow.kt                        # unchanged
├── common/
│   ├── Clock.kt                             # unchanged
│   ├── IdGenerator.kt                       # unchanged
│   ├── Money.kt                             # NEW
│   └── Side.kt                              # unchanged
├── engine/Engine.kt                         # types pass through
├── events/Event.kt                          # unchanged structure (payloads' field types change)
├── execution/
│   ├── Order.kt                             # migrated
│   ├── OrderFactory.kt                      # NEW (extracted Signal.toOrder)
│   ├── OrderType.kt                         # unchanged
│   └── Trade.kt                             # migrated
├── marketdata/
│   ├── Candle.kt                            # migrated
│   ├── MarketPriceProvider.kt               # migrated
│   ├── MockTickFeed.kt                      # migrated
│   ├── Tick.kt                              # migrated
│   └── TickFeed.kt                          # unchanged (interface)
├── pnl/                                     # NEW
│   └── PnLProvider.kt                       # NEW (interface + PnLCalculator)
├── positions/
│   ├── Position.kt                          # migrated + gains avgEntryPrice
│   └── PositionProvider.kt                  # migrated + weighted-avg apply logic
├── risk/
│   ├── RiskEngine.kt                        # unchanged
│   └── RiskRule.kt                          # unchanged
├── risk/rules/
│   ├── MaxOpenPositions.kt                  # unchanged
│   └── MaxPositionSize.kt                   # migrated
└── strategy/
    ├── EveryNthTickBuyStrategy.kt           # migrated
    ├── Signal.kt                            # migrated
    └── Strategy.kt                          # unchanged

src/test/kotlin/com/qkt/
├── app/EndToEndTest.kt                      # migrated + 2 P&L tests (14 total)
├── broker/MockBrokerTest.kt                 # migrated
├── bus/EventBusTest.kt                      # unchanged
├── candles/
│   ├── CandleAggregatorTest.kt              # migrated
│   └── TimeWindowTest.kt                    # unchanged
├── common/
│   ├── MonotonicSequenceGeneratorTest.kt    # unchanged
│   └── MoneyTest.kt                         # NEW
├── engine/EngineTest.kt                     # migrated
├── marketdata/
│   ├── MarketPriceTrackerTest.kt            # migrated
│   └── MockTickFeedTest.kt                  # migrated
├── pnl/                                     # NEW
│   └── PnLCalculatorTest.kt                 # NEW (~8 tests)
├── positions/PositionTrackerTest.kt         # migrated + 4 new tests (10 total)
├── risk/
│   ├── RiskEngineTest.kt                    # unchanged
│   └── rules/
│       ├── MaxOpenPositionsTest.kt          # unchanged
│       └── MaxPositionSizeTest.kt           # migrated
└── strategy/EveryNthTickBuyStrategyTest.kt  # migrated
```

## 6. Type & interface signatures

### 6.1 `common.Money`

```kotlin
package com.qkt.common

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object Money {
    val CONTEXT: MathContext = MathContext.DECIMAL64
    const val SCALE: Int = 8
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SCALE, ROUNDING)

    fun of(value: String): BigDecimal = BigDecimal(value).setScale(SCALE, ROUNDING)
    fun of(value: Long): BigDecimal = BigDecimal.valueOf(value).setScale(SCALE, ROUNDING)
    fun of(value: Int): BigDecimal = BigDecimal.valueOf(value.toLong()).setScale(SCALE, ROUNDING)
}
```

### 6.2 `positions.Position`

```kotlin
data class Position(
    val symbol: String,
    val quantity: BigDecimal,
    val avgEntryPrice: BigDecimal,
)
```

### 6.3 `positions.PositionProvider`

Interface unchanged in shape; types swap. `apply` now returns realized P&L:

```kotlin
interface PositionProvider {
    fun positionFor(symbol: String): Position?
    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    fun apply(trade: Trade): BigDecimal      // returns realized P&L from this trade
    // ...
}
```

Full implementation in §6 of the brainstorm transcript and Section 2 of this spec.

### 6.4 `pnl.PnLProvider`

```kotlin
package com.qkt.pnl

import java.math.BigDecimal

interface PnLProvider {
    fun realizedTotal(): BigDecimal
    fun unrealizedFor(symbol: String): BigDecimal
    fun unrealizedTotal(): BigDecimal
    fun totalPnL(): BigDecimal
}

class PnLCalculator(
    private val positions: PositionProvider,
    private val prices: MarketPriceProvider,
) : PnLProvider {
    fun recordRealized(realized: BigDecimal)
    // ... see Section 2
}
```

### 6.5 `execution.OrderFactory`

```kotlin
package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
    is Signal.Buy  -> Order(id, symbol, Side.BUY,  size, OrderType.MARKET, null, ts)
    is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
}
```

Top-level extension. Imported by Main.kt and EndToEndTest.kt — no duplication.

### 6.6 Migrated data classes

`Tick`, `Candle`, `Order`, `Trade`, `Signal.Buy`, `Signal.Sell` — all numeric fields swap from `Double` to `BigDecimal`. Schema in Section 2 of brainstorm output.

### 6.7 Migrated rule

```kotlin
class MaxPositionSize(
    private val symbol: String,
    private val maxQty: BigDecimal,
) : RiskRule {
    init { require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" } }
    // arithmetic via Money policy
}
```

### 6.8 Extended Main.kt

See Section 2 — wires `PnLCalculator(positions, priceTracker)`, single TradeEvent subscriber doing `positions.apply` + `pnl.recordRealized`, FILLED log shows `(position, realized, unrealized)` formatted via `stripTrailingZeros()` and `setScale(2)`.

## 7. Build configuration changes

None. Phase 3b uses existing Java stdlib `BigDecimal`/`MathContext`/`RoundingMode`. No new dependencies.

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| `Money.of("not a number")` | `NumberFormatException` from JDK | Crash (caller bug) |
| `MaxPositionSize(maxQty.signum() <= 0)` | `require(...)` throws | Crash (bug) |
| `PositionTracker.apply` on a flipping trade | Realized P&L for closed portion + new position opens at trade price | Documented |
| `Signal.size.signum() <= 0` | Falls through; broker may reject via `require(quantity > 0)` | Documented |
| `BigDecimal` divide without explicit context | `ArithmeticException` from JDK on non-terminating decimal | Crash; we use `Money.CONTEXT` everywhere to avoid this |
| `BigDecimal(double)` constructor used somewhere | Inherits Double imprecision; subtle bugs in tests | Convention: NEVER use raw `BigDecimal(double)`; always `Money.of("...")` |
| Negative realized P&L (loss) | Stored as negative BigDecimal; `realizedTotal` accumulates correctly | Expected |
| `unrealizedFor("X")` when no position | Returns `Money.ZERO` | Documented |
| `unrealizedFor("X")` when no current price | Returns `Money.ZERO` | Documented (consistent with "we can't compute it") |
| Realized P&L on flipping = correct? | Tested: long 2@100, SELL 5@110 → realized = 2 × (110-100) = 20 | Tested explicitly in PositionTrackerTest |

No try/catch in production code. Phase 1 fail-fast policy continues.

## 9. Testing strategy

### 9.1 Test files

| File | Phase 3 → Phase 3b |
|---|---|
| `MoneyTest.kt` | NEW (~3 tests) |
| `MockTickFeedTest.kt` | migrated (8 tests; assertions only) |
| `MarketPriceTrackerTest.kt` | migrated (4 tests; assertions only) |
| `EveryNthTickBuyStrategyTest.kt` | migrated (5 tests; assertions only) |
| `MockBrokerTest.kt` | migrated (8 tests; assertions only) |
| `EngineTest.kt` | migrated (2 tests; assertions only) |
| `MonotonicSequenceGeneratorTest.kt` | unchanged (3 tests) |
| `EventBusTest.kt` | unchanged (12 tests) |
| `EndToEndTest.kt` | migrated + 2 new P&L tests (12 → 14) |
| `TimeWindowTest.kt` | unchanged (5 tests) |
| `CandleAggregatorTest.kt` | migrated (10 tests; assertions only) |
| `PositionTrackerTest.kt` | migrated + 4 new (avgEntryPrice, weighted-avg, partial close, flipping) (6 → 10) |
| `MaxPositionSizeTest.kt` | migrated (5 tests; assertions only) |
| `MaxOpenPositionsTest.kt` | unchanged (5 tests) |
| `RiskEngineTest.kt` | unchanged (5 tests) |
| `PnLCalculatorTest.kt` | NEW (~8 tests) |

### 9.2 New test cases (highlights)

**`PositionTrackerTest`** new cases:
- `weighted-average price updates on subsequent buys` — BUY 1@100, BUY 1@110, asserts avg=105
- `partial sell does not change avgEntryPrice and returns realized PnL` — BUY 2@100, SELL 1@110, asserts position(qty=1, avg=100), realized=10
- `full close removes position and returns realized PnL` — BUY 1@100, SELL 1@120, asserts positionFor null, realized=20
- `flipping trade realizes PnL on closed portion and opens new at trade price` — long 2@100, SELL 5@110 → realized=20, position=(qty=-3, avg=110)

**`PnLCalculatorTest`** cases:
- realizedTotal is zero on a fresh calculator
- recordRealized accumulates positive realized values
- recordRealized accumulates negative realized values (losses)
- unrealizedFor returns zero for unknown symbol
- unrealizedFor returns zero when no current price for symbol
- unrealizedFor computes (price - avg) * quantity for a long position
- unrealizedFor returns negative for a short position with rising price
- unrealizedTotal sums across all open symbols

**`EndToEndTest`** new cases:
- realized PnL accumulates after a closing trade
- unrealized PnL is visible after an open position

### 9.3 Total test count

| Source | Phase 3 | Phase 3b delta | Phase 3b total |
|---|---|---|---|
| (Phase 1+2a+2b unchanged in Phase 3b) | 57 | 0 | 57 |
| `MaxPositionSizeTest` | 5 | 0 | 5 |
| `MaxOpenPositionsTest` | 5 | 0 | 5 |
| `RiskEngineTest` | 5 | 0 | 5 |
| `PositionTrackerTest` | 6 | +4 | 10 |
| `EndToEndTest` | 12 | +2 | 14 |
| `MoneyTest` | 0 | +3 | 3 |
| `PnLCalculatorTest` | 0 | +8 | 8 |
| **Total** | **90** | **+17** | **107** |

(The 57 covers `MarketPriceTrackerTest` (4) + `MockTickFeedTest` (8) + `EveryNthTickBuyStrategyTest` (5) + `MockBrokerTest` (8) + `EngineTest` (2) + `MonotonicSequenceGeneratorTest` (3) + `EventBusTest` (12) + `TimeWindowTest` (5) + `CandleAggregatorTest` (10).)

### 9.4 Conventions

- AssertJ: `isEqualByComparingTo(Money.of("..."))` for BigDecimal value equality.
- `Money.of("...")` exclusively. NO raw `BigDecimal("...")` or `BigDecimal(double)` constructions.
- `assertThat(x).isNull()` for nullable BigDecimal (unchanged).
- `FixedClock`, `MonotonicSequenceGenerator`, fresh trackers/calculators per test class instance.
- ktlint enforced via `./gradlew check`.
- Real types only; anonymous `Strategy`/`Broker` impls for one-off needs.

### 9.5 Migration discipline (lockstep)

For each migration task: change tests first (assertions go red because production type still returns Double), change production class, run tests green, commit. This is a refactor pass — no new behavior introduced by the migration steps themselves; new behavior arrives in P&L-feature steps.

## 10. Build & run

Commands unchanged. `./gradlew run` output shape after Phase 3b:

```
[main] INFO Main - FILLED: BUY 1 XAUUSD @ 2382.18404227 (position: 1, realized: 0.00, unrealized: 0.00)
[main] INFO Main - CANDLE: XAUUSD O=... [..., ...)
[main] INFO Main - FILLED: BUY 1 XAUUSD @ 2377.52639754 (position: 2, realized: 0.00, unrealized: -4.66)
[main] INFO Main - FILLED: BUY 1 XAUUSD @ 2392.73817446 (position: 3, realized: 0.00, unrealized: 28.13)
[main] INFO Main - REJECTED: BUY 1 XAUUSD (MaxPositionSize: |4| > 3 for XAUUSD)
... 7 REJECTED ...
[main] INFO Main - Done.
```

Realized stays at $0 in the demo because the strategy only buys (no sell-side closes). Unrealized changes as price walks. Realized fires in tests via explicit sell trades.

## 11. Out of scope (deferred)

| Feature | Phase |
|---|---|
| Lot-by-lot tracking (FIFO/LIFO) | Future regulated phase |
| Per-trade `RealizedPnLEvent` | Already rejected |
| Cumulative drawdown / peak P&L analytics | Future analytics phase |
| Multi-currency P&L | Future |
| P&L attribution by strategy | Future analytics |
| Slippage modeling | Real-broker phase |
| Position persistence | Future |
| Run-end P&L summary log | Could be added; not in scope |
| Broker → BigDecimal interface contract for real-broker integration | Real-broker phase |
| Async / coroutines | post-Phase 5 |
| Backtest replay engine | Phase 4 |
| External DSL parser | Phase 5 |

## 12. Migration from Phase 3

Phase 3 is on `main`. Phase 3b lands on a feature branch (`phase3b-pnl-bigdecimal`), reviewed and merged.

Internal: every consumer of money fields recompiles after the migration. The lockstep migration + ktlint should catch any conversion oversights at compile time. Phase 3 tests pass with assertion-style updates.

## 13. Acceptance criteria (Phase 3b done means)

- [ ] `Money` object exists in `com.qkt.common` with `CONTEXT`, `SCALE`, `ROUNDING`, `ZERO`, `of(String/Long/Int)`.
- [ ] All money + quantity fields migrated to `BigDecimal` across `Tick`, `Candle`, `Order`, `Trade`, `Signal`, `Position`, `MarketPriceProvider`, etc.
- [ ] `Position` has `avgEntryPrice: BigDecimal`.
- [ ] `PositionTracker.apply(trade): BigDecimal` returns realized P&L; weighted-average + flipping logic correct.
- [ ] `PnLProvider`, `PnLCalculator` exist in `com.qkt.pnl`. `PnLCalculator` has `recordRealized` writer + pull-on-demand `unrealizedFor` / `unrealizedTotal` / `totalPnL` readers.
- [ ] `OrderFactory.kt` provides shared `Signal.toOrder` extension; Main.kt and EndToEndTest.kt both use it (no duplication).
- [ ] All 107 tests pass.
- [ ] `./gradlew build` green.
- [ ] `./gradlew run` produces 3 FILLED + 7 REJECTED + Done. with FILLED lines showing `(position, realized, unrealized)` formatted cleanly via `stripTrailingZeros()` and `setScale(2)`.
- [ ] Phase 3 acceptance criteria still hold (cross-symbol behavior, ordering invariants).
- [ ] `EveryNthTickBuyStrategy` continues unchanged in behavior (size now BigDecimal).
- [ ] `git log` clean history with conventional commit messages.

When all boxes checked, Phase 3b is complete on the `phase3b-pnl-bigdecimal` branch, ready for merge to `main`.
