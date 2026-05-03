# Trading Engine Phase 3b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate every monetary and quantity field from `Double` to `BigDecimal` across the codebase, then ship realized + unrealized P&L on that foundation. `Position` gains `avgEntryPrice`; `PositionTracker.apply(trade): BigDecimal` returns realized P&L with weighted-average + flipping semantics. New `PnLProvider`/`PnLCalculator` in `com.qkt.pnl` package compute realized total + per-symbol unrealized + total. The shared `Signal.toOrder` extension is extracted to `com.qkt.execution.OrderFactory.kt`.

**Architecture:** Single `Money` object in `com.qkt.common` exposes `MathContext.DECIMAL64`, `SCALE = 8`, `RoundingMode.HALF_EVEN`, `ZERO`, and `of(String/Long/Int)` builders (no `of(Double)`). All numeric literals route through `Money.of("...")`. Tests use AssertJ `isEqualByComparingTo(Money.of("..."))` for value equality. P&L is read/write split: `PnLProvider` interface for queries, `PnLCalculator` writer with `recordRealized`. Realized P&L feeds via the `apply` return value in a single TradeEvent subscriber that also calls `pnl.recordRealized`. Unrealized is pull-on-demand: `pnl.unrealizedFor(symbol)` reads positions + `MarketPriceProvider`.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. No new dependencies — `BigDecimal`/`MathContext`/`RoundingMode` are JDK stdlib.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase3b-design.md`](../specs/2026-05-03-trading-engine-phase3b-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase3b-pnl-bigdecimal`. Phase 3 is on `main` with 90 tests; Phase 3b spec is committed.

---

## Task ordering

```
1. Money + MoneyTest                                                      [TDD, foundation]
2. Full BigDecimal migration (all value types + consumers + tests)        [single coordinated commit]
3. Position + PositionTracker rewrite (avgEntryPrice + apply returns)     [TDD, +4 tests]
4. PnLProvider + PnLCalculator                                            [TDD, +8 tests]
5. OrderFactory extraction + Main.kt wiring + EndToEndTest +2             [integration]
6. Final verification                                                      [no new files]
```

6 tasks. Each ends in a commit. Task 2 is the largest — touches ~20 files in one coordinated migration. Tasks 3–4 are full TDD with new logic. Task 5 wires `PnLCalculator` and adds integration tests. Task 6 is verification.

After each task, expected test count:
- Task 1 done: 93 tests
- Task 2 done: 93 (assertions migrated; no behavior change)
- Task 3 done: 97 (+4 new PositionTrackerTest)
- Task 4 done: 105 (+8 new PnLCalculatorTest)
- Task 5 done: 107 (+2 new EndToEndTest)
- Task 6: no new tests; verify 107

---

## Task 1: Money + MoneyTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/common/MoneyTest.kt`
- Create: `src/main/kotlin/com/qkt/common/Money.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/common/MoneyTest.kt`:

```kotlin
package com.qkt.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `of(String) produces value with scale 8`() {
        val v = Money.of("2400.5")
        assertThat(v.scale()).isEqualTo(Money.SCALE)
        assertThat(v).isEqualByComparingTo(BigDecimal("2400.5"))
    }

    @Test
    fun `of(Long) and of(Int) produce values with scale 8`() {
        val long = Money.of(100L)
        val int = Money.of(42)
        assertThat(long.scale()).isEqualTo(Money.SCALE)
        assertThat(int.scale()).isEqualTo(Money.SCALE)
        assertThat(long).isEqualByComparingTo(BigDecimal("100"))
        assertThat(int).isEqualByComparingTo(BigDecimal("42"))
    }

    @Test
    fun `ZERO has scale 8 and value zero`() {
        assertThat(Money.ZERO.scale()).isEqualTo(Money.SCALE)
        assertThat(Money.ZERO).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.common.MoneyTest"`
Expected: compile failure — `Unresolved reference 'Money'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Create `Money.kt`**

`src/main/kotlin/com/qkt/common/Money.kt`:

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

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.common.MoneyTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 93 tests total. ktlint clean (run `ktlintFormat` if needed).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/common/Money.kt src/test/kotlin/com/qkt/common/MoneyTest.kt
git commit -m "feat(common): add Money helper with MathContext and Money.of builders"
```

---

## Task 2: Full BigDecimal migration

This task migrates every monetary or quantity field from `Double` to `BigDecimal` across all production files AND updates every test file's assertions and constructions. Single coordinated commit because the dependency graph means partial migrations break the build (e.g., migrating `Tick.price` to `BigDecimal` requires every consumer of `tick.price` to update simultaneously).

**Files modified (production):**
- `src/main/kotlin/com/qkt/marketdata/Tick.kt`
- `src/main/kotlin/com/qkt/marketdata/Candle.kt`
- `src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt`
- `src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt`
- `src/main/kotlin/com/qkt/execution/Order.kt`
- `src/main/kotlin/com/qkt/execution/Trade.kt`
- `src/main/kotlin/com/qkt/strategy/Signal.kt`
- `src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt`
- `src/main/kotlin/com/qkt/broker/MockBroker.kt`
- `src/main/kotlin/com/qkt/candles/CandleAggregator.kt`
- `src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt`
- `src/main/kotlin/com/qkt/positions/Position.kt` (only `quantity` migrates here; avgEntryPrice added in Task 3)
- `src/main/kotlin/com/qkt/positions/PositionProvider.kt` (only types update; new logic in Task 3)
- `src/main/kotlin/com/qkt/app/Main.kt` (literals migrate via `Money.of("...")`; full restructure in Task 5)

