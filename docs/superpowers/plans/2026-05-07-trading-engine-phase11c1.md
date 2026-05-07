# Phase 11c1 — Expressive Core (State, Range, Conditional, Cross, Composition, Math) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the DSL with the non-stateful expressive surface from the master spec — engine state accessors that read existing views (ACCOUNT.realized_pnl/unrealized_pnl/total_pnl, POSITION.<sym>, POSITION_AVG_PRICE.<sym>), `BETWEEN`/`IN`/`CASE WHEN`, `CROSSES ABOVE`/`CROSSES BELOW`, indicator-on-indicator composition, and a stdlib math function family (`ABS`/`SQRT`/`LOG`/`MIN`/`MAX`). Snapshots, running aggregates, and new actions (`Log`/`CLOSE`/`CANCEL`) are deferred to 11c2 and 11c3.

**Architecture:** Three structural changes to existing 11b code: (a) `EvalContext` carries `StrategyContext` so state accessors can read live engine state; (b) `IndicatorBinding.bind()` recursively binds nested `IndicatorCall` series inputs and feeds inner-indicator output to outer-indicator update; (c) `Crosses` introduces per-node mutable state inside the otherwise-stateless `CompiledExpr`. New AST node `FuncCall(name, args)` + `FuncRegistry` host the math stdlib without conflating it with `IndicatorCall` (which is stateful). All other additions are pure-function compile branches.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 — Phase 11c1.

**Branch:** `phase11c1-state-and-operators` (already cut from `main`).

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/dsl/ast/
└── FuncCall.kt                         # FuncCall AST node (pure-function call)

src/main/kotlin/com/qkt/dsl/stdlib/
└── FuncRegistry.kt                     # ABS/SQRT/LOG/MIN/MAX

src/main/kotlin/com/qkt/dsl/compile/
└── CrossesState.kt                     # per-node prev-bar state for CROSSES

src/test/kotlin/com/qkt/dsl/ast/
└── FuncCallTest.kt

src/test/kotlin/com/qkt/dsl/stdlib/
└── FuncRegistryTest.kt

src/test/kotlin/com/qkt/dsl/compile/
├── ExprCompilerStateTest.kt            # AccountRef, PositionRef, StateAccessor
├── ExprCompilerBetweenInCaseTest.kt    # BETWEEN, IN, CASE WHEN
├── ExprCompilerCrossesTest.kt          # CROSSES ABOVE / BELOW
├── ExprCompilerCompositionTest.kt      # indicator-on-indicator
└── ExprCompilerFuncCallTest.kt         # math stdlib

src/test/kotlin/com/qkt/dsl/kotlin/
└── ExpressiveCoreBuildersTest.kt       # crosses/between/inList/case-when/account/position helpers + math
```

### Modified files

```
src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt    # EvalContext gets strategyContext
src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt     # CompiledStrategy passes ctx into EvalContext
src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt    # new compile branches for state/range/cond/cross/funccall/composition
src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt # nested IndicatorCall as series arg
src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt     # FuncCall pass-through (already handles AccountRef etc.)
src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt             # add FuncCall to sealed hierarchy

src/main/kotlin/com/qkt/dsl/kotlin/ExprBuilders.kt     # crossesAbove/crossesBelow infix, between, inList
src/main/kotlin/com/qkt/dsl/kotlin/StateRefs.kt        # NEW: account/position helpers (factor under kotlin pkg)
src/main/kotlin/com/qkt/dsl/kotlin/MathBuilders.kt     # NEW: abs/sqrt/log/min/max helpers

src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLiteralsTest.kt
src/test/kotlin/com/qkt/dsl/compile/ExprCompilerOperatorsTest.kt
src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt
src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLetTest.kt        # if any need ctx (most don't)
src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIndicatorTest.kt
src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt
src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt        # remove now-supported asserts; keep ones still unsupported (Aggregate, snapshots)
```

---

## Tasks

### Task 1: Extend `EvalContext` with `StrategyContext`

State accessors need live engine state at evaluation time. We thread it through `EvalContext`. Existing tests construct `EvalContext` directly; they update to use `testStrategyContext()`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLiteralsTest.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerOperatorsTest.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIndicatorTest.kt`

- [ ] **Step 1: Update `EvalContext` to require `StrategyContext`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

sealed interface Value {
    data class Num(val v: BigDecimal) : Value

    data class Bool(val v: Boolean) : Value

    data object Undefined : Value
}

