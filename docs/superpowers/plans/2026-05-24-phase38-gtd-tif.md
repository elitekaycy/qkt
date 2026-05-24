# Phase 38 — GTD time-in-force Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate `TIF GTD <expr>` end-to-end so a pending order self-cancels at its deadline — venue-side on MT5 (native), engine-side on PaperBroker/Bybit via the existing tick-driven sweep in `OrderManager`.

**Architecture:** `TimeInForce` gains a `GTD` enum value. Pending `OrderRequest` variants carry a nullable `expiresAt: Long?` (epoch ms). `ActionCompiler` compiles the `until` expression once per strategy and evaluates per emit. `Broker.supportsNativeGtd` decides who enforces the deadline — MT5 stamps it onto the wire `expiration` field; PaperBroker/Bybit let `OrderManager.evaluateTriggers` cancel the order on the next tick after the deadline.

**Tech Stack:** Kotlin 2.x, JUnit 5, AssertJ. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-24-phase38-gtd-tif-design.md`](../specs/2026-05-24-phase38-gtd-tif-design.md)

**Issue:** <https://github.com/elitekaycy/qkt/issues/50>

---

## File map

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/com/qkt/execution/TimeInForce.kt` | modify | Add `GTD` enum value. |
| `src/main/kotlin/com/qkt/execution/OrderRequest.kt` | modify | Add `expiresAt: Long? = null` to 9 pending variants. New `withExpiresAt(Long?)` extension parallel to `withStrategyId`. |
| `src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt` | modify | `is Gtd -> TimeInForce.GTD` (was: error). |
| `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` | modify | Compile `gtd.until` via `ExprCompiler`; evaluate per emit; stamp `expiresAt` on built request. Compile-time guard against `TIF GTD` on Market. |
| `src/main/kotlin/com/qkt/broker/Broker.kt` | modify | Add `val supportsNativeGtd: Boolean get() = false`. |
| `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` | modify | Override `supportsNativeGtd = true`. |
| `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt` | modify | Pass `request.expiresAt / 1000` into the wire `expiration` field. |
| `src/main/kotlin/com/qkt/app/OrderManager.kt` | modify | In `evaluateTriggers(tick)`, sweep pending orders for expired `expiresAt` when `!broker.supportsNativeGtd`. |
| `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt` | modify | Round-trip `expiresAt` in `OrderRequestDto.fromDomain`/`toDomain`. |
| `src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt` | extend | Replace "GTD is deferred" with "GTD maps to TimeInForce.GTD". |
| `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerGtdTest.kt` | create | 3 tests. |
| `src/test/kotlin/com/qkt/execution/OrderRequestWithExpiresAtTest.kt` | create | 4 tests. |
| `src/test/kotlin/com/qkt/app/OrderManagerGtdSweepTest.kt` | create | 4 tests. |
| `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorGtdTest.kt` | create | 2 tests. |
| `src/test/kotlin/com/qkt/persistence/FileStatePersistorGtdTest.kt` | create | 1 parameterised test. |
| `docs/phases/phase-38-gtd-tif.md` | create | Phase changelog. |

---

### Task 1: `TimeInForce.GTD` + `TifTranslator` activation

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/TimeInForce.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt`

- [ ] **Step 1: Add the enum value**

In `src/main/kotlin/com/qkt/execution/TimeInForce.kt`, append:

```kotlin
/** Good-til-date — stays open until [OrderRequest.expiresAt] passes (epoch millis). */
GTD,
```

- [ ] **Step 2: Update `TifTranslator`**

In `src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt`, replace the `is Gtd ->` branch:

```kotlin
is Gtd -> TimeInForce.GTD
```

The `until` expression is consumed by `ActionCompiler` (Task 4), not here.

- [ ] **Step 3: Update the existing test**

In `src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt`, find the test asserting GTD is deferred and replace with:

```kotlin
@Test
fun `GTD maps to TimeInForce GTD`() {
    val tif = TifTranslator.translate(Gtd(NumLit(BigDecimal("1700000000000"))))
    assertThat(tif).isEqualTo(TimeInForce.GTD)
}
```

- [ ] **Step 4: Run + commit**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.TifTranslatorTest'`
Expected: PASS.