Engine.kt has no Double fields directly — types pass through. No change.

**Files modified (test):**
- Every test file that constructs a `Tick`, `Candle`, `Order`, `Trade`, `Signal`, `Position`, or asserts on `lastPrice`/`avgEntryPrice`/etc. That's ~14 test files. Assertions go from `isEqualTo(2400.5)` to `isEqualByComparingTo(Money.of("2400.5"))`.

### Production migrations

- [ ] **Step 1: Migrate `Tick.kt`**

Replace `src/main/kotlin/com/qkt/marketdata/Tick.kt` with:

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal

data class Tick(
    val symbol: String,
    val price: BigDecimal,
    val timestamp: Long,
    val volume: BigDecimal? = null,
)
```

- [ ] **Step 2: Migrate `Candle.kt`**

Replace `src/main/kotlin/com/qkt/marketdata/Candle.kt` with:

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal

data class Candle(
    val symbol: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val startTime: Long,
    val endTime: Long,
)
```

- [ ] **Step 3: Migrate `MarketPriceProvider.kt`**

Replace `src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt` with:

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal

interface MarketPriceProvider {
    fun lastPrice(symbol: String): BigDecimal?
}

class MarketPriceTracker : MarketPriceProvider {
    private val prices = mutableMapOf<String, BigDecimal>()

    fun update(symbol: String, price: BigDecimal) {
        prices[symbol] = price
    }

    override fun lastPrice(symbol: String): BigDecimal? = prices[symbol]
}
```

- [ ] **Step 4: Migrate `Order.kt`**

Replace `src/main/kotlin/com/qkt/execution/Order.kt` with:

```kotlin
package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

data class Order(
    val id: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val type: OrderType,
    val price: BigDecimal? = null,
    val timestamp: Long,
)
```

- [ ] **Step 5: Migrate `Trade.kt`**

Replace `src/main/kotlin/com/qkt/execution/Trade.kt` with:

```kotlin
package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

data class Trade(
    val orderId: String,
    val symbol: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val side: Side,
    val timestamp: Long,
)
```

- [ ] **Step 6: Migrate `Signal.kt`**

Replace `src/main/kotlin/com/qkt/strategy/Signal.kt` with:

```kotlin
package com.qkt.strategy

import java.math.BigDecimal

sealed class Signal {
    data class Buy(val symbol: String, val size: BigDecimal) : Signal()

    data class Sell(val symbol: String, val size: BigDecimal) : Signal()
}
```

- [ ] **Step 7: Migrate `EveryNthTickBuyStrategy.kt`**

Replace with:

```kotlin
package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: BigDecimal = Money.of("1"),
) : Strategy {
    init {
        require(n > 0) { "n must be > 0: $n" }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    private var counter = 0

    override fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        counter++
        if (counter % n == 0) emit(Signal.Buy(symbol, size))
    }
}
```

- [ ] **Step 8: Migrate `MockTickFeed.kt`**

Replace with:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Clock
import com.qkt.common.Money
import java.math.BigDecimal
import kotlin.random.Random

class MockTickFeed(
    private val symbol: String,
    private val startPrice: BigDecimal,
    private val count: Int,
    private val clock: Clock,
    private val tickIntervalMs: Long = 1_000L,
    private val random: Random = Random(seed = 42L),
) : TickFeed {
    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice.signum() > 0) { "startPrice must be > 0: $startPrice" }
        require(tickIntervalMs > 0L) { "tickIntervalMs must be > 0: $tickIntervalMs" }
    }

    private val startTime = clock.now()
    private var emitted = 0
    private var price: BigDecimal = startPrice.setScale(Money.SCALE, Money.ROUNDING)

    override fun next(): Tick? {
        if (emitted >= count) return null
        val factor = BigDecimal.ONE.add(
            BigDecimal.valueOf(random.nextDouble() - 0.5).multiply(BigDecimal("0.01")),
        )
        price = price.multiply(factor).setScale(Money.SCALE, Money.ROUNDING)
        val ts = startTime + emitted * tickIntervalMs
        emitted++
        return Tick(symbol, price, ts)
    }
}
```

Note: `random.nextDouble()` still returns a Double. We convert via `BigDecimal.valueOf(double)` which uses `Double.toString` and is precision-correct for non-exact representations. Determinism preserved by the seed.

- [ ] **Step 9: Migrate `MockBroker.kt`**

Replace with:

```kotlin
package com.qkt.broker

import com.qkt.common.Clock
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceProvider

class MockBroker(
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
) : Broker {
    override fun execute(order: Order): Trade? {
        require(order.quantity.signum() > 0) { "Order quantity must be > 0: $order" }
        val fillPrice = when (order.type) {
            OrderType.MARKET -> priceProvider.lastPrice(order.symbol) ?: return null
            OrderType.LIMIT, OrderType.STOP ->
                order.price ?: error("LIMIT/STOP requires price: $order")
        }
        return Trade(
            orderId = order.id,
            symbol = order.symbol,
            price = fillPrice,
            quantity = order.quantity,
            side = order.side,
            timestamp = clock.now(),
        )
    }
}
```