class EvalContext(
    val candle: Candle,
    val streamSymbols: Map<String, String>,
    val lets: Map<String, BigDecimal>,
    val strategyContext: StrategyContext,
)

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
```

- [ ] **Step 2: Wire `strategyContext` through `CompiledStrategy.onCandle`**

In `AstCompiler.kt`:

```kotlin
override fun onCandle(
    candle: Candle,
    ctx: StrategyContext,
    emit: (Signal) -> Unit,
) {
    if (candle.symbol !in streamSymbols.values) return
    val ec =
        EvalContext(
            candle = candle,
            streamSymbols = streamSymbols,
            lets = emptyMap(),
            strategyContext = ctx,
        )
    bindings.updateAll(ec)
    for (rule in rules) {
        val sig = rule.fire(ec)
        if (sig != null) emit(sig)
    }
}
```

- [ ] **Step 3: Update existing direct-construction tests**

Anywhere `EvalContext(candle = ..., streamSymbols = ..., lets = emptyMap())` appears, add `strategyContext = testStrategyContext()` and import `com.qkt.strategy.testStrategyContext`.

Affected files: `ExprCompilerLiteralsTest`, `ExprCompilerOperatorsTest`, `ExprCompilerStreamFieldTest`, `ExprCompilerIndicatorTest`. (`ExprCompilerLetTest` doesn't construct `EvalContext` — it tests the resolver, which doesn't touch ctx.)

- [ ] **Step 4: Run existing test suite**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.*'`
Expected: all existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLiteralsTest.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerOperatorsTest.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIndicatorTest.kt
git commit -m "feat(dsl): thread StrategyContext through EvalContext"
```

---

### Task 2: Compile `AccountRef`

`AccountRef("realized_pnl" | "unrealized_pnl" | "total_pnl")` reads from `ctx.strategyContext.pnl`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerStateTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctxWithPnl(
        realized: BigDecimal = BigDecimal.ZERO,
        unrealizedTotal: BigDecimal = BigDecimal.ZERO,
    ): EvalContext {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = realized

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = unrealizedTotal

                override fun total(): BigDecimal = realized.add(unrealizedTotal)
            }
        val sc = testStrategyContext(pnl = pnl)
        return EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = sc,
        )
    }

    @Test
    fun `ACCOUNT realized_pnl reads from pnl view`() {
        val ec = ExprCompiler()
        val v = ec.compile(AccountRef("realized_pnl")).evaluate(ctxWithPnl(realized = BigDecimal("123.45"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123.45")
    }

    @Test
    fun `ACCOUNT unrealized_pnl reads from pnl view`() {
        val ec = ExprCompiler()
        val v = ec.compile(AccountRef("unrealized_pnl"))
            .evaluate(ctxWithPnl(unrealizedTotal = BigDecimal("7.5"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7.5")
    }

    @Test
    fun `ACCOUNT total_pnl reads from pnl view`() {
        val ec = ExprCompiler()
        val v = ec.compile(AccountRef("total_pnl"))
            .evaluate(ctxWithPnl(realized = BigDecimal("10"), unrealizedTotal = BigDecimal("5"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("15")
    }

    @Test
    fun `unsupported ACCOUNT field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(AccountRef("equity")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: FAIL — `AccountRef` still hits the `else error()` branch.

- [ ] **Step 3: Add an `AccountRef` branch to `ExprCompiler`**

Add to the `when (expr)` block:

```kotlin
            is AccountRef -> compileAccountRef(expr)
```

And the helper:

```kotlin
    private fun compileAccountRef(ref: AccountRef): CompiledExpr {
        require(ref.field in setOf("realized_pnl", "unrealized_pnl", "total_pnl")) {
            "Unsupported ACCOUNT field in 11c1: ${ref.field} (equity/balance/drawdown deferred — engine surface needs work)"
        }
        return CompiledExpr { ctx ->
            val pnl = ctx.strategyContext.pnl
            Value.Num(
                when (ref.field) {
                    "realized_pnl" -> pnl.realized()
                    "unrealized_pnl" -> pnl.unrealizedTotal()
                    "total_pnl" -> pnl.total()
                    else -> error("unreachable")
                },
            )
        }
    }
```

Don't forget to import `AccountRef`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt
git commit -m "feat(dsl): compile AccountRef from pnl view"
```

---

### Task 3: Compile `PositionRef`

`PositionRef("btc")` reads `ctx.strategyContext.positions.positionFor(symbol)?.quantity ?: ZERO`. Stream alias resolves through `streamSymbols` like every other alias.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `ExprCompilerStateTest.kt`:

```kotlin
    @Test
    fun `POSITION reads signed quantity from positions view`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
            }
        val sc = testStrategyContext(positions = pos)
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = sc,
            )
        val v = ExprCompiler().compile(com.qkt.dsl.ast.PositionRef("btc")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("2.5")
    }

    @Test
    fun `POSITION on unknown symbol is zero`() {
        val sc = testStrategyContext()
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = sc,
            )
        val v = ExprCompiler().compile(com.qkt.dsl.ast.PositionRef("btc")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }

    @Test
    fun `POSITION on unknown stream alias errors at evaluation`() {
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        org.assertj.core.api.Assertions.assertThatThrownBy {
            ExprCompiler().compile(com.qkt.dsl.ast.PositionRef("btc")).evaluate(ec)
        }.isInstanceOf(IllegalStateException::class.java)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: FAIL.

- [ ] **Step 3: Add `PositionRef` branch to `ExprCompiler`**

```kotlin
            is PositionRef -> compilePositionRef(expr)
```

```kotlin
    private fun compilePositionRef(ref: PositionRef): CompiledExpr =
        CompiledExpr { ctx ->
            val symbol = ctx.streamSymbols[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val qty = ctx.strategyContext.positions.positionFor(symbol)?.quantity ?: java.math.BigDecimal.ZERO
            Value.Num(qty)
        }
```

Import `PositionRef`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt
git commit -m "feat(dsl): compile PositionRef from positions view"
```

---

### Task 4: Compile `StateAccessor` for `POSITION_AVG_PRICE`

`StateAccessor(StateSource.POSITION_AVG_PRICE, "btc")` → `positions.positionFor(symbol)?.avgEntryPrice ?: ZERO`.

The other `StateSource` variants stay unsupported in 11c1 (`OPEN_ORDERS` needs broker surface; `ACCOUNT`/`POSITION` are handled by their own AST nodes).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt`

- [ ] **Step 1: Add failing test**

Append:

```kotlin
    @Test
    fun `POSITION_AVG_PRICE reads avg entry price`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    com.qkt.positions.Position("BTCUSDT", BigDecimal("1"), BigDecimal("105.50"))

                override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
            }
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val v =
            ExprCompiler().compile(
                com.qkt.dsl.ast.StateAccessor(com.qkt.dsl.ast.StateSource.POSITION_AVG_PRICE, "btc"),
            ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105.50")
    }

    @Test
    fun `OPEN_ORDERS state is rejected in 11c1`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            ExprCompiler()
                .compile(com.qkt.dsl.ast.StateAccessor(com.qkt.dsl.ast.StateSource.OPEN_ORDERS, "btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: FAIL.

- [ ] **Step 3: Add `StateAccessor` branch to `ExprCompiler`**

```kotlin
            is StateAccessor -> compileStateAccessor(expr)
```

```kotlin
    private fun compileStateAccessor(ref: StateAccessor): CompiledExpr {
        require(ref.source == StateSource.POSITION_AVG_PRICE) {
            "StateAccessor source ${ref.source} is not supported in 11c1"
        }
        return CompiledExpr { ctx ->
            val symbol = ctx.streamSymbols[ref.key] ?: error("Unknown stream alias: ${ref.key}")
            val price =
                ctx.strategyContext.positions.positionFor(symbol)?.avgEntryPrice ?: java.math.BigDecimal.ZERO
            Value.Num(price)
        }
    }
```

Import `StateAccessor`, `StateSource`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStateTest.kt
git commit -m "feat(dsl): compile POSITION_AVG_PRICE state accessor"
```

---

### Task 5: Compile `Between`

`Between(v, lo, hi)` evaluates `lo <= v && v <= hi`. Undefined-contagious.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerBetweenInCaseTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `BETWEEN includes endpoints`() {
        val ec = ExprCompiler()
        assertThat(
            (ec.compile(Between(NumLit(BigDecimal("5")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10")))).evaluate(ctx) as Value.Bool).v,
        ).isTrue()
        assertThat(
            (ec.compile(Between(NumLit(BigDecimal("1")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10")))).evaluate(ctx) as Value.Bool).v,
        ).isTrue()
        assertThat(
            (ec.compile(Between(NumLit(BigDecimal("10")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10")))).evaluate(ctx) as Value.Bool).v,
        ).isTrue()
    }

    @Test
    fun `BETWEEN excludes outside`() {
        val ec = ExprCompiler()
        assertThat(
            (ec.compile(Between(NumLit(BigDecimal("0")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10")))).evaluate(ctx) as Value.Bool).v,
        ).isFalse()
        assertThat(
            (ec.compile(Between(NumLit(BigDecimal("11")), NumLit(BigDecimal("1")), NumLit(BigDecimal("10")))).evaluate(ctx) as Value.Bool).v,
        ).isFalse()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerBetweenInCaseTest'`
Expected: FAIL.

- [ ] **Step 3: Add `Between` branch to `ExprCompiler`**

```kotlin
            is Between -> compileBetween(expr)
```

```kotlin
    private fun compileBetween(b: Between): CompiledExpr {
        val v = compile(b.v)
        val lo = compile(b.lo)
        val hi = compile(b.hi)
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            val lov = lo.evaluate(ctx)
            val hiv = hi.evaluate(ctx)
            if (vv !is Value.Num || lov !is Value.Num || hiv !is Value.Num) {
                Value.Undefined
            } else {
                Value.Bool(vv.v >= lov.v && vv.v <= hiv.v)
            }
        }
    }
```

Import `Between`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerBetweenInCaseTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt
git commit -m "feat(dsl): compile BETWEEN range check"
```

---

### Task 6: Compile `InList`

`InList(v, members)` evaluates `members.any { it == v }` numerically.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt`

- [ ] **Step 1: Add failing tests**

Append:

```kotlin
    @Test
    fun `IN-list matches numeric members`() {
        val ec = ExprCompiler()
        val expr =
            com.qkt.dsl.ast.InList(
                NumLit(BigDecimal("3")),
                listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("3")), NumLit(BigDecimal("5"))),
            )
        assertThat((ec.compile(expr).evaluate(ctx) as Value.Bool).v).isTrue()
    }

    @Test
    fun `IN-list misses non-members`() {
        val ec = ExprCompiler()
        val expr =
            com.qkt.dsl.ast.InList(
                NumLit(BigDecimal("4")),
                listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("3")), NumLit(BigDecimal("5"))),
            )
        assertThat((ec.compile(expr).evaluate(ctx) as Value.Bool).v).isFalse()
    }

    @Test
    fun `IN-list empty members is always false`() {
        val ec = ExprCompiler()
        val expr = com.qkt.dsl.ast.InList(NumLit(BigDecimal("1")), emptyList())
        assertThat((ec.compile(expr).evaluate(ctx) as Value.Bool).v).isFalse()
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerBetweenInCaseTest'`
Expected: FAIL.

- [ ] **Step 3: Add `InList` branch**

```kotlin
            is InList -> compileInList(expr)
```

```kotlin
    private fun compileInList(expr: InList): CompiledExpr {
        val v = compile(expr.v)
        val members = expr.members.map { compile(it) }
        return CompiledExpr { ctx ->
            val vv = v.evaluate(ctx)
            if (vv !is Value.Num) {
                Value.Undefined
            } else {
                var hit = false
                for (m in members) {
                    val mv = m.evaluate(ctx)
                    if (mv is Value.Num && mv.v.compareTo(vv.v) == 0) {
                        hit = true
                        break
                    }
                }
                Value.Bool(hit)
            }
        }
    }
```

Import `InList`.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerBetweenInCaseTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt
git commit -m "feat(dsl): compile IN-list membership"
```

---

### Task 7: Compile `CaseWhen`

`CaseWhen(branches, elseExpr)` evaluates branches in order; first true `cond` returns its expr; if none match, `elseExpr`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt`

- [ ] **Step 1: Add failing tests**

Append:

```kotlin
    @Test
    fun `CASE WHEN picks first matching branch`() {
        val ec = ExprCompiler()
        val expr =
            com.qkt.dsl.ast.CaseWhen(
                branches =
                    listOf(
                        com.qkt.dsl.ast.BoolLit(false) to NumLit(BigDecimal("1")),
                        com.qkt.dsl.ast.BoolLit(true) to NumLit(BigDecimal("2")),
                        com.qkt.dsl.ast.BoolLit(true) to NumLit(BigDecimal("3")),
                    ),
                elseExpr = NumLit(BigDecimal("0")),
            )
        assertThat((ec.compile(expr).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("2")
    }

    @Test
    fun `CASE WHEN falls through to ELSE when no branch matches`() {
        val ec = ExprCompiler()
        val expr =
            com.qkt.dsl.ast.CaseWhen(
                branches = listOf(com.qkt.dsl.ast.BoolLit(false) to NumLit(BigDecimal("1"))),
                elseExpr = NumLit(BigDecimal("99")),
            )
        assertThat((ec.compile(expr).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("99")
    }
```

- [ ] **Step 2: Run tests**

Expected: FAIL.

- [ ] **Step 3: Add `CaseWhen` branch**

```kotlin
            is CaseWhen -> compileCaseWhen(expr)
```

```kotlin
    private fun compileCaseWhen(expr: CaseWhen): CompiledExpr {
        val branches = expr.branches.map { compile(it.first) to compile(it.second) }
        val elseE = compile(expr.elseExpr)
        return CompiledExpr { ctx ->
            for ((cond, body) in branches) {
                val cv = cond.evaluate(ctx)
                if (cv is Value.Bool && cv.v) return@CompiledExpr body.evaluate(ctx)
            }
            elseE.evaluate(ctx)
        }
    }
```

Import `CaseWhen`.

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerBetweenInCaseTest.kt
git commit -m "feat(dsl): compile CASE WHEN expression"
```

---

### Task 8: `CrossesState` and `Crosses` compile

`Crosses` is the first stateful `CompiledExpr`. Each compiled `Crosses` instance owns a `CrossesState` that remembers `prev_lhs > prev_rhs` from the previous bar. On each candle: read current `lhs`/`rhs`, compare against previous comparison, emit `true` only on the transition matching `direction`. If either side is `Undefined`, the result is `Undefined` and previous state is **not** updated (so warmup doesn't burn the prev-bar slot).

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/CrossesState.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerCrossesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerCrossesTest {
    private fun candleAt(
        symbol: String,
        close: String,
    ) = Candle(
        symbol,
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal.ZERO,
        0L,
        60_000L,
    )

    private fun ec(streamSymbols: Map<String, String> = mapOf("btc" to "BTCUSDT")): ExprCompiler =
        ExprCompiler()

    private fun ctxFor(
        candle: Candle,
        streamSymbols: Map<String, String> = mapOf("btc" to "BTCUSDT"),
    ): EvalContext =
        EvalContext(
            candle = candle,
            streamSymbols = streamSymbols,
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `CROSSES ABOVE fires only on the rising transition bar`() {
        val expr =
            Crosses(
                direction = CrossDir.ABOVE,
                lhs = StreamFieldRef("btc", "close"),
                rhs = com.qkt.dsl.ast.NumLit(BigDecimal("100")),
            )
        val compiled = ec().compile(expr)
        // 99 -> Undefined first bar (no prev), 99 again -> false, 101 -> true (cross), 102 -> false (still above)
        assertThat(compiled.evaluate(ctxFor(candleAt("BTCUSDT", "99")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "99"))) as Value.Bool).v).isFalse()
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "101"))) as Value.Bool).v).isTrue()
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "102"))) as Value.Bool).v).isFalse()
    }

    @Test
    fun `CROSSES BELOW fires only on the falling transition bar`() {
        val expr =
            Crosses(
                direction = CrossDir.BELOW,
                lhs = StreamFieldRef("btc", "close"),
                rhs = com.qkt.dsl.ast.NumLit(BigDecimal("100")),
            )
        val compiled = ec().compile(expr)
        assertThat(compiled.evaluate(ctxFor(candleAt("BTCUSDT", "101")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "101"))) as Value.Bool).v).isFalse()
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "99"))) as Value.Bool).v).isTrue()
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "98"))) as Value.Bool).v).isFalse()
    }

    @Test
    fun `Undefined operand does not corrupt prev state`() {
        // candle on a different symbol returns Undefined for StreamFieldRef("btc", ...)
        val expr =
            Crosses(
                direction = CrossDir.ABOVE,
                lhs = StreamFieldRef("btc", "close"),
                rhs = com.qkt.dsl.ast.NumLit(BigDecimal("100")),
            )
        val compiled = ec().compile(expr)
        assertThat(compiled.evaluate(ctxFor(candleAt("BTCUSDT", "99"))))
            .isEqualTo(Value.Undefined) // first bar — no prev
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "99"))) as Value.Bool).v).isFalse()
        // unrelated symbol candle — Undefined, must not corrupt prev
        assertThat(compiled.evaluate(ctxFor(candleAt("OTHER", "200")))).isEqualTo(Value.Undefined)
        // back to BTCUSDT crossing — still detects properly
        assertThat((compiled.evaluate(ctxFor(candleAt("BTCUSDT", "101"))) as Value.Bool).v).isTrue()
    }
}
```

- [ ] **Step 2: Run test**

Expected: FAIL.

- [ ] **Step 3: Implement `CrossesState.kt`**

```kotlin
package com.qkt.dsl.compile

class CrossesState {
    private var prevLhsAboveRhs: Boolean? = null

    fun update(
        currentAbove: Boolean,
        direction: com.qkt.dsl.ast.CrossDir,
    ): Value {
        val prev = prevLhsAboveRhs
        prevLhsAboveRhs = currentAbove
        if (prev == null) return Value.Undefined
        return Value.Bool(
            when (direction) {
                com.qkt.dsl.ast.CrossDir.ABOVE -> !prev && currentAbove
                com.qkt.dsl.ast.CrossDir.BELOW -> prev && !currentAbove
            },
        )
    }
}
```

- [ ] **Step 4: Add `Crosses` branch in `ExprCompiler`**

```kotlin
            is Crosses -> compileCrosses(expr)
```

```kotlin
    private fun compileCrosses(c: Crosses): CompiledExpr {
        val l = compile(c.lhs)
        val r = compile(c.rhs)
        val state = CrossesState()
        return CompiledExpr { ctx ->
            val lv = l.evaluate(ctx)
            val rv = r.evaluate(ctx)
            if (lv !is Value.Num || rv !is Value.Num) {
                Value.Undefined
            } else {
                val above = lv.v.compareTo(rv.v) > 0
                state.update(above, c.direction)
            }
        }
    }
```

Import `Crosses`, `CrossDir`.

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CrossesState.kt src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerCrossesTest.kt
git commit -m "feat(dsl): compile CROSSES ABOVE and BELOW with prev-bar state"
```

---

### Task 9: Indicator-on-indicator composition

Today `IndicatorBinding.bind` requires the series arg of an `IndicatorCall` to be a `StreamFieldRef`. Extend it to recursively accept another `IndicatorCall` as the series arg. Two bindings are then chained: each candle, the inner indicator updates first; whenever it has a value, that value feeds the outer indicator.

This means `IndicatorBinding` needs two update modes: **stream-fed** (current 11b path) and **indicator-fed** (the inner binding's most recent value drives the outer).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerCompositionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerCompositionTest {
    @Test
    fun `EMA over EMA composes`() {
        val bindings = IndicatorBinding.Bag()
        // EMA(EMA(close, 3), 3)
        val inner = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val outer = IndicatorCall("EMA", listOf(inner, NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(outer)
        var ec: EvalContext? = null
        for (price in listOf("100", "110", "120", "130", "140", "150", "160")) {
            val c =
                Candle("BTCUSDT", BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ZERO, 0L, 60_000L)
            ec =
                EvalContext(
                    candle = c,
                    streamSymbols = mapOf("btc" to "BTCUSDT"),
                    lets = emptyMap(),
                    strategyContext = testStrategyContext(),
                )
            bindings.updateAll(ec)
        }
        val v = compiled.evaluate(ec!!)
        assertThat(v).isInstanceOf(Value.Num::class.java)
    }

    @Test
    fun `outer indicator stays Undefined while inner is warming`() {
        val bindings = IndicatorBinding.Bag()
        val inner = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val outer = IndicatorCall("EMA", listOf(inner, NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(outer)
        val c =
            Candle("BTCUSDT", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 60_000L)
        val ec =
            EvalContext(
                candle = c,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        bindings.updateAll(ec)
        assertThat(compiled.evaluate(ec)).isEqualTo(Value.Undefined)
    }
}
```

- [ ] **Step 2: Run test**

Expected: FAIL — `bind` rejects nested `IndicatorCall`.

- [ ] **Step 3: Extend `IndicatorBinding.Bag.bind`**

`Bag` keeps bindings in a list (already). For an indicator-fed binding, store a reference to the inner binding and feed `inner.indicator.value()` into the outer indicator on every `update()`.

Replace the body of `Bag.bind(call: IndicatorCall): IndicatorBinding` so it can recurse. The series arg is either:
- a `StreamFieldRef` → existing stream-fed binding,
- another `IndicatorCall` → bind the inner first, then create an indicator-fed binding referencing it.

```kotlin
    class Bag {
        private val bindings: MutableList<IndicatorBinding> = mutableListOf()

        fun bind(call: IndicatorCall): IndicatorBinding {
            val spec = IndicatorRegistry.spec(call.name) ?: error("Unknown indicator: ${call.name}")
            require(call.args.size == spec.arity) {
                "Indicator ${call.name} expects ${spec.arity} args, got ${call.args.size}"
            }
            val seriesArg = call.args.first()
            val constArgs =
                call.args.drop(1).map {
                    require(it is NumLit) {
                        "Indicator ${call.name} non-series arg must be a numeric literal"
                    }
                    it.value
                }
            val ind = IndicatorRegistry.create(call.name, constArgs)
            val binding =
                when (seriesArg) {
                    is StreamFieldRef -> bindStream(spec, call, seriesArg, ind)
                    is IndicatorCall -> bindIndicator(spec, call, seriesArg, ind)
                    else ->
                        error(
                            "Indicator ${call.name} series arg must be a stream field or another indicator call",
                        )
                }
            bindings.add(binding)
            return binding
        }

        private fun bindStream(
            spec: IndicatorSpec,
            call: IndicatorCall,
            seriesArg: StreamFieldRef,
            ind: IndicatorOutput,
        ): IndicatorBinding {
            when (spec.inputKind) {
                IndicatorInput.NUMERIC_SERIES -> {
                    require(seriesArg.field in setOf("close", "open", "high", "low", "volume", "price")) {
                        "Indicator ${call.name} series field must be numeric: got ${seriesArg.field}"
                    }
                    return IndicatorBinding.streamFed(call, ind, seriesArg.stream, seriesArg.field, spec.inputKind)
                }
                IndicatorInput.CANDLE_SERIES -> {
                    require(seriesArg.field == "candle") {
                        "Indicator ${call.name} series arg must be the whole stream (use stream.candle or atr(stream))"
                    }
                    return IndicatorBinding.streamFed(call, ind, seriesArg.stream, null, spec.inputKind)
                }
            }
        }

        private fun bindIndicator(
            spec: IndicatorSpec,
            call: IndicatorCall,
            inner: IndicatorCall,
            ind: IndicatorOutput,
        ): IndicatorBinding {
            require(spec.inputKind == IndicatorInput.NUMERIC_SERIES) {
                "Indicator ${call.name} requires a candle series; cannot accept another indicator's output"
            }
            val innerBinding = bind(inner)
            return IndicatorBinding.indicatorFed(call, ind, innerBinding)
        }

        fun updateAll(ctx: EvalContext) {
            for (b in bindings) b.update(ctx)
        }
    }
```

Replace the constructor + companion construction of `IndicatorBinding` accordingly:

```kotlin
class IndicatorBinding private constructor(
    val call: IndicatorCall,
    val indicator: IndicatorOutput,
    private val streamAlias: String?,
    private val field: String?,
    private val inputKind: IndicatorInput,
    private val source: IndicatorBinding?,
) {
    @Suppress("UNCHECKED_CAST")
    fun update(ctx: EvalContext) {
        if (source != null) {
            val v = source.indicator.value()
            if (v == null || !source.indicator.isReady) return
            (indicator as Indicator<BigDecimal>).update(v)
            return
        }
        val symbol = ctx.streamSymbols[streamAlias!!] ?: error("Unknown stream alias: $streamAlias")
        if (ctx.candle.symbol != symbol) return
        when (inputKind) {
            IndicatorInput.NUMERIC_SERIES -> {
                val v: BigDecimal =
                    when (field) {
                        "close", "price" -> ctx.candle.close
                        "open" -> ctx.candle.open
                        "high" -> ctx.candle.high
                        "low" -> ctx.candle.low
                        "volume" -> ctx.candle.volume
                        else ->
                            error(
                                "Numeric indicator on stream '$streamAlias' requires a numeric field; got '$field'",
                            )
                    }
                (indicator as Indicator<BigDecimal>).update(v)
            }
            IndicatorInput.CANDLE_SERIES -> {
                (indicator as Indicator<Candle>).update(ctx.candle)
            }
        }
    }

    companion object {
        fun streamFed(
            call: IndicatorCall,
            indicator: IndicatorOutput,
            streamAlias: String,
            field: String?,
            inputKind: IndicatorInput,
        ): IndicatorBinding =
            IndicatorBinding(call, indicator, streamAlias, field, inputKind, source = null)

        fun indicatorFed(
            call: IndicatorCall,
            indicator: IndicatorOutput,
            source: IndicatorBinding,
        ): IndicatorBinding =
            IndicatorBinding(
                call,
                indicator,
                streamAlias = null,
                field = null,
                inputKind = IndicatorInput.NUMERIC_SERIES,
                source = source,
            )

        // Bag is the public entry point for the compiler.
        class Bag { /* ... see code above ... */ }
    }
}
```

> **Note:** the existing `Bag` class lives inside `IndicatorBinding`. Move it to the companion or keep it as a sibling — whichever makes the diff smaller. Adapt the rest of the file accordingly.

- [ ] **Step 4: Run all `ExprCompiler*Test`**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompiler*'`
Expected: PASS for everything, including the existing 11b indicator tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerCompositionTest.kt
git commit -m "feat(dsl): support indicator-on-indicator composition"
```

---

### Task 10: `FuncCall` AST node

`FuncCall(name, args): ExprAst` represents a pure-function call. Distinct from `IndicatorCall` because it doesn't instantiate an `Indicator`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt`
- Create: `src/test/kotlin/com/qkt/dsl/ast/FuncCallTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FuncCallTest {
    @Test
    fun `FuncCall captures name and args`() {
        val f = FuncCall("ABS", listOf(NumLit(BigDecimal("-1"))))
        assertThat(f.name).isEqualTo("ABS")
        assertThat(f.args).hasSize(1)
    }
}
```

- [ ] **Step 2: Run test**

Expected: FAIL.

- [ ] **Step 3: Add `FuncCall` to `ExprAst.kt`**

Append:

```kotlin
data class FuncCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst
```

- [ ] **Step 4: Add `FuncCall` to `LetResolver.resolve`**

Add the recursive case in the `when (expr)`:

```kotlin
            is FuncCall -> FuncCall(expr.name, expr.args.map { resolve(it) })
```

Import `FuncCall`.

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt src/test/kotlin/com/qkt/dsl/ast/FuncCallTest.kt
git commit -m "feat(dsl): add FuncCall AST node"
```

---

### Task 11: `FuncRegistry` for stdlib math

Pure functions: `ABS`/`SQRT`/`LOG` (single-arg numeric), `MIN`/`MAX` (variadic numeric, ≥2). The registry maps names to `(List<BigDecimal>) -> BigDecimal` factories with arity validation. Keep the variadic separate from the running-aggregate forms (those land in 11c2 via the `Aggregate` AST node).

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/stdlib/FuncRegistry.kt`
- Create: `src/test/kotlin/com/qkt/dsl/stdlib/FuncRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.stdlib

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FuncRegistryTest {
    @Test
    fun `ABS returns absolute value`() {
        assertThat(FuncRegistry.invoke("ABS", listOf(BigDecimal("-3.14"))))
            .isEqualByComparingTo("3.14")
    }

    @Test
    fun `SQRT returns positive root`() {
        assertThat(FuncRegistry.invoke("SQRT", listOf(BigDecimal("16"))))
            .isEqualByComparingTo("4")
    }

    @Test
    fun `LOG returns natural log`() {
        assertThat(FuncRegistry.invoke("LOG", listOf(BigDecimal("1"))))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `MIN returns the smallest`() {
        assertThat(FuncRegistry.invoke("MIN", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"))))
            .isEqualByComparingTo("1")
    }

    @Test
    fun `MAX returns the largest`() {
        assertThat(FuncRegistry.invoke("MAX", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"))))
            .isEqualByComparingTo("3")
    }

    @Test
    fun `ABS rejects wrong arity`() {
        assertThatThrownBy { FuncRegistry.invoke("ABS", emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MIN rejects single arg`() {
        assertThatThrownBy { FuncRegistry.invoke("MIN", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown function throws`() {
        assertThatThrownBy { FuncRegistry.invoke("UNKNOWN", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `has reports membership`() {
        assertThat(FuncRegistry.has("ABS")).isTrue()
        assertThat(FuncRegistry.has("UNKNOWN")).isFalse()
    }
}
```

- [ ] **Step 2: Run test**

Expected: FAIL.

- [ ] **Step 3: Implement `FuncRegistry.kt`**

```kotlin
package com.qkt.dsl.stdlib

import com.qkt.common.Money
import java.math.BigDecimal
import kotlin.math.ln
import kotlin.math.sqrt

private enum class Arity { UNARY, VARIADIC2 }

private data class FuncSpec(
    val name: String,
    val arity: Arity,
    val apply: (List<BigDecimal>) -> BigDecimal,
)

object FuncRegistry {
    private val table: Map<String, FuncSpec> =
        mapOf(
            "ABS" to FuncSpec("ABS", Arity.UNARY) { args -> args[0].abs() },
            "SQRT" to FuncSpec("SQRT", Arity.UNARY) { args ->
                BigDecimal(sqrt(args[0].toDouble())).round(Money.CONTEXT)
            },
            "LOG" to FuncSpec("LOG", Arity.UNARY) { args ->
                BigDecimal(ln(args[0].toDouble())).round(Money.CONTEXT)
            },
            "MIN" to FuncSpec("MIN", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.min(b) } },
            "MAX" to FuncSpec("MAX", Arity.VARIADIC2) { args -> args.reduce { a, b -> a.max(b) } },
        )

    fun has(name: String): Boolean = table.containsKey(name)

    fun invoke(
        name: String,
        args: List<BigDecimal>,
    ): BigDecimal {
        val spec = table[name] ?: error("Unknown function: $name")
        when (spec.arity) {
            Arity.UNARY -> require(args.size == 1) { "$name expects 1 arg, got ${args.size}" }
            Arity.VARIADIC2 -> require(args.size >= 2) { "$name expects >= 2 args, got ${args.size}" }
        }
        return spec.apply(args)
    }
}
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/stdlib/FuncRegistry.kt src/test/kotlin/com/qkt/dsl/stdlib/FuncRegistryTest.kt
git commit -m "feat(dsl): add stdlib math function registry"
```

---

### Task 12: Compile `FuncCall`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerFuncCallTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerFuncCallTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `ABS evaluates`() {
        val v =
            ExprCompiler().compile(FuncCall("ABS", listOf(NumLit(BigDecimal("-7"))))).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7")
    }

    @Test
    fun `MAX of three`() {
        val v =
            ExprCompiler().compile(
                FuncCall(
                    "MAX",
                    listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("9")), NumLit(BigDecimal("3"))),
                ),
            ).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("9")
    }

    @Test
    fun `Undefined arg makes the result Undefined`() {
        val v =
            ExprCompiler().compile(
                FuncCall(
                    "MAX",
                    listOf(NumLit(BigDecimal("1")), com.qkt.dsl.ast.StreamFieldRef("btc", "close")),
                ),
            ).evaluate(ctx)
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
```

- [ ] **Step 2: Run test**

Expected: FAIL.

- [ ] **Step 3: Add `FuncCall` branch to `ExprCompiler`**

```kotlin
            is FuncCall -> compileFuncCall(expr)
```

```kotlin
    private fun compileFuncCall(call: FuncCall): CompiledExpr {
        require(FuncRegistry.has(call.name)) { "Unknown function: ${call.name}" }
        val args = call.args.map { compile(it) }
        return CompiledExpr { ctx ->
            val values = args.map { it.evaluate(ctx) }
            if (values.any { it !is Value.Num }) {
                Value.Undefined
            } else {
                Value.Num(FuncRegistry.invoke(call.name, values.map { (it as Value.Num).v }))
            }
        }
    }
```

Imports: `FuncCall`, `com.qkt.dsl.stdlib.FuncRegistry`.

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerFuncCallTest.kt
git commit -m "feat(dsl): compile FuncCall against stdlib registry"
```

---

### Task 13: Kotlin DSL helpers — state refs

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/StateRefs.kt`

- [ ] **Step 1: Implement `StateRefs.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource

object Account {
    val realizedPnl: ExprAst = AccountRef("realized_pnl")
    val unrealizedPnl: ExprAst = AccountRef("unrealized_pnl")
    val totalPnl: ExprAst = AccountRef("total_pnl")
}

fun position(stream: StreamRef): ExprAst = PositionRef(stream.alias)

fun positionAvgPrice(stream: StreamRef): ExprAst = StateAccessor(StateSource.POSITION_AVG_PRICE, stream.alias)
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/StateRefs.kt
git commit -m "feat(dsl): kotlin builders for account and position refs"
```

---

### Task 14: Kotlin DSL helpers — range, conditional, cross, math

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ExprBuilders.kt`
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/MathBuilders.kt`
- Create: `src/test/kotlin/com/qkt/dsl/kotlin/ExpressiveCoreBuildersTest.kt`

- [ ] **Step 1: Append to `ExprBuilders.kt`**

```kotlin
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.InList

fun ExprAst.between(
    lo: ExprAst,
    hi: ExprAst,
): ExprAst = Between(this, lo, hi)

fun ExprAst.inList(vararg members: ExprAst): ExprAst = InList(this, members.toList())

infix fun ExprAst.crossesAbove(other: ExprAst): ExprAst = Crosses(CrossDir.ABOVE, this, other)

infix fun ExprAst.crossesBelow(other: ExprAst): ExprAst = Crosses(CrossDir.BELOW, this, other)

fun caseWhen(
    vararg branches: Pair<ExprAst, ExprAst>,
    elseExpr: ExprAst,
): ExprAst = CaseWhen(branches.toList(), elseExpr)
```

- [ ] **Step 2: Implement `MathBuilders.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall

fun abs(x: ExprAst): ExprAst = FuncCall("ABS", listOf(x))

fun sqrt(x: ExprAst): ExprAst = FuncCall("SQRT", listOf(x))

fun log(x: ExprAst): ExprAst = FuncCall("LOG", listOf(x))

fun min(
    a: ExprAst,
    b: ExprAst,
    vararg rest: ExprAst,
): ExprAst = FuncCall("MIN", listOf(a, b) + rest.toList())

fun max(
    a: ExprAst,
    b: ExprAst,
    vararg rest: ExprAst,
): ExprAst = FuncCall("MAX", listOf(a, b) + rest.toList())
```

- [ ] **Step 3: Write the integration test**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressiveCoreBuildersTest {
    @Test
    fun `between builds Between AST`() {
        val expr = 5.bd.between(1.bd, 10.bd)
        assertThat(expr).isInstanceOf(Between::class.java)
    }

    @Test
    fun `inList builds InList AST`() {
        val expr = 3.bd.inList(1.bd, 3.bd, 5.bd)
        assertThat(expr).isInstanceOf(InList::class.java)
    }

    @Test
    fun `crossesAbove builds Crosses ABOVE`() {
        val expr = 1.bd crossesAbove 0.bd
        assertThat((expr as Crosses).direction).isEqualTo(CrossDir.ABOVE)
    }

    @Test
    fun `caseWhen builds CaseWhen AST`() {
        val expr = caseWhen(1.bd.gt(0.bd) to 5.bd, elseExpr = 0.bd)
        assertThat(expr).isInstanceOf(CaseWhen::class.java)
    }

    @Test
    fun `Account refs`() {
        assertThat(Account.realizedPnl).isEqualTo(AccountRef("realized_pnl"))
        assertThat(Account.unrealizedPnl).isEqualTo(AccountRef("unrealized_pnl"))
        assertThat(Account.totalPnl).isEqualTo(AccountRef("total_pnl"))
    }

    @Test
    fun `position helpers`() {
        val btc = StreamRef("btc")
        assertThat(position(btc)).isEqualTo(PositionRef("btc"))
        assertThat(positionAvgPrice(btc)).isEqualTo(StateAccessor(StateSource.POSITION_AVG_PRICE, "btc"))
    }

    @Test
    fun `math helpers build FuncCall`() {
        assertThat(abs(1.bd)).isEqualTo(FuncCall("ABS", listOf(1.bd)))
        assertThat(min(1.bd, 2.bd)).isEqualTo(FuncCall("MIN", listOf(1.bd, 2.bd)))
        assertThat(max(1.bd, 2.bd, 3.bd)).isEqualTo(FuncCall("MAX", listOf(1.bd, 2.bd, 3.bd)))
    }
}
```

> **Note:** `1.bd.gt(0.bd)` requires that `gt` from `ExprBuilders.kt` is imported into the test (it's in the same package, so it's resolved automatically once it's a top-level `infix` function).

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.ExpressiveCoreBuildersTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/ExprBuilders.kt src/main/kotlin/com/qkt/dsl/kotlin/MathBuilders.kt src/test/kotlin/com/qkt/dsl/kotlin/ExpressiveCoreBuildersTest.kt
git commit -m "feat(dsl): kotlin builders for between inList caseWhen crosses and math"
```

---

### Task 15: Update boundary lock — remove now-supported asserts

11b's `UnsupportedAstTest` asserted that `BETWEEN`/`IN`/`CROSSES`/`CASE WHEN`/`AccountRef`/`PositionRef` all errored. Remove those — they work now. Keep the asserts for `Aggregate` (11c2) and add `StateAccessor(OPEN_ORDERS, ...)` (still rejected in 11c1).

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt`

- [ ] **Step 1: Replace the file content**

Keep only the asserts that still hold in 11c1:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `aggregates are not supported in 11c1`() {
        assertThatThrownBy {
            ec.compile(Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen))
        }.hasMessageContaining("unsupported")
    }

    @Test
    fun `OPEN_ORDERS state is not supported in 11c1`() {
        assertThatThrownBy {
            ec.compile(StateAccessor(StateSource.OPEN_ORDERS, "btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt
git commit -m "test(dsl): refresh boundary lock for 11c1"
```

---

### Task 16: End-to-end CROSSES strategy equivalence

The killer 11c1 demo: a Kotlin DSL EMA crossover with `crossesAbove`/`crossesBelow` should now equal the hand-written `EmaCrossoverStrategy` over the same fixture (which 11b couldn't match).

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/CrossEquivalenceTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.crossesBelow
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import com.qkt.strategy.EmaCrossoverStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrossEquivalenceTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private val sample =
        ticks(
            listOf(
                "100", "101", "102", "104", "108", "112", "115", "120", "118", "115",
                "112", "110", "108", "105", "100", "95", "92", "90", "88", "85",
                "80", "82", "85", "90", "95", "100", "108", "115", "120", "125",
            ),
        )

    @Test
    fun `dsl ema crossover with CROSSES equals handwritten`() {
        val ast =
            strategy("ema_x", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 3))
                val slow by letting(ema(btc.close, period = 7))
                rule {
                    whenever(fast crossesAbove slow)
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever(fast crossesBelow slow)
                    then { sell(btc, qty = 1.bd) }
                }
            }

        val dslStrategy = AstCompiler().compile(ast)
        val handStrategy = EmaCrossoverStrategy(symbol = "BTCUSDT", fastPeriod = 3, slowPeriod = 7)

        val dslResult =
            Backtest(
                strategies = listOf("ema_x" to dslStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val handResult =
            Backtest(
                strategies = listOf("ema_x" to handStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(dslResult.global.totalPnL).isEqualByComparingTo(handResult.global.totalPnL)
        assertThat(dslResult.trades.map { it.trade.symbol to it.trade.side })
            .isEqualTo(handResult.trades.map { it.trade.symbol to it.trade.side })
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.CrossEquivalenceTest'`
Expected: PASS. If it diverges, the cause is most likely an off-by-one in `CrossesState` — `EmaCrossoverStrategy` has the same edge-detection semantics, so a divergence is a bug. Stop and reassess after one failed attempt before patching.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/CrossEquivalenceTest.kt
git commit -m "test(dsl): cross-detection ema equivalence with handwritten"
```

---

### Task 17: Build green + ktlint

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: ktlintFormat**

Run: `./gradlew ktlintFormat`
Then re-run `./gradlew build` to confirm.

- [ ] **Step 3: Commit reformat as `style:` if anything changed**

```bash
git status -s
git add <changed files>
git commit -m "style: ktlint format 11c1 sources"
```

---

### Task 18: Phase changelog

**Files:**
- Create: `docs/phases/phase-11c1-state-and-operators.md`

- [ ] **Step 1: Write `docs/phases/phase-11c1-state-and-operators.md`**

Skeleton:

```markdown
# Phase 11c1 — Engine State, Range, Conditional, Cross, Composition, Math

## Summary

Phase 11c1 turns the 11b foundation into an actually expressive language. Strategies can now read live engine state (account P&L, position size, average entry price), gate on ranges and membership, branch with `CASE WHEN`, detect price crosses, compose indicators, and call the standard math library — all without per-rule runtime state. Snapshots and running aggregates land in 11c2; the new actions land in 11c3.

## What's new

- `EvalContext.strategyContext` — the compiled strategy threads the live `StrategyContext` into every expression evaluation, so state accessors can read `pnl` and `positions`.
- `AccountRef` compile — `realized_pnl`, `unrealized_pnl`, `total_pnl`. Reads from `StrategyPnLView`.
- `PositionRef` compile — signed quantity from `StrategyPositionView`.
- `StateAccessor(POSITION_AVG_PRICE, alias)` compile — `Position.avgEntryPrice`. Other `StateSource` variants (`OPEN_ORDERS`) remain rejected.
- `Between`, `InList`, `CaseWhen` compile.
- `Crosses ABOVE` / `Crosses BELOW` with per-binding `CrossesState` for prev-bar tracking. `Undefined` does not corrupt the prev-bar slot.
- Indicator-on-indicator composition. `IndicatorBinding.Bag.bind` recurses on nested `IndicatorCall` series args; the inner binding's output drives the outer indicator's update.
- `FuncCall` AST node + `FuncRegistry` stdlib: `ABS`, `SQRT`, `LOG` (single-arg), `MIN`, `MAX` (variadic ≥2). Distinct from running-aggregate `MIN`/`MAX` over a `SINCE` window (11c2).
- Kotlin DSL helpers: `Account.realizedPnl/unrealizedPnl/totalPnl`, `position(stream)`, `positionAvgPrice(stream)`, `between`, `inList`, `crossesAbove`, `crossesBelow`, `caseWhen(...)`, `abs/sqrt/log/min/max`.

## Migration from previous phase

`EvalContext` now requires `strategyContext: StrategyContext`. Tests that constructed `EvalContext` directly need to pass `testStrategyContext()`. Production code goes through `AstCompiler`, which threads `StrategyContext` automatically.

## Usage cookbook

[Worked examples covering: gating on POSITION, account-PnL throttle, RSI(ATR) composition, CASE WHEN entry sizing, etc.]

## Testing patterns

[Patterns for state-aware tests using `testStrategyContext(positions = ..., pnl = ...)` and the `CrossEquivalenceTest` parity check against `EmaCrossoverStrategy`.]

## Known limitations

- `ACCOUNT.equity`, `ACCOUNT.balance`, `ACCOUNT.drawdown`, `OPEN_ORDERS.<sym>` not yet exposed — engine surface needs work first.
- Snapshots, running aggregates: Phase 11c2.
- `Log`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`: Phase 11c3.
- `LIMIT`, `STOP`, etc.: Phase 11d.
- `FOR EACH`, multi-stream: Phase 11e.
- Parser: Phase 11f.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11c1
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11c1.md`
- Merge commit: TBD (filled in after merge to `main`).
```

Fill in the `[Worked examples ...]` and `[Patterns ...]` sections with actual runnable Kotlin DSL examples covering the major ways the new capabilities can be used. Aim for 3–5 cookbook entries.

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-11c1-state-and-operators.md
git commit -m "docs: phase 11c1 changelog"
```

---

### Task 19: Pre-push checklist

Per qkt skill §4.

- [ ] `./gradlew build` — `BUILD SUCCESSFUL`.
- [ ] `git status` — clean.
- [ ] `git log --oneline main..HEAD` — every message conforms to `<type>(<scope>): <subject>`.
- [ ] `grep -rEn 'TODO|FIXME|XXX' src/main/kotlin/com/qkt/dsl/ src/test/kotlin/com/qkt/dsl/` — no stragglers.
- [ ] Hand off to `superpowers:finishing-a-development-branch`. Per qkt convention the merge into `main` uses `--no-ff` with the message `merge: phase 11c1 state and operators`. Fill in the merge SHA in `docs/phases/phase-11c1-state-and-operators.md` after merge as a `docs:` commit on `main`.
