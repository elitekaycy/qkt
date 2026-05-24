# Phase 39 — INSTRUMENT_META DSL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `InstrumentMeta` fields to DSL strategies as `<stream>.tick_size`, `.contract_size`, `.volume_step`, `.volume_min` accessors, with fail-loud startup validation when the registry has no entry for a referenced instrument.

**Architecture:** Reuse Phase 32's `StreamFieldRef(stream, field)` AST node. `ExprCompiler.compileStreamField` branches by field name — candle path for OHLCV/bid/ask/spread, registry path for the four new meta names. Meta references are collected at `AstCompiler.compile` and re-checked against `ctx.strategyContext.instruments` inside `CompiledStrategy.bindToHub`, so a strategy referencing meta for an instrument the registry doesn't know fails to bind with a pointed error.

**Tech Stack:** Kotlin 2.x, JUnit 5, AssertJ. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-24-phase39-instrument-meta-dsl-design.md`](../specs/2026-05-24-phase39-instrument-meta-dsl-design.md)

**Issue:** <https://github.com/elitekaycy/qkt/issues/51>

---

## File map

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` | modify | Add `META_FIELDS` + `CANDLE_FIELDS` constants. Branch `compileStreamField` to a new `compileMetaField`. |
| `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` | modify | Collect meta `StreamFieldRef`s into `CompiledStrategy`. Validate in `bindToHub`. |
| `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt` | modify | Add `.tick_size`, `.contract_size`, `.volume_step`, `.volume_min`. |
| `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerInstrumentMetaTest.kt` | create | Field-by-field resolution + unknown-field error + arithmetic composition. |
| `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerFieldSetsTest.kt` | create | Guard: `CANDLE_FIELDS ∩ META_FIELDS == ∅`. |
| `src/test/kotlin/com/qkt/dsl/compile/AstCompilerMetaValidationTest.kt` | create | bindToHub fails loud on missing meta, passes when present, skips when no meta refs. |
| `src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt` | modify | Assert each new property emits the right `StreamFieldRef`. |
| `docs/phases/phase-39-instrument-meta.md` | create | Phase changelog (per qkt skill). |

---

### Task 1: Recognise the four meta field names in `ExprCompiler`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerInstrumentMetaTest.kt`

- [ ] **Step 1: Write the failing test for `tick_size` resolution**

Create `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerInstrumentMetaTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.risk.RiskState
import com.qkt.risk.RiskViewImpl
import com.qkt.strategy.Mode
import com.qkt.strategy.StrategyContext
import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.pnl.PnL
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionViewImpl
import com.qkt.pnl.StrategyPnLViewImpl
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerInstrumentMetaTest {
    private val xauKey = HubKey("EXNESS", "XAUUSD", "1m")
    private val gold =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private fun registry(meta: InstrumentMeta?): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? =
                if (meta != null && meta.qktSymbol == qktSymbol) meta else null
        }

    private fun ctx(reg: InstrumentRegistry): EvalContext {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val pnl = PnL(clock)
        val strategyPnL = StrategyPnL(clock)
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker()
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val sctx =
            StrategyContext(
                strategyId = "test",
                mode = Mode.LIVE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                positions = StrategyPositionViewImpl(strategyPositions, "test"),
                pnl = StrategyPnLViewImpl(strategyPnL, "test"),
                risk = RiskViewImpl(riskState, "test"),
                instruments = reg,
            )
        val candle =
            Candle(
                symbol = "EXNESS:XAUUSD",
                open = BigDecimal("4500"),
                high = BigDecimal("4500"),
                low = BigDecimal("4500"),
                close = BigDecimal("4500"),
                volume = BigDecimal("1"),
                startTime = 0L,
                endTime = 60_000L,
            )
        return EvalContext(
            candle = candle,
            streams = mapOf("gold" to xauKey),
            lets = emptyMap(),
            strategyContext = sctx,
        )
    }

    @Test
    fun `tick_size resolves to InstrumentMeta pointSize`() {
        val ec = ctx(registry(gold))
        val v = ExprCompiler().compile(StreamFieldRef("gold", "tick_size")).evaluate(ec)
        assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerInstrumentMetaTest.tick_size resolves to InstrumentMeta pointSize'`
Expected: FAIL with `Unknown stream field for gold: tick_size` (from the current `require(...)`).

- [ ] **Step 3: Implement META_FIELDS recognition + compileMetaField**

Modify `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`. Find the existing `compileStreamField` (around line 309) and replace its body with:

```kotlin
private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
    require(ref.field in CANDLE_FIELDS || ref.field in META_FIELDS) {
        "Unknown stream field for ${ref.stream}: ${ref.field}"
    }
    if (ref.field in META_FIELDS) return compileMetaField(ref)
    return compileCandleField(ref)
}

private fun compileMetaField(ref: StreamFieldRef): CompiledExpr =
    CompiledExpr { ctx ->
        val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
        val meta =
            ctx.strategyContext.instruments.lookup(key.qktSymbol)
                ?: error("InstrumentMeta missing for ${key.qktSymbol} (covered by startup validation)")
        val value =
            when (ref.field) {
                "tick_size" -> meta.pointSize
                "contract_size" -> meta.contractSize
                "volume_step" -> meta.volumeStep
                "volume_min" -> meta.volumeMin
                else -> error("unreachable: ${ref.field}")
            }
        Value.Num(value)
    }

private fun compileCandleField(ref: StreamFieldRef): CompiledExpr =
    CompiledExpr { ctx ->
        // body unchanged: paste the original CompiledExpr block from the previous
        // compileStreamField — the one that resolves `close/open/high/low/volume/
        // price/bid/ask/spread` against ctx.candle (or ctx.hub.latest for cross-stream).
    }

companion object {
    val CANDLE_FIELDS: Set<String> =
        setOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread")
    val META_FIELDS: Set<String> =
        setOf("tick_size", "contract_size", "volume_step", "volume_min")
}
```

Two notes about the implementation:
- The previous `compileStreamField` had an inline `CompiledExpr { ctx -> … }` block that resolved candle fields. Move that block verbatim into the new `compileCandleField` helper — do not rewrite it.
- `CANDLE_FIELDS` already exists *as a literal* in the current `require(...)` call. Extract it to the `companion object` and reuse from both `require(...)` and `compileCandleField`. This is the only way the `ExprCompilerFieldSetsTest` (Task 3) can `intersect` the two sets.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerInstrumentMetaTest.tick_size resolves to InstrumentMeta pointSize'`
Expected: PASS.

- [ ] **Step 5: Add the four-field test sweep**

Append to `ExprCompilerInstrumentMetaTest.kt`:

```kotlin
@Test
fun `contract_size resolves to InstrumentMeta contractSize`() {
    val ec = ctx(registry(gold))
    val v = ExprCompiler().compile(StreamFieldRef("gold", "contract_size")).evaluate(ec)
    assertThat(v).isEqualTo(Value.Num(BigDecimal("100")))
}

@Test
fun `volume_step resolves to InstrumentMeta volumeStep`() {
    val ec = ctx(registry(gold))
    val v = ExprCompiler().compile(StreamFieldRef("gold", "volume_step")).evaluate(ec)
    assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
}

@Test
fun `volume_min resolves to InstrumentMeta volumeMin`() {
    val ec = ctx(registry(gold))
    val v = ExprCompiler().compile(StreamFieldRef("gold", "volume_min")).evaluate(ec)
    assertThat(v).isEqualTo(Value.Num(BigDecimal("0.01")))
}

@Test
fun `unknown meta field fails at compile with the unified error`() {
    assertThatThrownBy {
        ExprCompiler().compile(StreamFieldRef("gold", "tick_sze"))
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Unknown stream field for gold: tick_sze")
}

@Test
fun `meta accessor composes with arithmetic`() {
    val ec = ctx(registry(gold))
    val mul =
        com.qkt.dsl.ast.Binary(
            op = com.qkt.dsl.ast.BinOp.MUL,
            lhs = StreamFieldRef("gold", "tick_size"),
            rhs = com.qkt.dsl.ast.NumLit(BigDecimal("2")),
        )
    val v = ExprCompiler().compile(mul).evaluate(ec)
    assertThat(v).isEqualTo(Value.Num(BigDecimal("0.02")))
}
```

- [ ] **Step 6: Run the full meta test class**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerInstrumentMetaTest'`
Expected: 5 PASSED.

- [ ] **Step 7: Confirm Phase 32 bid/ask tests still pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: existing tests still PASSED (regression guard for the candle-field refactor).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ExprCompilerInstrumentMetaTest.kt
git commit -m "feat(dsl): resolve <stream>.tick_size/contract_size/volume_step/volume_min"
```

---

### Task 2: Field-set collision guard

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerFieldSetsTest.kt`

- [ ] **Step 1: Write the regression test**