- [ ] **Step 10: Migrate `CandleAggregator.kt`**

Replace with:

```kotlin
package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class CandleAggregator(
    private val bus: EventBus,
    private val window: TimeWindow,
) {
    private val open = mutableMapOf<String, MutableCandle>()

    init {
        bus.subscribe<TickEvent> { event -> handle(event.tick) }
    }

    private fun handle(tick: Tick) {
        val state = open[tick.symbol]
        if (state == null) {
            open[tick.symbol] = newState(tick)
            return
        }
        if (tick.timestamp >= state.endTime) {
            bus.publish(CandleEvent(state.toCandle()))
            open[tick.symbol] = newState(tick)
            return
        }
        state.update(tick)
    }

    private fun newState(tick: Tick): MutableCandle {
        val start = window.windowStartFor(tick.timestamp)
        val end = start + window.durationMs
        return MutableCandle(
            symbol = tick.symbol,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.volume ?: Money.ZERO,
            startTime = start,
            endTime = end,
        )
    }

    private class MutableCandle(
        val symbol: String,
        val open: BigDecimal,
        var high: BigDecimal,
        var low: BigDecimal,
        var close: BigDecimal,
        var volume: BigDecimal,
        val startTime: Long,
        val endTime: Long,
    ) {
        fun update(tick: Tick) {
            if (tick.price > high) high = tick.price
            if (tick.price < low) low = tick.price
            close = tick.price
            if (tick.volume != null) volume = volume.add(tick.volume)
        }

        fun toCandle(): Candle = Candle(symbol, open, high, low, close, volume, startTime, endTime)
    }
}
```

Note: Kotlin's stdlib provides `operator fun BigDecimal.compareTo` so `tick.price > high` works.

- [ ] **Step 11: Migrate `MaxPositionSize.kt`**