```bash
git add src/main/kotlin/com/qkt/execution/TimeInForce.kt \
        src/main/kotlin/com/qkt/dsl/compile/TifTranslator.kt \
        src/test/kotlin/com/qkt/dsl/compile/TifTranslatorTest.kt
git commit -m "feat(execution): add TimeInForce.GTD and activate TifTranslator path"
```

---

### Task 2: `expiresAt` on `OrderRequest` + `withExpiresAt`

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Create: `src/test/kotlin/com/qkt/execution/OrderRequestWithExpiresAtTest.kt`

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/qkt/execution/OrderRequestWithExpiresAtTest.kt`:

```kotlin
package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestWithExpiresAtTest {
    private fun limit(id: String): OrderRequest.Limit =
        OrderRequest.Limit(
            id = id, symbol = "EURUSD", side = Side.BUY,
            quantity = Money.of("1"), limitPrice = Money.of("1.10"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )

    @Test
    fun `withExpiresAt stamps Limit`() {
        val stamped = limit("l1").withExpiresAt(1700000000000L) as OrderRequest.Limit
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into Bracket entry`() {
        val br =
            OrderRequest.Bracket(
                id = "b1", symbol = "XAUUSD", side = Side.BUY, quantity = Money.of("1"),
                entry = limit("e1"),
                takeProfit = Money.of("4600"), stopLoss = Money.of("4400"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            )
        val stamped = br.withExpiresAt(1700000000000L) as OrderRequest.Bracket
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.entry as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into both StandaloneOCO legs`() {
        val oco =
            OrderRequest.StandaloneOCO(
                id = "o1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                leg1 = limit("l1"), leg2 = limit("l2"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            )
        val stamped = oco.withExpiresAt(1700000000000L) as OrderRequest.StandaloneOCO
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.leg1 as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.leg2 as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into OTO parent and all children`() {
        val oto =
            OrderRequest.OTO(
                id = "oto1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                parent = limit("p1"), children = listOf(limit("c1"), limit("c2")),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            )
        val stamped = oto.withExpiresAt(1700000000000L) as OrderRequest.OTO
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.parent as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        assertThat(stamped.children).allSatisfy {
            assertThat((it as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.qkt.execution.OrderRequestWithExpiresAtTest'`
Expected: FAIL — `Unresolved reference: expiresAt` and/or `Unresolved reference: withExpiresAt`.

- [ ] **Step 3: Add `expiresAt: Long? = null` to nine pending variants**

In `src/main/kotlin/com/qkt/execution/OrderRequest.kt`, add the field to each of these data classes (place it at the end of the parameter list, alongside `strategyId`):

```kotlin
// On every one of: Limit, Stop, StopLimit, IfTouched, TrailingStop, TrailingStopLimit,
// Bracket, StandaloneOCO, OTO, ScaleOut
override val strategyId: String = "",
val expiresAt: Long? = null,
```

Add an `expiresAt` accessor to the `OrderRequest` interface itself so callers can read it polymorphically without exhaustive `when`:

```kotlin
sealed interface OrderRequest {
    // ... existing val id, symbol, side, quantity, timeInForce, timestamp, strategyId
    val expiresAt: Long? get() = null
}
```

Override on each of the nine variants — Kotlin generates the override automatically because each is a `val expiresAt: Long?` constructor parameter. `Market`, `TimeExit`, and `Stack` inherit the default-null from the interface.

- [ ] **Step 4: Add the `withExpiresAt` extension**

In `src/main/kotlin/com/qkt/execution/OrderRequest.kt`, after the existing `withStrategyId` function:

```kotlin
/**
 * Returns a copy of this request with [expiresAt] populated; preserves the concrete subtype.
 *
 * Composite variants ([Bracket], [StandaloneOCO], [OTO], [ScaleOut]) also stamp [expiresAt]
 * onto their nested sub-requests so the deadline rides every leg the broker sees. [Market],
 * [TimeExit], and [Stack] return themselves unchanged — they have no GTD semantic.
 */
fun OrderRequest.withExpiresAt(expiresAt: Long?): OrderRequest =
    when (this) {
        is OrderRequest.Market -> this
        is OrderRequest.TimeExit -> this
        is OrderRequest.Stack -> this
        is OrderRequest.Limit -> copy(expiresAt = expiresAt)
        is OrderRequest.Stop -> copy(expiresAt = expiresAt)
        is OrderRequest.StopLimit -> copy(expiresAt = expiresAt)
        is OrderRequest.IfTouched -> copy(expiresAt = expiresAt)
        is OrderRequest.TrailingStop -> copy(expiresAt = expiresAt)
        is OrderRequest.TrailingStopLimit -> copy(expiresAt = expiresAt)
        is OrderRequest.Bracket ->
            copy(expiresAt = expiresAt, entry = entry.withExpiresAt(expiresAt))
        is OrderRequest.StandaloneOCO ->
            copy(
                expiresAt = expiresAt,
                leg1 = leg1.withExpiresAt(expiresAt),
                leg2 = leg2.withExpiresAt(expiresAt),
            )
        is OrderRequest.OTO ->
            copy(
                expiresAt = expiresAt,
                parent = parent.withExpiresAt(expiresAt),
                children = children.map { it.withExpiresAt(expiresAt) },
            )
        is OrderRequest.ScaleOut ->
            copy(expiresAt = expiresAt, basis = basis.withExpiresAt(expiresAt))
    }
```

- [ ] **Step 5: Run + commit**

Run: `./gradlew test --tests 'com.qkt.execution.OrderRequestWithExpiresAtTest'`
Expected: 4 PASSED.

```bash
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt \
        src/test/kotlin/com/qkt/execution/OrderRequestWithExpiresAtTest.kt
git commit -m "feat(execution): carry expiresAt on pending OrderRequest variants"
```

---

### Task 3: `Broker.supportsNativeGtd` capability flag

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/Broker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`

- [ ] **Step 1: Add the interface property**

In `src/main/kotlin/com/qkt/broker/Broker.kt`, add inside the interface block (next to `capabilities`):

```kotlin
/**
 * When true, this broker submits GTD orders with a venue-side expiration and the venue
 * self-cancels at the deadline; the engine's deadline-sweep in [com.qkt.app.OrderManager]
 * skips orders routed through this broker. When false (default), the engine cancels the
 * order at the deadline on the next tick.
 */
val supportsNativeGtd: Boolean get() = false
```

- [ ] **Step 2: Override on MT5**

In `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`, near the existing `override val capabilities` line, add:

```kotlin
override val supportsNativeGtd: Boolean = true
```

PaperBroker, Bybit, LogBroker, CompositeBroker all inherit `false` — no change needed in those files. (`CompositeBroker` could in principle delegate to its routes, but the engine-side sweep is sound for any broker that doesn't claim native support, so leaving Composite at `false` is correct — MT5 routes inside it still self-cancel via the venue.)

- [ ] **Step 3: Run + commit**

Run: `./gradlew test --tests 'com.qkt.broker.*'`
Expected: BUILD SUCCESSFUL (no existing tests should regress).

```bash
git add src/main/kotlin/com/qkt/broker/Broker.kt \
        src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt
git commit -m "feat(broker): declare MT5 supportsNativeGtd capability"
```

---

### Task 4: `ActionCompiler` — compile + evaluate the GTD deadline

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerGtdTest.kt`

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerGtdTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionCompilerGtdTest {
    private fun compileAndEmit(src: String): OrderRequest? {
        val parsed = Parser(Lexer(src).tokenize()).parseFile()
        require(parsed is ParseResult.Success) { "parse failed: $parsed" }
        val ast = (parsed.value as ParsedFile.StrategyFile).ast
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy

        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 1, strategyId = "test") }

        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }
        // Feed a candle so the rule fires.
        val key = strategy.declaredStreams.values.first()
        val candle =
            com.qkt.marketdata.Candle(
                symbol = key.qktSymbol,
                open = BigDecimal("100"), high = BigDecimal("100"), low = BigDecimal("100"),
                close = BigDecimal("100"), volume = BigDecimal("1"),
                startTime = 0L, endTime = 60_000L,
            )
        hub.feed(
            com.qkt.marketdata.Tick(symbol = key.qktSymbol, price = BigDecimal("100"), timestamp = 60_001L),
        )
        return (signals.singleOrNull() as? Signal.Submit)?.request
    }

    @Test
    fun `TIF GTD with literal deadline stamps expiresAt on the request`() {
        val req =
            compileAndEmit(
                """
                STRATEGY g VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 0
                    THEN BUY btc LIMIT AT 100 SIZING 0.1 TIF GTD 1700001800000
                """.trimIndent(),
            ) as OrderRequest.Limit
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTD)
        assertThat(req.expiresAt).isEqualTo(1700001800000L)
    }

    @Test
    fun `TIF GTD with NOW expression stamps expiresAt computed per emit`() {
        val req =
            compileAndEmit(
                """
                STRATEGY g VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 0
                    THEN BUY btc LIMIT AT 100 SIZING 0.1 TIF GTD NOW + 3600000
                """.trimIndent(),
            ) as OrderRequest.Limit
        assertThat(req.timeInForce).isEqualTo(TimeInForce.GTD)
        // testStrategyContext() uses FixedClock(0L); NOW + 3_600_000 = 3_600_000.
        assertThat(req.expiresAt).isEqualTo(3_600_000L)
    }

    @Test
    fun `TIF GTD on a MARKET action fails compile with the pointed message`() {
        assertThatThrownBy {
            compileAndEmit(
                """
                STRATEGY g VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 0
                    THEN BUY btc SIZING 0.1 TIF GTD 1700000000000
                """.trimIndent(),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("TIF GTD is only valid on pending order types")
            .hasMessageContaining("MARKET")
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerGtdTest'`
Expected: FAIL — no `expiresAt` plumbing yet.

- [ ] **Step 3: Implement the ActionCompiler change**

In `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`, locate the submit-path block that calls `TifTranslator.translate(opts.tif)` (around line 242). Augment it to compile the GTD deadline at strategy-compile time:

```kotlin
val tif = TifTranslator.translate(opts.tif)
val gtdDeadlineExpr: CompiledExpr? =
    when (val t = opts.tif) {
        is com.qkt.dsl.ast.Gtd -> exprCompiler.compile(t.until)
        else -> null
    }

// Guard: TIF GTD requires a pending order type.
val orderType = opts.orderType ?: com.qkt.dsl.ast.Market
if (gtdDeadlineExpr != null && orderType === com.qkt.dsl.ast.Market) {
    error(
        "TIF GTD is only valid on pending order types (LIMIT/STOP/IFTOUCHED/...); " +
            "MARKET orders fill instantly and have no expiry semantic.",
    )
}
```

The variable name `orderType` already exists at the next line; rename the new local to `effectiveOrderType` to avoid shadowing if necessary, or hoist the new check above the existing line and remove the duplicate variable.

Inside the existing `return { ctx -> ... }` lambda, after the `request: OrderRequest` is constructed via `when`, stamp the deadline:

```kotlin
val deadline: Long? = gtdDeadlineExpr?.evaluate(ctx)?.let { v ->
    when (v) {
        is Value.Num -> v.value.toLong()
        else -> error("TIF GTD expression evaluated to $v; expected a numeric epoch-millis timestamp")
    }
}
val finalRequest = if (deadline != null) request.withExpiresAt(deadline) else request
```

Replace the existing `request` reference in the trailing `listOf(Signal.Submit(request))` (or equivalent) with `finalRequest`.

- [ ] **Step 4: Run + commit**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerGtdTest'`
Expected: 3 PASSED.

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ActionCompilerGtdTest.kt
git commit -m "feat(dsl): compile TIF GTD deadline and stamp expiresAt on the emitted request"
```

---

### Task 5: MT5 native GTD wire field

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Create: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorGtdTest.kt`

- [ ] **Step 1: Write failing tests**

Create the test file (study existing `MT5OrderTranslatorTest.kt` if present for the construction fixture; otherwise pattern-match from `MT5BrokerIntegrationTest`):

```kotlin
package com.qkt.broker.mt5

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5OrderTranslatorGtdTest {
    private val profile =
        MT5DefaultProfiles.exness.copy(
            instrumentOverrides = mapOf("EXNESS:EURUSD" to InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
            )),
        )
    private val translator = MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))

    private fun limit(expiresAt: Long?): OrderRequest.Limit =
        OrderRequest.Limit(
            id = "ord-1", symbol = "EXNESS:EURUSD", side = Side.BUY,
            quantity = Money.of("0.10"), limitPrice = Money.of("1.10"),
            timeInForce = if (expiresAt != null) TimeInForce.GTD else TimeInForce.GTC,
            timestamp = 0L, strategyId = "s1",
            expiresAt = expiresAt,
        )

    @Test
    fun `expiresAt populates wire expiration in seconds`() {
        val single = translator.translate(limit(1_700_001_800_000L)) as MT5Translation.Single
        assertThat(single.request.expiration).isEqualTo(1_700_001_800L)
    }

    @Test
    fun `null expiresAt leaves wire expiration null`() {
        val single = translator.translate(limit(null)) as MT5Translation.Single
        assertThat(single.request.expiration).isNull()
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew test --tests 'com.qkt.broker.mt5.MT5OrderTranslatorGtdTest'`
Expected: FAIL — translator doesn't read `expiresAt` yet.

- [ ] **Step 3: Update the translator**

In `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`, each per-shape `translate*` function (e.g., `translateLimit`, `translateStop`, `translateStopLimit`, `translateTrailingStop`, `translateBracket`) builds an `MT5OrderRequest(...)` with named arguments. Add `expiration = req.expiresAt?.let { it / 1000 }` to each call site that builds a wire request from a pending variant. For `translateStandaloneOCO`, both leg builders need the same field.

Reference the existing `MT5WireTypes.kt:48` — the field already exists on `MT5OrderRequest` as `val expiration: Long? = null`.

- [ ] **Step 4: Run + commit**

Run: `./gradlew test --tests 'com.qkt.broker.mt5.*'`
Expected: BUILD SUCCESSFUL (new tests pass + existing translator tests unchanged).

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorGtdTest.kt
git commit -m "feat(mt5): pass expiresAt into the MT5 wire expiration field"
```

---

### Task 6: `OrderManager` GTD sweep

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerGtdSweepTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerGtdSweepTest {
    private fun bus(clock: FixedClock = FixedClock(0L)) =
        EventBus(clock, MonotonicSequenceGenerator())

    private fun pendingLimit(expiresAt: Long?): OrderRequest.Limit =
        OrderRequest.Limit(
            id = "ord-1", symbol = "X", side = Side.BUY,
            quantity = Money.of("1"), limitPrice = Money.of("99"),
            timeInForce = if (expiresAt != null) TimeInForce.GTD else TimeInForce.GTC,
            timestamp = 0L, expiresAt = expiresAt,
        )

    private class GtdNativeBroker(bus: EventBus, clock: FixedClock) :
        FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT)) {
        override val supportsNativeGtd: Boolean = true
    }

    @Test
    fun `pending GTD order with expired deadline is cancelled on the next tick`() {
        val clock = FixedClock(1_000L)
        val b = bus(clock)
        val broker = FakeBroker(b, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        b.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, b, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 500L))      // already past
        clock.time = 2_000L
        b.publish(TickEvent(Tick("X", BigDecimal("100"), 2_000L)))
        assertThat(cancellations.map { it.clientOrderId }).contains("ord-1")
    }

    @Test
    fun `pending GTD order with future deadline is not cancelled`() {
        val clock = FixedClock(1_000L)
        val b = bus(clock)
        val broker = FakeBroker(b, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        b.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, b, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 10_000L))
        b.publish(TickEvent(Tick("X", BigDecimal("100"), 1_500L)))
        assertThat(cancellations).isEmpty()
    }

    @Test
    fun `pending GTD order is NOT touched by sweep when broker supportsNativeGtd is true`() {
        val clock = FixedClock(1_000L)
        val b = bus(clock)
        val broker = GtdNativeBroker(b, clock)
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        b.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, b, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 500L))      // already past
        clock.time = 2_000L
        b.publish(TickEvent(Tick("X", BigDecimal("100"), 2_000L)))
        // engine sweep skips MT5-class brokers; venue is responsible
        assertThat(cancellations).noneMatch { it.clientOrderId == "ord-1" }
    }

    @Test
    fun `order without expiresAt is never touched by the sweep`() {
        val clock = FixedClock(1_000L)
        val b = bus(clock)
        val broker = FakeBroker(b, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        b.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, b, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = null))
        b.publish(TickEvent(Tick("X", BigDecimal("100"), 9_999_999L)))
        assertThat(cancellations).isEmpty()
    }
}
```

Note: `FixedClock.time` is mutable — confirm this against the existing definition; if it isn't mutable, the test creates a fresh `FixedClock(2_000L)` before the second publish. (See `FixedClock.kt` for the contract; existing tests in this file pattern-match the right shape.)

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerGtdSweepTest'`
Expected: tests 1 and 3 FAIL (sweep doesn't exist; broker capability isn't read). Tests 2 and 4 may pass incidentally.

- [ ] **Step 3: Add the sweep**

In `src/main/kotlin/com/qkt/app/OrderManager.kt`, locate `private fun evaluateTriggers(tick: Tick)` (around line 1005). At the top of the body, before the existing for-loop, add:

```kotlin
if (!broker.supportsNativeGtd) {
    val now = clock.now()
    for (managed in orders.values.toList()) {
        if (managed.state.isTerminal) continue
        if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
        val deadline = managed.request.expiresAt ?: continue
        if (now > deadline) cancel(managed.id)
    }
}
```

The `expiresAt` field is now accessible polymorphically via the interface's default getter (Task 2 Step 3).

- [ ] **Step 4: Run + commit**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerGtdSweepTest'`
Expected: 4 PASSED.

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt \
        src/test/kotlin/com/qkt/app/OrderManagerGtdSweepTest.kt
git commit -m "feat(app): sweep pending GTD orders past their deadline on each tick"
```

---

### Task 7: Persistor round-trip for `expiresAt`

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Create: `src/test/kotlin/com/qkt/persistence/FileStatePersistorGtdTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.persistence

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorGtdTest {
    @Test
    fun `Limit with expiresAt round-trips through the persistor`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val req =
            OrderRequest.Limit(
                id = "l1", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"), limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTD, timestamp = 100L,
                strategyId = "s1", expiresAt = 1_700_001_800_000L,
            )
        persistor.savePendingOrders("s1", mapOf("l1" to req))
        val loaded = persistor.loadPendingOrders("s1")
        val back = loaded["l1"] as OrderRequest.Limit
        assertThat(back.expiresAt).isEqualTo(1_700_001_800_000L)
        assertThat(back.timeInForce).isEqualTo(TimeInForce.GTD)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew test --tests 'com.qkt.persistence.FileStatePersistorGtdTest'`
Expected: FAIL — `back.expiresAt` is null because the DTO doesn't carry it.

- [ ] **Step 3: Add `expiresAt` to the persistor DTO**

In `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`, find `OrderRequestDto` and add a nullable field:

```kotlin
@Serializable
private data class OrderRequestDto(
    // ... existing fields
    val expiresAt: Long? = null,
)
```

In `OrderRequestDto.fromDomain`, propagate `expiresAt` from the domain object for each variant that has it (Limit, Stop, StopLimit, IfTouched). The existing function compiles to:

```kotlin
is OrderRequest.Limit ->
    OrderRequestDto(
        type = "Limit",
        // existing fields…
        expiresAt = req.expiresAt,
    )
// repeat for Stop, StopLimit, IfTouched
```

In `toDomain`, when reconstructing the domain object, pass `expiresAt = dto.expiresAt` to each constructor call.

- [ ] **Step 4: Run + commit**

Run: `./gradlew test --tests 'com.qkt.persistence.FileStatePersistorGtdTest' --tests 'com.qkt.persistence.*'`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt \
        src/test/kotlin/com/qkt/persistence/FileStatePersistorGtdTest.kt
git commit -m "feat(persistence): round-trip expiresAt for pending OrderRequest variants"
```

---

### Task 8: Phase changelog

**Files:**
- Create: `docs/phases/phase-38-gtd-tif.md`

- [ ] **Step 1: Write the changelog**

Follow the qkt-skill template (`docs/phases/phase-37-proportional-stack-sizing.md` is the closest peer). Sections:

1. **Summary** (2–4 sentences): Phase 38 activates `TIF GTD <expr>` end-to-end. MT5 routes self-cancel via the venue; other brokers use OrderManager's tick-driven sweep. The hedge-straddle `:10` sweep-rule workaround can now be removed.
2. **What's new**:
   - `TimeInForce.GTD` enum value.
   - `expiresAt: Long?` on nine pending OrderRequest variants.
   - `withExpiresAt(Long?)` extension that propagates into nested sub-requests.
   - `Broker.supportsNativeGtd` capability (MT5 = true, default false).
   - MT5 wire `expiration` field populated from `expiresAt / 1000`.
   - `OrderManager.evaluateTriggers` sweeps pending orders past their deadline when the broker does not support native GTD.
   - Persistor DTO carries `expiresAt`; restart restores it.
3. **Migration from previous phase**: none. All new fields default to null; the new enum value is additive; the new broker property has a default.
4. **Usage cookbook**: at minimum three examples — relative deadline (`NOW + 30m`), absolute deadline (`1700001800000`), composite (`OCO_ENTRY ... TIF GTD NOW + 30m`).
5. **Testing patterns**: `OrderManagerGtdSweepTest` for the engine path, `MT5OrderTranslatorGtdTest` for the native path, `ActionCompilerGtdTest` for the DSL surface.
6. **Known limitations**:
   - Sweep cadence is one tick — typically sub-second on live MT5, deterministic in backtest, but not microsecond-precise.
   - MT5 venue clock-skew can affect a `NOW + Ns` deadline near the millisecond boundary.
   - `TIF GTD` rejected on Market actions at compile time.
7. **References**: spec, plan, merge commit (added on merge).

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-38-gtd-tif.md
git commit -m "docs(phases): phase 38 GTD time-in-force changelog"
```

---

### Task 9: Regression + push + PR + merge

- [ ] **Step 1: Full test sweep**

Run: `./gradlew test --tests 'com.qkt.*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. If it fails, run `./gradlew ktlintFormat` and re-stage any auto-formatted files.

- [ ] **Step 3: Push**

```bash
git push -u origin phase38-gtd-tif
```

- [ ] **Step 4: Open the PR**

Title: `[phase 38] feat(dsl): GTD time-in-force end-to-end`

PR body must follow the qkt skill template. Link the spec and plan, enumerate the eight production files, the six test files, the changelog, and the broker capability surface. Mark risk as **Medium** — touches the order lifecycle, broker capability, and persistence.

- [ ] **Step 5: Watch CI**

```bash
gh pr checks <PR#> --watch
```

- [ ] **Step 6: Merge when green**

```bash
gh pr merge <PR#> --merge --subject "merge: phase 38 GTD time-in-force" --delete-branch
git checkout dev && git pull --ff-only
```

---

## Self-review notes

- **Spec coverage:** Every spec section maps to a task — TIF enum (Task 1), expiresAt field (Task 2), broker capability (Task 3), ActionCompiler activation (Task 4), MT5 native (Task 5), OrderManager sweep (Task 6), persistence (Task 7), changelog (Task 8), merge (Task 9).
- **Type consistency:** `expiresAt: Long?` everywhere. `supportsNativeGtd: Boolean` everywhere. `withExpiresAt(expiresAt: Long?)` matches the `withStrategyId` pattern from Phase 39.
- **Placeholder check:** Task 4 Step 3 says "rename the new local to `effectiveOrderType` to avoid shadowing **if necessary**" — that's a "follow your nose" instruction rather than a placeholder. The surrounding code is shown enough that the engineer can complete it.
- **Known weakness:** Task 6 says `FixedClock.time` may or may not be mutable; the test text instructs the engineer to confirm. If immutable, the test reconstructs a fresh clock. Spelled out in the step.