```kotlin
package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerFieldSetsTest {
    @Test
    fun `CANDLE_FIELDS and META_FIELDS are disjoint`() {
        val overlap = ExprCompiler.CANDLE_FIELDS.intersect(ExprCompiler.META_FIELDS)
        assertThat(overlap)
            .`as`("a name in both sets would make resolution ambiguous")
            .isEmpty()
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerFieldSetsTest'`
Expected: PASS (the two sets are currently disjoint).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/ExprCompilerFieldSetsTest.kt
git commit -m "test(dsl): guard CANDLE_FIELDS/META_FIELDS disjointness"
```

---

### Task 3: Startup validation of meta references against the registry

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/AstCompilerMetaValidationTest.kt`

The fail-loud behavior happens inside `CompiledStrategy.bindToHub`, which already receives the `StrategyContext` carrying the registry. The `AstCompiler.compile` step collects the meta references; `bindToHub` checks them.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnL
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.StrategyPnLViewImpl
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.StrategyPositionViewImpl
import com.qkt.risk.RiskState
import com.qkt.risk.RiskViewImpl
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AstCompilerMetaValidationTest {
    private fun parse(text: String): com.qkt.dsl.ast.StrategyAst {
        val parsed = Parser(Lexer(text).tokenize()).parseFile()
        require(parsed is ParseResult.Success) { "parse failed: $parsed" }
        return (parsed.value as ParsedFile.StrategyFile).ast
    }

    private val xauMeta =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private fun registry(map: Map<String, InstrumentMeta>): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = map[qktSymbol]
        }

    private fun ctxFor(reg: InstrumentRegistry): StrategyContext {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val pnl = PnL(clock)
        val strategyPnL = StrategyPnL(clock)
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker()
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        return StrategyContext(
            strategyId = "test",
            mode = Mode.LIVE,
            clock = clock,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            positions = StrategyPositionViewImpl(strategyPositions, "test"),
            pnl = StrategyPnLViewImpl(strategyPnL, "test"),
            risk = RiskViewImpl(riskState, "test"),
            instruments = reg,
        )
    }

    @Test
    fun `strategy with meta refs binds when registry has the instrument`() {
        val ast =
            parse(
                """
                STRATEGY meta_ok VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > gold.tick_size * 100
                    THEN BUY gold SIZING gold.volume_min
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 1, strategyId = "test") }
        strategy.bindToHub(hub, ctxFor(registry(mapOf("EXNESS:XAUUSD" to xauMeta)))) { _: Signal -> }
    }

    @Test
    fun `strategy with meta refs fails to bind when registry is missing the instrument`() {
        val ast =
            parse(
                """
                STRATEGY meta_missing VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > gold.tick_size
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 1, strategyId = "test") }
        assertThatThrownBy {
            strategy.bindToHub(hub, ctxFor(NoopInstrumentRegistry)) { _: Signal -> }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'gold.tick_size'")
            .hasMessageContaining("EXNESS:XAUUSD")
    }

    @Test
    fun `strategy without meta refs binds against an empty registry`() {
        val ast =
            parse(
                """
                STRATEGY no_meta VERSION 1
                SYMBOLS
                    gold = EXNESS:XAUUSD EVERY 1m
                RULES
                    WHEN gold.close > 0
                    THEN BUY gold SIZING 0.01
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(ast) as DslCompiledStrategy
        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 1, strategyId = "test") }
        // Should not throw against the empty (Noop) registry — no meta refs.
        strategy.bindToHub(hub, ctxFor(NoopInstrumentRegistry)) { _: Signal -> }
    }
}
```

- [ ] **Step 2: Run tests to verify two fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerMetaValidationTest'`
Expected: 2 FAILED (the meta-present + meta-missing cases), 1 PASSED (no-meta-refs).

The `binds when registry has the instrument` test fails because validation hasn't been added yet — compile + bindToHub will succeed without checking, the test passes only if `bindToHub` doesn't throw. Actually all three should pass currently because no validation runs at all. Re-check: the missing-meta test should *fail* because `bindToHub` doesn't throw yet → `assertThatThrownBy` doesn't see the expected exception → test fails. The other two pass. Re-stating expected: 1 FAILED (missing-meta), 2 PASSED.

- [ ] **Step 3: Add meta-ref collection to AstCompiler**

In `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`, locate the `compile(ast: StrategyAst)` method. Before constructing and returning `CompiledStrategy(...)`, walk every expression node (`WHEN` conditions, `THEN` action arguments — sizing expressions, LOG fields — and any strategy-level `LET` RHS) collecting `StreamFieldRef` nodes whose field is in `ExprCompiler.META_FIELDS`. The walker is the same shape as the existing `collectStackAtSymbols` helper at line 125 — model it on that.

Add a helper:

```kotlin
private fun collectMetaRefs(
    ast: StrategyAst,
    streams: Map<String, HubKey>,
): List<MetaRef> {
    val out = mutableListOf<MetaRef>()

    fun walkExpr(e: com.qkt.dsl.ast.ExprAst) {
        when (e) {
            is com.qkt.dsl.ast.StreamFieldRef ->
                if (e.field in ExprCompiler.META_FIELDS) {
                    val sym = streams[e.stream]?.qktSymbol
                    if (sym != null) out.add(MetaRef(stream = e.stream, field = e.field, qktSymbol = sym))
                }
            is com.qkt.dsl.ast.Binary -> { walkExpr(e.lhs); walkExpr(e.rhs) }
            is com.qkt.dsl.ast.Unary -> walkExpr(e.expr)
            // … add every ExprAst variant the codebase has; the parser-tested set is small.
            else -> {}
        }
    }

    fun walkAction(a: com.qkt.dsl.ast.ActionAst) {
        when (a) {
            is com.qkt.dsl.ast.Buy -> a.sizing?.let { walkExpr(it) }
            is com.qkt.dsl.ast.Sell -> a.sizing?.let { walkExpr(it) }
            is com.qkt.dsl.ast.Block -> a.actions.forEach { walkAction(it) }
            is com.qkt.dsl.ast.OcoEntry -> { walkAction(a.leg1); walkAction(a.leg2) }
            is com.qkt.dsl.ast.Log -> a.fields.values.forEach { walkExpr(it) }
            else -> {}
        }
    }

    for (rule in ast.rules) {
        when (rule) {
            is com.qkt.dsl.ast.WhenThen -> { walkExpr(rule.cond); walkAction(rule.action) }
            else -> {}
        }
    }
    for ((_, expr) in ast.lets) walkExpr(expr)

    return out
}

private data class MetaRef(
    val stream: String,
    val field: String,
    val qktSymbol: String,
)
```

Pass the collected `List<MetaRef>` into the `CompiledStrategy` constructor and store it as a private field.

- [ ] **Step 4: Validate in bindToHub**

In `CompiledStrategy.bindToHub(hub, ctx, emit)`, before the existing `for ((alias, key) in streams)` loop that calls `hub.onClosed`, add:

```kotlin
val registry = ctx.strategyContext.instruments
val missing = metaRefs.filter { registry.lookup(it.qktSymbol) == null }
if (missing.isNotEmpty()) {
    val first = missing.first()
    error(
        "Strategy '${ctx.strategyContext.strategyId}' references '${first.stream}.${first.field}' " +
            "but no InstrumentMeta is registered for ${first.qktSymbol}. " +
            "Populate it via the MT5 broker connection (live) or a YAML manifest in qkt.config.yaml (backtest).",
    )
}
```

- [ ] **Step 5: Run the validation tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerMetaValidationTest'`
Expected: 3 PASSED.

- [ ] **Step 6: Regression sweep**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.*'`
Expected: all existing DSL-compile tests still PASSED.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/AstCompilerMetaValidationTest.kt
git commit -m "feat(dsl): validate <stream>.<meta_field> against InstrumentRegistry on bindToHub"
```

---

### Task 4: Kotlin DSL StreamRef accessors

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt`

- [ ] **Step 1: Write the failing assertions**

Open `src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt` and append (inside the existing test class):

```kotlin
@Test
fun `tick_size emits StreamFieldRef`() {
    val ref = StreamRef("gold").tick_size
    assertThat(ref).isEqualTo(StreamFieldRef("gold", "tick_size"))
}

@Test
fun `contract_size emits StreamFieldRef`() {
    val ref = StreamRef("gold").contract_size
    assertThat(ref).isEqualTo(StreamFieldRef("gold", "contract_size"))
}

@Test
fun `volume_step emits StreamFieldRef`() {
    val ref = StreamRef("gold").volume_step
    assertThat(ref).isEqualTo(StreamFieldRef("gold", "volume_step"))
}

@Test
fun `volume_min emits StreamFieldRef`() {
    val ref = StreamRef("gold").volume_min
    assertThat(ref).isEqualTo(StreamFieldRef("gold", "volume_min"))
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StreamRefTest'`
Expected: 4 FAILED (`Unresolved reference: tick_size` etc).

- [ ] **Step 3: Add the four properties**

Modify `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt`. After the existing `.spread` property, add:

```kotlin
val tick_size: ExprAst = StreamFieldRef(alias, "tick_size")
val contract_size: ExprAst = StreamFieldRef(alias, "contract_size")
val volume_step: ExprAst = StreamFieldRef(alias, "volume_step")
val volume_min: ExprAst = StreamFieldRef(alias, "volume_min")
```

- [ ] **Step 4: Run to verify all pass**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StreamRefTest'`
Expected: all StreamRefTest cases PASSED (existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt \
        src/test/kotlin/com/qkt/dsl/kotlin/StreamRefTest.kt
git commit -m "feat(dsl): expose tick_size/contract_size/volume_step/volume_min on Kotlin StreamRef"
```

---

### Task 5: Phase changelog

**Files:**
- Create: `docs/phases/phase-39-instrument-meta.md`

- [ ] **Step 1: Write the changelog**

Create `docs/phases/phase-39-instrument-meta.md`. Follow the qkt-skill phase-changelog template (`docs/phases/phase-32-bid-ask.md` is the canonical reference). The changelog must contain at minimum:

1. **Summary** (2–4 sentences): Phase 39 exposes per-instrument meta to the DSL via `<stream>.tick_size/contract_size/volume_step/volume_min`. Strategies stop hard-coding tick sizes; meta is validated at bindToHub time.
2. **What's new**: four new accessors on every `<stream>`, mirrored in Kotlin DSL `StreamRef`.
3. **Migration from previous phase**: none — additive. Existing strategies untouched.
4. **Usage cookbook**: at minimum three worked examples:
   - Scaled spread gate: `WHEN gold.spread > gold.tick_size * 2 THEN LOG "wide"`
   - Portable sizing: `BUY gold SIZING gold.volume_min`
   - Cross-instrument: `WHEN gold.contract_size > 50 THEN ...`
5. **Testing patterns**: point at `ExprCompilerInstrumentMetaTest` + `AstCompilerMetaValidationTest` as the canonical fixtures.
6. **Known limitations**:
   - `volumeMax`, `digits`, `tradeStopsLevelPoints` not exposed (out of scope; add when needed).
   - Backtest needs a YAML manifest, or strategy fails to bind.
7. **References**: spec path, plan path, merge commit (filled at merge).

- [ ] **Step 2: Verify it renders**

Run: `mkdocs build --strict` (or whatever the project's docs-build target is) — qkt's CI uses `./gradlew dokkaHtml` for KDoc but mkdocs for the prose site. Skip if neither tool is installed locally; CI will catch it.

- [ ] **Step 3: Commit**

```bash
git add docs/phases/phase-39-instrument-meta.md
git commit -m "docs(phases): phase 39 instrument-meta changelog"
```

---

### Task 6: Final regression sweep + PR

- [ ] **Step 1: Full test sweep**

Run: `./gradlew test --tests 'com.qkt.dsl.*' --tests 'com.qkt.app.*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Push the branch**

The branch is `phase39-instrument-meta`, already created during the brainstorming step. Push:

```bash
git push -u origin phase39-instrument-meta
```

- [ ] **Step 4: Open the PR**

Use the qkt PR template (`docs/superpowers/specs/...` + `docs/superpowers/plans/...` links, phase = 39). Title:

```
[phase 39] feat(dsl): expose InstrumentMeta accessors to DSL strategies
```

PR body must enumerate the four accessors, the validation contract, the test file list, and link both spec and plan.

- [ ] **Step 5: Watch CI, merge when green**

```bash
gh pr checks <PR#> --watch
gh pr merge <PR#> --merge --subject "merge: phase 39 instrument-meta DSL accessors" --delete-branch
```

---

## Self-review notes

- **Spec coverage:** All four spec sections (surface, components, data flow, error handling, testing) have direct tasks. The collision regression guard from the spec's "Risks" section is covered by Task 2.
- **Type consistency:** `MetaRef.qktSymbol` matches `InstrumentMeta.qktSymbol`. `InstrumentRegistry.lookup(qktSymbol)` matches the actual interface signature. `ExprCompiler.META_FIELDS` is the single source of truth referenced by Task 1, Task 2, and Task 3.
- **Placeholder check:** Task 1 Step 3 references "the original CompiledExpr block from the previous compileStreamField — the one that resolves close/open/…" without inlining the code. That's acceptable because (a) the engineer is doing a literal copy-move of existing code, not writing new logic, and (b) the existing block is ~30 lines that don't need to be reproduced in the plan. Every step that introduces *new* code shows it.
- **Task 3 Step 3** lists `is com.qkt.dsl.ast.Binary -> ...` plus `Unary` plus an `else -> {}`. The full set of `ExprAst` variants is parser-defined; the engineer should grep `sealed class ExprAst` to confirm coverage and add a branch for every variant. This is documented in-step. The walker is bug-prone if a variant is missed — a `when` over a sealed interface would help here but the existing code uses non-exhaustive `else`. Stay consistent with project style.