Replace with:

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: BigDecimal,
) : RiskRule {
    init { require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" } }

    override fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision {
        if (order.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: Money.ZERO
        val projected =
            if (order.side == Side.BUY) current.add(order.quantity) else current.subtract(order.quantity)
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
```

- [ ] **Step 12: Migrate `Position.kt`** (quantity only; avgEntryPrice added in Task 3)

Replace with:

```kotlin
package com.qkt.positions

import java.math.BigDecimal

data class Position(
    val symbol: String,
    val quantity: BigDecimal,
)
```

- [ ] **Step 13: Migrate `PositionProvider.kt`** (types only; new logic in Task 3)

Replace with:

```kotlin
package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    fun apply(trade: Trade) {
        val current = positions[trade.symbol]?.quantity ?: Money.ZERO
        val delta = if (trade.side == Side.BUY) trade.quantity else trade.quantity.negate()
        val next = current.add(delta)
        if (next.signum() == 0) {
            positions.remove(trade.symbol)
        } else {
            positions[trade.symbol] = Position(trade.symbol, next)
        }
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]

    override fun allPositions(): Map<String, Position> = positions.toMap()
}
```

`apply` returns `Unit` for now (Task 3 changes the signature to return `BigDecimal`). Float `== 0.0` becomes `signum() == 0` (exact for BigDecimal).

- [ ] **Step 14: Migrate `Main.kt`** (numeric literals only; full restructure in Task 5)

Update `src/main/kotlin/com/qkt/app/Main.kt` to change literal numbers to `Money.of(...)`. Find:

```kotlin
EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
...
MaxPositionSize(symbol = "XAUUSD", maxQty = 3.0),
...
val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock, tickIntervalMs = 1_000L)
```

Replace with:

```kotlin
EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1")),
...
MaxPositionSize(symbol = "XAUUSD", maxQty = Money.of("3")),
...
val feed = MockTickFeed("XAUUSD", startPrice = Money.of("2400"), count = 100, clock = clock, tickIntervalMs = 1_000L)
```

Add the import:

```kotlin
import com.qkt.common.Money
```

The FILLED log line currently uses `t.price`, `t.quantity` etc. as numeric arguments to `log.info`. After migration these are `BigDecimal`s; SLF4J will call `toString()` which gives `1.00000000` for scale=8 zero-padded values. We accept that for Task 2 (ugly but functional) and clean up in Task 5 with `stripTrailingZeros().toPlainString()`.

### Test migrations

For every test file that constructs a `Tick`, `Candle`, `Order`, `Trade`, `Signal`, or asserts on numeric values, change:
- Construction: `Tick("XAUUSD", 2400.5, 1000L)` → `Tick("XAUUSD", Money.of("2400.5"), 1000L)`
- Assertions: `assertThat(price).isEqualTo(2400.5)` → `assertThat(price).isEqualByComparingTo(Money.of("2400.5"))`
- Helpers: any `private fun trade(qty: Double, ...)` becomes `qty: BigDecimal` and callers use `Money.of("1.0")`.

Apply this mechanically to every test file:
- `src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt`
- `src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt`
- `src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt`
- `src/test/kotlin/com/qkt/broker/MockBrokerTest.kt`
- `src/test/kotlin/com/qkt/engine/EngineTest.kt`
- `src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`
- `src/test/kotlin/com/qkt/risk/rules/MaxPositionSizeTest.kt`
- `src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt`
- `src/test/kotlin/com/qkt/risk/RiskEngineTest.kt`
- `src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt`
- `src/test/kotlin/com/qkt/app/EndToEndTest.kt`
- `src/test/kotlin/com/qkt/bus/EventBusTest.kt` (only the CandleEvent + RiskRejectedEvent tests construct Order/Candle)

`MoneyTest`, `MonotonicSequenceGeneratorTest`, `TimeWindowTest` are unaffected (no money types).

For each test file, the migration follows this pattern:
- Import: add `import com.qkt.common.Money`, `import java.math.BigDecimal` if needed.
- Helper function signatures: `qty: Double` → `qty: BigDecimal`, `price: Double` → `price: BigDecimal`.
- Helper invocations: `qty = 1.0` → `qty = Money.of("1.0")`.
- Construction calls: `Tick("X", 2400.0, 1000L)` → `Tick("X", Money.of("2400.0"), 1000L)`.
- Assertions: `.isEqualTo(2400.5)` → `.isEqualByComparingTo(Money.of("2400.5"))`.
- Capture lists: `mutableListOf<Double?>()` → `mutableListOf<BigDecimal?>()`.

### Verification

- [ ] **Step 15: Compile**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

If compile fails, the error pinpoints a missed conversion. Fix and re-run.

- [ ] **Step 16: Run all tests**

Run: `./gradlew test --rerun-tasks`
Expected: 93 tests PASS (90 from Phase 3 + 3 from Money in Task 1).

If a test fails, investigate — the migration should be behavior-equivalent. If a test asserts a wrong precision now (e.g., `.isEqualTo(BigDecimal("2400.5"))` would fail because scale differs), fix to use `isEqualByComparingTo`.

- [ ] **Step 17: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. ktlint clean. 93 tests pass.

If ktlint fails, run `./gradlew ktlintFormat` and re-run check.

- [ ] **Step 18: Run main, sanity check**

Run: `./gradlew run`
Expected: 3 FILLED + 7 REJECTED + Done. Numbers may display with `.00000000` suffixes (cleanup in Task 5). Behavior identical to Phase 3.

- [ ] **Step 19: Commit**

```bash
git add src/main/kotlin/com/qkt src/test/kotlin/com/qkt
git status   # verify all expected files staged
git commit -m "refactor: migrate Double to BigDecimal across price and quantity fields"
```

This is the largest commit in the project history. ~25 files. Acceptable because the migration is behaviorally inert and atomic — splitting it would break the build between commits.

---

## Task 3: Position + PositionTracker rewrite [TDD]

Adds `avgEntryPrice` to `Position` and rewrites `apply` with weighted-average + flipping semantics that returns realized P&L.

**Files modified:**
- `src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt` (existing 6 tests migrate; +4 new)
- `src/main/kotlin/com/qkt/positions/Position.kt` (gains `avgEntryPrice`)
- `src/main/kotlin/com/qkt/positions/PositionProvider.kt` (apply returns `BigDecimal`; weighted-avg + flipping logic)

### Step 1: Update existing tests + add 4 new tests

Replace `src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt` with:

```kotlin
package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PositionTrackerTest {
    private val tracker = PositionTracker()

    private fun trade(
        symbol: String,
        qty: BigDecimal,
        side: Side,
        price: BigDecimal = Money.of("100"),
        ts: Long = 1000L,
    ) = Trade(orderId = "ORD-X", symbol = symbol, price = price, quantity = qty, side = side, timestamp = ts)

    @Test
    fun `positionFor returns null for unknown symbol`() {
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `apply Buy creates position with positive quantity and avgEntryPrice equal to trade price`() {
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1.5"), side = Side.BUY, price = Money.of("100")))
        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("1.5"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `apply Sell from zero creates short position with negative quantity and avgEntryPrice equal to trade price`() {
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL, price = Money.of("100")))
        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("-2"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `consecutive Buys accumulate quantity and update weighted-average price`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("110")))
        tracker.apply(trade("XAUUSD", qty = Money.of("0.5"), side = Side.BUY, price = Money.of("120")))
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("2.5"))
        // weighted: (1*100 + 1*110 + 0.5*120) / 2.5 = 270/2.5 = 108
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("108"))
    }

    @Test
    fun `Sell that brings quantity to zero removes the position from the map`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))   // 2 * (110 - 100)
        assertThat(tracker.positionFor("XAUUSD")).isNull()
        assertThat(tracker.allPositions()).isEmpty()
    }

    @Test
    fun `tracks positions for multiple symbols independently`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("EURUSD", qty = Money.of("5"), side = Side.BUY, price = Money.of("1.10")))
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("3"))
        assertThat(tracker.positionFor("EURUSD")?.quantity).isEqualByComparingTo(Money.of("5"))
        assertThat(tracker.allPositions()).hasSize(2)
    }

    @Test
    fun `weighted-average price updates on subsequent buys`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("110")))
        val pos = tracker.positionFor("XAUUSD")!!
        // (1*100 + 1*110) / 2 = 105
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("105"))
    }

    @Test
    fun `partial sell does not change avgEntryPrice and returns realized PnL`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("10"))   // 1 * (110 - 100)
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("1"))
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("100"))   // unchanged
    }

    @Test
    fun `full close removes position and returns realized PnL`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("1"), side = Side.SELL, price = Money.of("120")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))   // 1 * (120 - 100)
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `flipping trade realizes PnL on closed portion and opens new position at trade price`() {
        tracker.apply(trade("XAUUSD", qty = Money.of("2"), side = Side.BUY, price = Money.of("100")))
        val realized = tracker.apply(trade("XAUUSD", qty = Money.of("5"), side = Side.SELL, price = Money.of("110")))
        assertThat(realized).isEqualByComparingTo(Money.of("20"))   // closed 2 @ avg 100 sold @ 110
        val pos = tracker.positionFor("XAUUSD")!!
        assertThat(pos.quantity).isEqualByComparingTo(Money.of("-3"))   // -3 short
        assertThat(pos.avgEntryPrice).isEqualByComparingTo(Money.of("110"))   // new short avg = sell price
    }
}
```

10 tests total: 6 migrated + 4 new (weighted-average, partial sell, full close, flipping).

### Step 2: Run tests, confirm RED

Run: `./gradlew test --tests "com.qkt.positions.PositionTrackerTest"`
Expected: tests fail. The new tests reference `Position.avgEntryPrice` (doesn't exist yet) and `tracker.apply(...)` returning `BigDecimal` (currently returns Unit).

### Step 3: Update `Position.kt`

Replace with:

```kotlin
package com.qkt.positions

import java.math.BigDecimal

data class Position(
    val symbol: String,
    val quantity: BigDecimal,
    val avgEntryPrice: BigDecimal,
)
```

### Step 4: Replace `PositionProvider.kt` with weighted-average + flipping logic

Replace with:

```kotlin
package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    /**
     * Applies a trade. Returns the realized P&L from this trade
     * (zero on adds; non-zero on partial close, full close, or flipping).
     */
    fun apply(trade: Trade): BigDecimal {
        val current = positions[trade.symbol]
        val signedTradeQty =
            if (trade.side == Side.BUY) trade.quantity else trade.quantity.negate()

        if (current == null) {
            positions[trade.symbol] = Position(trade.symbol, signedTradeQty, trade.price)
            return Money.ZERO
        }

        val currentQty = current.quantity
        val currentAvg = current.avgEntryPrice
        val sameDirection = currentQty.signum() == signedTradeQty.signum()

        if (sameDirection) {
            val totalQty = currentQty.add(signedTradeQty)
            val newAvg =
                currentAvg
                    .multiply(currentQty.abs())
                    .add(trade.price.multiply(trade.quantity))
                    .divide(totalQty.abs(), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            positions[trade.symbol] = Position(trade.symbol, totalQty, newAvg)
            return Money.ZERO
        }

        val closingQty = currentQty.abs().min(trade.quantity)
        val priceDiff =
            if (currentQty.signum() > 0) {
                trade.price.subtract(currentAvg)
            } else {
                currentAvg.subtract(trade.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)

        val remainingQty = currentQty.add(signedTradeQty)
        when {
            remainingQty.signum() == 0 -> positions.remove(trade.symbol)
            remainingQty.signum() == currentQty.signum() ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, currentAvg)
            else ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, trade.price)
        }
        return realized
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]

    override fun allPositions(): Map<String, Position> = positions.toMap()
}
```

### Step 5: Compile, fix EndToEndTest if needed

Run: `./gradlew compileKotlin compileTestKotlin`

`EndToEndTest.kt` constructs `Position` objects in some assertions or helpers (verify). Any `Position(symbol, quantity)` two-arg construction now requires the third `avgEntryPrice` arg. Fix call sites:

```kotlin
Position("XAUUSD", Money.of("1"))   // OLD
Position("XAUUSD", Money.of("1"), Money.of("100"))   // NEW (with appropriate avgEntryPrice)
```

If `EndToEndTest` only asserts on `positionFor(symbol)?.quantity`, no construction fix needed.

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

### Step 6: Run tests, confirm GREEN

Run: `./gradlew test --tests "com.qkt.positions.PositionTrackerTest"`
Expected: 10 tests PASS.

### Step 7: Run full check

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 97 tests total (93 + 4 new).

### Step 8: Commit

```bash
git add src/main/kotlin/com/qkt/positions/Position.kt src/main/kotlin/com/qkt/positions/PositionProvider.kt src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt
git commit -m "feat(positions): add avgEntryPrice and weighted-average apply with flipping"
```

---

## Task 4: PnLProvider + PnLCalculator [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/pnl/PnLCalculatorTest.kt`
- Create: `src/main/kotlin/com/qkt/pnl/PnLProvider.kt`

### Step 1: Write the failing tests

`src/test/kotlin/com/qkt/pnl/PnLCalculatorTest.kt`:

```kotlin
package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PnLCalculatorTest {
    private val tracker = PositionTracker()
    private val priceTracker = MarketPriceTracker()
    private val pnl = PnLCalculator(tracker, priceTracker)

    @Test
    fun `realizedTotal is zero on a fresh calculator`() {
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `recordRealized accumulates positive realized values`() {
        pnl.recordRealized(Money.of("10"))
        pnl.recordRealized(Money.of("25.5"))
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("35.5"))
    }

    @Test
    fun `recordRealized accumulates negative realized values (losses)`() {
        pnl.recordRealized(Money.of("10"))
        pnl.recordRealized(Money.of("-15"))
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("-5"))
    }

    @Test
    fun `unrealizedFor returns zero for unknown symbol`() {
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor returns zero when no current price for symbol`() {
        tracker.apply(
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("1"), Side.BUY, 1000L),
        )
        // priceTracker has no price for XAUUSD
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor computes (price - avg) * quantity for a long position`() {
        tracker.apply(
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("2"), Side.BUY, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))
        // (110 - 100) * 2 = 20
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `unrealizedFor returns negative for a short position with rising price`() {
        tracker.apply(
            Trade("ORD-X", "XAUUSD", Money.of("100"), Money.of("2"), Side.SELL, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))
        // (110 - 100) * -2 = -20
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("-20"))
    }

    @Test
    fun `unrealizedTotal sums across all open symbols`() {
        tracker.apply(
            Trade("ORD-1", "XAUUSD", Money.of("100"), Money.of("2"), Side.BUY, 1000L),
        )
        tracker.apply(
            Trade("ORD-2", "EURUSD", Money.of("1.10"), Money.of("100"), Side.BUY, 1000L),
        )
        priceTracker.update("XAUUSD", Money.of("110"))   // unrealized = (110-100) * 2 = 20
        priceTracker.update("EURUSD", Money.of("1.20"))  // unrealized = (1.20-1.10) * 100 = 10
        // total = 30
        assertThat(pnl.unrealizedTotal()).isEqualByComparingTo(Money.of("30"))
    }
}
```

### Step 2: Run test, confirm RED

Run: `./gradlew test --tests "com.qkt.pnl.PnLCalculatorTest"`
Expected: compile failure — `Unresolved reference 'PnLCalculator'`.

### Step 3: Implement `PnLProvider.kt`

`src/main/kotlin/com/qkt/pnl/PnLProvider.kt`:

```kotlin
package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
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
    private var realizedTotal: BigDecimal = Money.ZERO

    fun recordRealized(realized: BigDecimal) {
        realizedTotal = realizedTotal.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun realizedTotal(): BigDecimal = realizedTotal

    override fun unrealizedFor(symbol: String): BigDecimal {
        val pos = positions.positionFor(symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        return price
            .subtract(pos.avgEntryPrice)
            .multiply(pos.quantity)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun unrealizedTotal(): BigDecimal =
        positions.allPositions().keys
            .map { unrealizedFor(it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    override fun totalPnL(): BigDecimal =
        realizedTotal().add(unrealizedTotal()).setScale(Money.SCALE, Money.ROUNDING)
}
```

### Step 4: Run test, confirm GREEN

Run: `./gradlew test --tests "com.qkt.pnl.PnLCalculatorTest"`
Expected: 8 tests PASS.

### Step 5: Run full check

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 105 tests total (97 + 8 new).

### Step 6: Commit

```bash
git add src/main/kotlin/com/qkt/pnl/PnLProvider.kt src/test/kotlin/com/qkt/pnl/PnLCalculatorTest.kt
git commit -m "feat(pnl): add PnLProvider and PnLCalculator with realized and unrealized"
```

---

## Task 5: OrderFactory extraction + Main.kt wiring + EndToEndTest +2

Extracts the `Signal.toOrder` extension to a shared file, restructures `Main.kt` with the PnL wiring + extended log lines, adds 2 P&L integration tests.

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/OrderFactory.kt`
- Modify: `src/main/kotlin/com/qkt/app/Main.kt`
- Modify: `src/test/kotlin/com/qkt/app/EndToEndTest.kt`

### Step 1: Create `OrderFactory.kt`

`src/main/kotlin/com/qkt/execution/OrderFactory.kt`:

```kotlin
package com.qkt.execution

import com.qkt.common.Side
import com.qkt.strategy.Signal

fun Signal.toOrder(
    id: String,
    ts: Long,
): Order = when (this) {
    is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
    is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
}
```

Top-level extension function. Importable as `com.qkt.execution.toOrder`.

### Step 2: Replace `Main.kt`

Replace `src/main/kotlin/com/qkt/app/Main.kt` with:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.toOrder
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()
    val positions = PositionTracker()
    val pnl = PnLCalculator(positions, priceTracker)

    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    val strategies: List<Strategy> =
        listOf(
            EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1")),
        )

    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "XAUUSD", maxQty = Money.of("3")),
        )
    val riskEngine = RiskEngine(rules, positions)

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e ->
            strategy.onTick(e.tick) { signal -> bus.publish(SignalEvent(signal)) }
        }
        bus.subscribe<CandleEvent> { e ->
            strategy.onCandle(e.candle) { signal -> bus.publish(SignalEvent(signal)) }
        }
    }

    bus.subscribe<SignalEvent> { e ->
        val order = e.signal.toOrder(ids.next(), clock.now())
        when (val decision = riskEngine.approve(order)) {
            is Decision.Approve -> bus.publish(OrderEvent(order))
            is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
        }
    }

    bus.subscribe<OrderEvent> { e ->
        broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
    }

    bus.subscribe<TradeEvent> { e ->
        val realized = positions.apply(e.trade)
        pnl.recordRealized(realized)
    }

    bus.subscribe<TradeEvent> { e ->
        val t = e.trade
        val pos = positions.positionFor(t.symbol)?.quantity ?: Money.ZERO
        log.info(
            "FILLED: {} {} {} @ {} (position: {}, realized: {}, unrealized: {})",
            t.side,
            t.quantity.stripTrailingZeros().toPlainString(),
            t.symbol,
            t.price.stripTrailingZeros().toPlainString(),
            pos.stripTrailingZeros().toPlainString(),
            pnl.realizedTotal().setScale(2, Money.ROUNDING),
            pnl.unrealizedTotal().setScale(2, Money.ROUNDING),
        )
    }

    bus.subscribe<RiskRejectedEvent> { e ->
        val o = e.order
        log.info(
            "REJECTED: {} {} {} ({})",
            o.side,
            o.quantity.stripTrailingZeros().toPlainString(),
            o.symbol,
            e.reason,
        )
    }

    bus.subscribe<CandleEvent> { e ->
        val c = e.candle
        log.info(
            "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol,
            c.open.stripTrailingZeros().toPlainString(),
            c.high.stripTrailingZeros().toPlainString(),
            c.low.stripTrailingZeros().toPlainString(),
            c.close.stripTrailingZeros().toPlainString(),
            c.volume.stripTrailingZeros().toPlainString(),
            c.startTime,
            c.endTime,
        )
    }

    val feed =
        MockTickFeed(
            symbol = "XAUUSD",
            startPrice = Money.of("2400"),
            count = 100,
            clock = clock,
            tickIntervalMs = 1_000L,
        )
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}
```

Critical changes vs Phase 3 Main:
- New imports: `Money`, `PnLCalculator`, `toOrder` (from OrderFactory).
- The `private fun Signal.toOrder` at the bottom of the file is REMOVED — now imported from OrderFactory.
- New `pnl = PnLCalculator(positions, priceTracker)`.
- TradeEvent subscriber #1 captures `realized` and calls `pnl.recordRealized`.
- FILLED log shows `(position, realized, unrealized)` formatted via `stripTrailingZeros()` and `setScale(2)`.
- All numeric literals via `Money.of("...")`.

### Step 3: Update EndToEndTest.kt

Read `src/test/kotlin/com/qkt/app/EndToEndTest.kt`. Update:

3a. **Update imports**: add `import com.qkt.common.Money`, `import com.qkt.execution.toOrder`, `import com.qkt.pnl.PnLCalculator`. Remove the local `private fun Signal.toOrder(...)` declaration (use imported one).

3b. **Add `pnl` field** alongside existing private fields:

```kotlin
private val pnl = PnLCalculator(positions, tracker)
```

3c. **Update `wirePipeline` helper** to record realized P&L on the existing TradeEvent subscriber that updates positions:

```kotlin
private fun wirePipeline(
    strategies: List<Strategy>,
    captureOrders: Boolean = false,
    rules: List<RiskRule> = emptyList(),
) {
    val riskEngine = RiskEngine(rules, positions)
    bus.subscribe<TradeEvent> { e ->
        val realized = positions.apply(e.trade)
        pnl.recordRealized(realized)
    }
    strategies.forEach { s ->
        bus.subscribe<TickEvent> { e ->
            s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
        }
        bus.subscribe<CandleEvent> { e ->
            s.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
        }
    }
    bus.subscribe<SignalEvent> { e ->
        val order = e.signal.toOrder(ids.next(), clock.now())
        if (captureOrders) orders.add(order)
        when (val decision = riskEngine.approve(order)) {
            is Decision.Approve -> bus.publish(OrderEvent(order))
            is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
        }
    }
    bus.subscribe<OrderEvent> { e ->
        broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
    }
    bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
}
```

3d. **Migrate existing 12 tests' assertions and constructions** to `Money.of("...")` + `isEqualByComparingTo`. Mechanical pass.

3e. **Add 2 new tests** at the end of the class body:

```kotlin
@Test
fun `realized PnL accumulates after a closing trade`() {
    val sellAfterBuy =
        object : Strategy {
            private var bought = false
            override fun onTick(
                tick: Tick,
                emit: (Signal) -> Unit,
            ) {
                if (!bought) {
                    emit(Signal.Buy("XAUUSD", Money.of("1")))
                    bought = true
                } else {
                    emit(Signal.Sell("XAUUSD", Money.of("1")))
                }
            }
        }
    wirePipeline(listOf(sellAfterBuy))

    engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))   // BUY 1 @ 100
    engine.onTick(Tick("XAUUSD", Money.of("120"), 1000L))  // SELL 1 @ 120

    assertThat(trades).hasSize(2)
    assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("20"))
}

@Test
fun `unrealized PnL is visible after an open position`() {
    val buyOnce =
        object : Strategy {
            private var done = false
            override fun onTick(
                tick: Tick,
                emit: (Signal) -> Unit,
            ) {
                if (!done) {
                    emit(Signal.Buy("XAUUSD", Money.of("2")))
                    done = true
                }
            }
        }
    wirePipeline(listOf(buyOnce))

    engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))    // BUY 2 @ 100; tracker records price=100
    // (price - avg) * qty = (100 - 100) * 2 = 0
    assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)

    // Advance the price; engine.onTick updates the tracker before publishing the TickEvent
    engine.onTick(Tick("XAUUSD", Money.of("110"), 1000L))
    // (110 - 100) * 2 = 20
    assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("20"))
}
```

### Step 4: Compile and run tests

Run: `./gradlew test`
Expected: 107 tests PASS (105 + 2 new).

If compile fails, fix call sites that reference the removed `private fun Signal.toOrder` (use the imported version).

### Step 5: Run main, sanity check

Run: `./gradlew run`
Expected: 3 FILLED + 7 REJECTED + Done. FILLED lines now show `(position: N, realized: 0.00, unrealized: X.YZ)` with clean formatting (no `.00000000` artifacts).

If FILLED count is not 3 or REJECTED is not 7, report DONE_WITH_CONCERNS.

### Step 6: Run full check

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 107 tests pass. ktlint clean.

If ktlint fails, run `./gradlew ktlintFormat` and re-run check.

### Step 7: Commit

```bash
git add src/main/kotlin/com/qkt/execution/OrderFactory.kt src/main/kotlin/com/qkt/app/Main.kt src/test/kotlin/com/qkt/app/EndToEndTest.kt
git commit -m "feat(app): wire PnLCalculator and extract OrderFactory"
```

---

## Task 6: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Compile + ktlintCheck + 107 tests + assemble.

- [ ] **Step 2: Force test re-run, count PASSED**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -cE "PASSED$"`
Expected: `107`.

- [ ] **Step 3: Run main, confirm FILLED + REJECTED + Done. with P&L**

Run: `./gradlew run 2>&1 | tail -25`
Expected: 3 FILLED lines (each with `position`, `realized: 0.00`, `unrealized: X.YZ`), 7 REJECTED lines, optional CANDLE lines, then `Done.`. The realized stays at 0 throughout because the demo strategy only buys.

- [ ] **Step 4: Verify file count**

Run: `find src -name "*.kt" -type f | sort | wc -l`
Expected: 47.

Phase 3 had 42 files (28 production + 14 test). Phase 3b adds:
- Production: `Money.kt`, `OrderFactory.kt`, `PnLProvider.kt` (+3)
- Test: `MoneyTest.kt`, `PnLCalculatorTest.kt` (+2)
- Total: 42 + 5 = 47.

If the actual count differs from 47, investigate.

- [ ] **Step 5: Verify line counts**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest production file under 130 lines. `Main.kt` and `PositionTracker.kt` are the biggest.

Run: `wc -l src/test/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest test file is `EndToEndTest.kt` at ~360 lines (Phase 3's 340 + 2 new P&L tests). Still over the project skill's 200-line cap. Carry forward to a future cleanup phase per the Phase 3 backlog.

- [ ] **Step 6: Run precheck**

Run: `./scripts/precheck.sh`
Expected: all 6 steps PASS.

- [ ] **Step 7: Verify acceptance criteria from spec §13**

- [x] `Money` object exists in `com.qkt.common`.
- [x] All money + quantity fields are `BigDecimal`.
- [x] `Position` has `avgEntryPrice: BigDecimal`.
- [x] `PositionTracker.apply(trade): BigDecimal` returns realized P&L.
- [x] `PnLProvider`, `PnLCalculator` exist in `com.qkt.pnl`.
- [x] `OrderFactory.kt` provides shared `Signal.toOrder`.
- [x] All 107 tests pass.
- [x] `./gradlew build` green.
- [x] `./gradlew run` produces 3 FILLED + 7 REJECTED with `(position, realized, unrealized)` formatted cleanly.
- [x] Phase 3 acceptance still holds.
- [x] `EveryNthTickBuyStrategy` continues unchanged in behavior.
- [x] Clean git history.

When all checked, Phase 3b is done.

- [ ] **Step 8: No commit**

Verification only.

---

## Notes for the implementing engineer

- **Run tests after every TDD task** (Tasks 1, 3, 4). Don't batch.
- **Task 2 is the largest commit in project history.** Embrace the size — the migration is atomic by necessity. Be careful with find/replace; review staged diffs before committing.
- **Don't use mocks.** Anonymous `Strategy`/`Broker` impls + capture lists.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if check fails; stage formatting with the changes.
- **Match Kotlin style exactly.** No semicolons. No `public`. No emojis. No useless comments.
- **Frequent small commits per task.** Each task ends in exactly one commit (Task 2 is the one big commit).
- **If a test fails unexpectedly:** read the error. The most common Phase 3b failure is mistaken `isEqualTo` (for BigDecimal scale equality) instead of `isEqualByComparingTo` (for value equality). Fix the assertion, not the production code.
- **No emoji, no AI references** in code or commit messages. Per CLAUDE.md and the project skill.
- **Commit message types:** use `feat`, `refactor`, `test`, `build`, `chore`, `docs`, `style`. Subject only, no body, no footer, no AI references.
- **`EndToEndTest.kt` will exceed 360 lines after this phase.** Project-skill 200-line cap is exceeded. Flagged in carried-over Phase 3 backlog. Phase 4 should split this file.
