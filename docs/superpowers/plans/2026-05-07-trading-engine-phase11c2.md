# Phase 11c2 — Snapshots and Running Aggregates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stateful runtime to the DSL — capture LET values at decision time (`@buy`, `@sell`, `@open`, `@T-N`) and summarise series over rolling/since-open windows (`MIN`/`MAX`/`MEAN`/`SUM`). After this phase, a quant can write trailing-stop logic, breakeven exits, "highest high since entry" gates, and N-bar momentum filters in pure DSL.

**Architecture:** Two new state machines wired into `CompiledStrategy`: a `SnapshotStore` keyed by `(symbol, name, kind)` and a per-`Aggregate`-instance `AggregateState`. Compile-time scan over rules collects every `Ref(name, snapshot)` and `Aggregate(...)` use, building a list of (symbol, name/expr, kind) tuples to evaluate at the right lifecycle moments: on every candle (rolling), on rule fire (`@buy`/`@sell`/`@open` + reset detection), on position-transition (`@open` clear, `SINCE OPEN` reset). The `LetResolver` is upgraded to keep snapshot Refs intact rather than substitute them. `ExprCompiler.compile` gains an optional `ruleSymbol` parameter so `Ref(snapshot)` and `Aggregate(SinceOpen)` can close over the rule's stream symbol at compile time.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 — Phase 11c2.

**Branch:** `phase11c2-snapshots-aggregates` (already cut from `main`).

---

## Design notes

Two design calls worth being explicit about, because they shape the runtime semantics:

### Snapshot lifecycle

- `@buy` / `@sell` capture **at rule-fire time**, before the Signal hits the broker. Stored per `(symbol, name, kind)`. Most-recent fire wins (overwrites the previous).
- `@open` captures **at rule-fire time** when the current pre-fire position on the rule's stream is exactly zero — which means this Buy/Sell will open a new position. Stored per `(symbol, name, SnapshotOpen)`.
- `@open` is **cleared at the start of the next `onCandle`** when we observe a non-zero → zero position transition (the prior position closed). Read against a cleared slot returns `Value.Undefined`.
- `@T-N` captures **on every `onCandle`** after indicators update but before rules fire. A bounded ring buffer per `(symbol, name)` holds the last `maxN+1` values, where `maxN` is the largest `N` referenced by any `SnapshotTPast(n)` for that name. Reading offset `n` returns `Value.Undefined` if fewer than `n+1` samples have been pushed.

### Aggregate lifecycle

- `Aggregate(MAX, series, SinceOpen)` updates **on every `onCandle`** with the current value of `series`. If `series` evaluates to `Undefined` (e.g., underlying indicator not warm), the update is a no-op for that bar.
- `SINCE OPEN` resets the running aggregate at the **start of `onCandle`** when we observe a zero → non-zero transition for the rule's stream symbol. While position is zero, reads return `Value.Undefined`.
- `SINCE T-N` is implemented as a ring buffer of the last `N` values; `MIN`/`MAX`/`MEAN`/`SUM` is computed on read. Returns `Value.Undefined` until the buffer has `N` samples.

Both mechanisms re-use the same per-stream position-transition watcher, which lives once on `CompiledStrategy`.

### Why `Ref(snapshot)` doesn't carry a symbol

The AST keeps `Ref(name, snapshot)` symbol-agnostic — the symbol is implicit from the rule that contains the Ref. At rule-compile time we resolve the rule's action stream alias to a symbol and pass it to `ExprCompiler.compile(expr, ruleSymbol = ...)`, which closes over the symbol when emitting the snapshot-read closure. This keeps the AST clean for 11e (multi-stream) — only the compiler changes.

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/dsl/compile/
├── SnapshotStore.kt            # per-(symbol, name, kind) value store + per-(symbol, name) ring buffer
├── SnapshotPlan.kt             # compile-time gathering of snapshot uses; per-fire/per-candle capture lists
├── AggregateState.kt           # running aggregator (sinceOpen + sinceT) with reset
└── PositionTransitions.kt      # per-stream prev_qty tracker + transition detector

src/main/kotlin/com/qkt/dsl/kotlin/
├── Snapshots.kt                # `at` infix + atBuy/atSell/atOpen/atT(n)
└── Aggregates.kt               # runMin/runMax/runMean/runSum + sinceOpen/sinceT(n)

src/test/kotlin/com/qkt/dsl/compile/
├── SnapshotStoreTest.kt
├── ExprCompilerSnapshotTest.kt
├── AggregateStateTest.kt
├── ExprCompilerAggregateTest.kt
└── SnapshotEndToEndTest.kt     # full strategy: trailing stop using runMax(close) sinceOpen and entry@open

src/test/kotlin/com/qkt/dsl/kotlin/
└── SnapshotAggregateBuildersTest.kt
```

### Modified files

```
src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt     # EvalContext: add snapshotStore
src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt      # keep snapshot Refs; require LET name exists
src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt     # compile Ref(snapshot) + Aggregate
src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt     # post-fire hook for snapshot capture
src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt      # wire SnapshotStore + position transitions + per-candle capture
src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt  # drop snapshot/aggregate asserts
```

---

## Tasks

### Task 1: `SnapshotStore`

The runtime store. Three storage shapes:
- `(symbol, name, kind∈{Buy,Sell,Open}) → BigDecimal?` — single-slot.
- `(symbol, name) → RingBuffer<BigDecimal?>` — for `SnapshotTPast`. Read at offset `n` from the end.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/SnapshotStore.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/SnapshotStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SnapshotStoreTest {
    @Test
    fun `single-slot store and read`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "fast", SnapshotBuy, BigDecimal("105"))
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotBuy)).isEqualByComparingTo("105")
    }

    @Test
    fun `unset slot returns null`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotSell)).isNull()
    }

    @Test
    fun `most-recent capture wins`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "x", SnapshotBuy, BigDecimal("1"))
        s.captureSlot("BTCUSDT", "x", SnapshotBuy, BigDecimal("2"))
        assertThat(s.readSlot("BTCUSDT", "x", SnapshotBuy)).isEqualByComparingTo("2")
    }

    @Test
    fun `clear slot returns null on read`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        s.captureSlot("BTCUSDT", "fast", SnapshotOpen, BigDecimal("105"))
        s.clearSlot("BTCUSDT", "fast", SnapshotOpen)
        assertThat(s.readSlot("BTCUSDT", "fast", SnapshotOpen)).isNull()
    }

    @Test
    fun `rolling buffer returns latest at offset 0 and prev at offset 1`() {
        val s = SnapshotStore(maxRollingPerName = mapOf("close" to 3))
        s.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        s.pushRolling("BTCUSDT", "close", BigDecimal("110"))
        s.pushRolling("BTCUSDT", "close", BigDecimal("120"))
        assertThat(s.readRolling("BTCUSDT", "close", 0)).isEqualByComparingTo("120")
        assertThat(s.readRolling("BTCUSDT", "close", 1)).isEqualByComparingTo("110")
        assertThat(s.readRolling("BTCUSDT", "close", 2)).isEqualByComparingTo("100")
    }

    @Test
    fun `rolling out-of-range returns null`() {
        val s = SnapshotStore(maxRollingPerName = mapOf("close" to 2))
        s.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        assertThat(s.readRolling("BTCUSDT", "close", 1)).isNull()
        assertThat(s.readRolling("BTCUSDT", "close", 5)).isNull()
    }

    @Test
    fun `rolling never registered returns null`() {
        val s = SnapshotStore(maxRollingPerName = emptyMap())
        assertThat(s.readRolling("BTCUSDT", "close", 0)).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.SnapshotStoreTest'`
Expected: FAIL — type doesn't exist.

- [ ] **Step 3: Implement `SnapshotStore.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotKind
import java.math.BigDecimal

class SnapshotStore(maxRollingPerName: Map<String, Int>) {
    private val slots: MutableMap<Triple<String, String, SnapshotKind>, BigDecimal> = HashMap()
    private val rollingCapacity: Map<String, Int> = maxRollingPerName.mapValues { it.value + 1 }
    private val rolling: MutableMap<Pair<String, String>, ArrayDeque<BigDecimal?>> = HashMap()

    fun captureSlot(
        symbol: String,
        name: String,
        kind: SnapshotKind,
        value: BigDecimal,
    ) {
        slots[Triple(symbol, name, kind)] = value
    }

    fun readSlot(
        symbol: String,
        name: String,
        kind: SnapshotKind,
    ): BigDecimal? = slots[Triple(symbol, name, kind)]

    fun clearSlot(
        symbol: String,
        name: String,
        kind: SnapshotKind,
    ) {
        slots.remove(Triple(symbol, name, kind))
    }

    fun pushRolling(
        symbol: String,
        name: String,
        value: BigDecimal?,
    ) {
        val cap = rollingCapacity[name] ?: return
        val key = symbol to name
        val deque = rolling.getOrPut(key) { ArrayDeque(cap) }
        deque.addLast(value)
        while (deque.size > cap) deque.removeFirst()
    }

    fun readRolling(
        symbol: String,
        name: String,
        offset: Int,
    ): BigDecimal? {
        val deque = rolling[symbol to name] ?: return null
        if (offset < 0 || offset >= deque.size) return null
        return deque[deque.size - 1 - offset]
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.SnapshotStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/SnapshotStore.kt src/test/kotlin/com/qkt/dsl/compile/SnapshotStoreTest.kt
git commit -m "feat(dsl): add snapshot store"
```

---

### Task 2: `LetResolver` accepts snapshot Refs

In 11b/11c1, `LetResolver.resolve(Ref)` substitutes the LET's RHS for bare Refs and rejects snapshot Refs. In 11c2 we keep the snapshot Ref intact so the compiler can route it to `SnapshotStore`. We still validate that the LET name exists.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLetTest.kt`

- [ ] **Step 1: Update the failing-snapshot test**

The 11b test asserted that snapshot Refs throw. Change it to assert they're returned as-is:

```kotlin
    @Test
    fun `snapshot references are kept intact`() {
        val lets = listOf(LetDecl("x", NumLit(BigDecimal.ONE)))
        val expr = Ref("x", snapshot = SnapshotOpen)
        val resolved = LetResolver(lets).resolve(expr)
        assertThat(resolved).isEqualTo(Ref("x", snapshot = SnapshotOpen))
    }

    @Test
    fun `snapshot reference to unknown LET is rejected`() {
        assertThatThrownBy { LetResolver(emptyList()).resolve(Ref("missing", snapshot = SnapshotOpen)) }
            .isInstanceOf(IllegalStateException::class.java)
    }
```

Drop the existing `snapshot references are rejected in 11b` test — replace it with the two above.

- [ ] **Step 2: Update `LetResolver`**

```kotlin
            is Ref -> {
                if (expr.snapshot != null) {
                    require(table.containsKey(expr.name)) { "Unknown LET reference: ${expr.name}" }
                    expr
                } else {
                    val target = table[expr.name] ?: error("Unknown reference: ${expr.name}")
                    resolve(target)
                }
            }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerLetTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLetTest.kt
git commit -m "feat(dsl): keep snapshot refs intact through LetResolver"
```

---

### Task 3: `SnapshotPlan` — compile-time scan

Walk every (resolved) rule expression and collect:
- Which `(letName, kind)` pairs need capture on Buy/Sell rule firings (`SnapshotBuy`/`SnapshotSell`).
- Which need capture on rule firings that open a position (`SnapshotOpen`).
- Which need rolling capture on every candle, with their max N (`SnapshotTPast`).

For each collected `letName`, compile its LET RHS once against the shared `IndicatorBinding.Bag` and `ExprCompiler`. The compiled exprs are evaluated when the appropriate lifecycle event fires.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/SnapshotPlan.kt`

- [ ] **Step 1: Implement `SnapshotPlan.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp

data class SnapshotPlan(
    val captureOnBuy: List<String>,
    val captureOnSell: List<String>,
    val captureOnOpen: List<String>,
    val rollingMaxN: Map<String, Int>,
) {
    companion object {
        fun scan(rules: List<ExprAst>): SnapshotPlan {
            val onBuy = mutableSetOf<String>()
            val onSell = mutableSetOf<String>()
            val onOpen = mutableSetOf<String>()
            val rolling = mutableMapOf<String, Int>()
            for (e in rules) walk(e, onBuy, onSell, onOpen, rolling)
            return SnapshotPlan(
                captureOnBuy = onBuy.toList(),
                captureOnSell = onSell.toList(),
                captureOnOpen = onOpen.toList(),
                rollingMaxN = rolling,
            )
        }

        private fun walk(
            expr: ExprAst,
            onBuy: MutableSet<String>,
            onSell: MutableSet<String>,
            onOpen: MutableSet<String>,
            rolling: MutableMap<String, Int>,
        ) {
            when (expr) {
                is Ref ->
                    when (expr.snapshot) {
                        null -> {}
                        SnapshotBuy -> onBuy.add(expr.name)
                        SnapshotSell -> onSell.add(expr.name)
                        SnapshotOpen -> onOpen.add(expr.name)
                        is SnapshotTPast -> {
                            val cur = rolling[expr.name] ?: 0
                            if (expr.snapshot.n > cur) rolling[expr.name] = expr.snapshot.n
                        }
                    }
                is BinaryOp -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is UnaryOp -> walk(expr.arg, onBuy, onSell, onOpen, rolling)
                is CmpOp -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is Between -> {
                    walk(expr.v, onBuy, onSell, onOpen, rolling)
                    walk(expr.lo, onBuy, onSell, onOpen, rolling)
                    walk(expr.hi, onBuy, onSell, onOpen, rolling)
                }
                is InList -> {
                    walk(expr.v, onBuy, onSell, onOpen, rolling)
                    expr.members.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                }
                is Crosses -> {
                    walk(expr.lhs, onBuy, onSell, onOpen, rolling)
                    walk(expr.rhs, onBuy, onSell, onOpen, rolling)
                }
                is CaseWhen -> {
                    expr.branches.forEach {
                        walk(it.first, onBuy, onSell, onOpen, rolling)
                        walk(it.second, onBuy, onSell, onOpen, rolling)
                    }
                    walk(expr.elseExpr, onBuy, onSell, onOpen, rolling)
                }
                is IndicatorCall -> expr.args.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                is Aggregate -> walk(expr.series, onBuy, onSell, onOpen, rolling)
                is FuncCall -> expr.args.forEach { walk(it, onBuy, onSell, onOpen, rolling) }
                is NumLit, is BoolLit, is StreamFieldRef, is AccountRef, is PositionRef, is StateAccessor -> {}
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/SnapshotPlan.kt
git commit -m "feat(dsl): scan rules for snapshot uses"
```

---

### Task 4: Extend `EvalContext` with `SnapshotStore`

State accessors and snapshot reads need access to the runtime stores. Add `snapshotStore` to `EvalContext`. Tests that construct EvalContext directly need an empty store.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt`
- Modify: every test that builds `EvalContext` directly

- [ ] **Step 1: Update `EvalContext`**

```kotlin
class EvalContext(
    val candle: Candle,
    val streamSymbols: Map<String, String>,
    val lets: Map<String, BigDecimal>,
    val strategyContext: StrategyContext,
    val snapshotStore: SnapshotStore = SnapshotStore(emptyMap()),
)
```

A default value of `SnapshotStore(emptyMap())` keeps the existing 11c1 tests compiling without modification. Production code (CompiledStrategy) always supplies a real store.

- [ ] **Step 2: Run all DSL tests**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: PASS — defaults make this a non-breaking extension.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt
git commit -m "feat(dsl): add snapshotStore to EvalContext"
```

---

### Task 5: Compile `Ref(name, snapshot)`

When a Ref carries a snapshot, the compiler emits a closure that reads from `SnapshotStore` keyed by `(ruleSymbol, name, kind)`. The `ruleSymbol` is supplied by `compile(..., ruleSymbol = ...)` — without it, snapshot Refs are an error.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerSnapshotTest.kt`

- [ ] **Step 1: Add the `ruleSymbol` parameter to `ExprCompiler.compile`**

```kotlin
class ExprCompiler(
    private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag(),
) {
    fun compile(
        expr: ExprAst,
        ruleSymbol: String? = null,
    ): CompiledExpr =
        when (expr) {
            // existing branches, recursing with ruleSymbol where applicable
            is Ref -> compileRef(expr, ruleSymbol)
            // ...
        }

    private fun compileRef(
        ref: Ref,
        ruleSymbol: String?,
    ): CompiledExpr {
        require(ref.snapshot != null) {
            "Bare Ref should have been substituted by LetResolver: ${ref.name}"
        }
        val sym = ruleSymbol ?: error("Snapshot ref ${ref.name}@${ref.snapshot} requires rule symbol context")
        val kind = ref.snapshot
        return when (kind) {
            is SnapshotTPast ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readRolling(sym, ref.name, kind.n)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
            else ->
                CompiledExpr { ctx ->
                    val v = ctx.snapshotStore.readSlot(sym, ref.name, kind)
                    if (v == null) Value.Undefined else Value.Num(v)
                }
        }
    }
}
```

Plumb `ruleSymbol` through the recursive helpers (BinaryOp, UnaryOp, CmpOp, Between, InList, CaseWhen, Aggregate's series, FuncCall args, etc.) — every recursive `compile(...)` call must propagate it.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerSnapshotTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctx(store: SnapshotStore): EvalContext =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
            snapshotStore = store,
        )

    @Test
    fun `Ref with SnapshotBuy reads slot via ruleSymbol`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("BTCUSDT", "fast", SnapshotBuy, BigDecimal("123"))
        val compiled = ExprCompiler().compile(Ref("fast", SnapshotBuy), ruleSymbol = "BTCUSDT")
        val v = compiled.evaluate(ctx(store)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123")
    }

    @Test
    fun `unset slot returns Undefined`() {
        val store = SnapshotStore(emptyMap())
        val compiled = ExprCompiler().compile(Ref("fast", SnapshotOpen), ruleSymbol = "BTCUSDT")
        assertThat(compiled.evaluate(ctx(store))).isEqualTo(Value.Undefined)
    }

    @Test
    fun `Ref with SnapshotTPast reads rolling buffer`() {
        val store = SnapshotStore(maxRollingPerName = mapOf("close" to 2))
        store.pushRolling("BTCUSDT", "close", BigDecimal("100"))
        store.pushRolling("BTCUSDT", "close", BigDecimal("110"))
        store.pushRolling("BTCUSDT", "close", BigDecimal("120"))
        val compiled = ExprCompiler().compile(Ref("close", SnapshotTPast(2)), ruleSymbol = "BTCUSDT")
        val v = compiled.evaluate(ctx(store)) as Value.Num
        assertThat(v.v).isEqualByComparingTo("100")
    }

    @Test
    fun `snapshot Ref without ruleSymbol context errors`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            ExprCompiler().compile(Ref("fast", SnapshotBuy))
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerSnapshotTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerSnapshotTest.kt
git commit -m "feat(dsl): compile snapshot refs against SnapshotStore"
```

---

### Task 6: `AggregateState`

A pure stateful aggregator. Two flavours:
- `SinceOpen`: keeps a running min/max/sum/count; reset clears all. `mean = sum/count`. Returns `null` when count == 0.
- `SinceT(n)`: ring buffer of last `n` values. `MIN`/`MAX`/`MEAN`/`SUM` computed on read. Returns `null` until buffer holds `n` samples.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/AggregateState.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/AggregateStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AggregateStateTest {
    @Test
    fun `sinceOpen MAX tracks running max`() {
        val s = AggregateState.sinceOpen(AggFn.MAX)
        assertThat(s.read()).isNull()
        s.update(BigDecimal("3"))
        s.update(BigDecimal("7"))
        s.update(BigDecimal("5"))
        assertThat(s.read()).isEqualByComparingTo("7")
    }

    @Test
    fun `sinceOpen MIN tracks running min`() {
        val s = AggregateState.sinceOpen(AggFn.MIN)
        s.update(BigDecimal("3"))
        s.update(BigDecimal("1"))
        s.update(BigDecimal("5"))
        assertThat(s.read()).isEqualByComparingTo("1")
    }

    @Test
    fun `sinceOpen SUM and MEAN`() {
        val s = AggregateState.sinceOpen(AggFn.SUM)
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        s.update(BigDecimal("3"))
        assertThat(s.read()).isEqualByComparingTo("6")
        val m = AggregateState.sinceOpen(AggFn.MEAN)
        m.update(BigDecimal("1"))
        m.update(BigDecimal("2"))
        m.update(BigDecimal("3"))
        assertThat(m.read()).isEqualByComparingTo("2")
    }

    @Test
    fun `sinceOpen reset clears`() {
        val s = AggregateState.sinceOpen(AggFn.MAX)
        s.update(BigDecimal("7"))
        s.reset()
        assertThat(s.read()).isNull()
        s.update(BigDecimal("2"))
        assertThat(s.read()).isEqualByComparingTo("2")
    }

    @Test
    fun `sinceT requires N samples`() {
        val s = AggregateState.sinceT(AggFn.MAX, n = 3)
        assertThat(s.read()).isNull()
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        assertThat(s.read()).isNull()
        s.update(BigDecimal("9"))
        assertThat(s.read()).isEqualByComparingTo("9")
    }

    @Test
    fun `sinceT slides window`() {
        val s = AggregateState.sinceT(AggFn.MAX, n = 3)
        s.update(BigDecimal("9"))
        s.update(BigDecimal("1"))
        s.update(BigDecimal("2"))
        // window = [9,1,2], max = 9
        assertThat(s.read()).isEqualByComparingTo("9")
        s.update(BigDecimal("3"))
        // window = [1,2,3], max = 3
        assertThat(s.read()).isEqualByComparingTo("3")
    }
}
```

- [ ] **Step 2: Implement `AggregateState.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.AggFn
import java.math.BigDecimal

sealed interface AggregateState {
    fun update(v: BigDecimal)

    fun read(): BigDecimal?

    fun reset()

    companion object {
        fun sinceOpen(fn: AggFn): AggregateState = SinceOpenState(fn)

        fun sinceT(
            fn: AggFn,
            n: Int,
        ): AggregateState {
            require(n > 0) { "sinceT N must be > 0: $n" }
            return SinceTState(fn, n)
        }
    }
}

private class SinceOpenState(private val fn: AggFn) : AggregateState {
    private var min: BigDecimal? = null
    private var max: BigDecimal? = null
    private var sum: BigDecimal = BigDecimal.ZERO
    private var count: Int = 0

    override fun update(v: BigDecimal) {
        min = min?.let { it.min(v) } ?: v
        max = max?.let { it.max(v) } ?: v
        sum = sum.add(v, Money.CONTEXT)
        count += 1
    }

    override fun read(): BigDecimal? {
        if (count == 0) return null
        return when (fn) {
            AggFn.MIN -> min
            AggFn.MAX -> max
            AggFn.SUM -> sum
            AggFn.MEAN -> sum.divide(BigDecimal(count), Money.CONTEXT)
        }
    }

    override fun reset() {
        min = null
        max = null
        sum = BigDecimal.ZERO
        count = 0
    }
}

private class SinceTState(
    private val fn: AggFn,
    private val n: Int,
) : AggregateState {
    private val buffer: ArrayDeque<BigDecimal> = ArrayDeque(n)

    override fun update(v: BigDecimal) {
        buffer.addLast(v)
        while (buffer.size > n) buffer.removeFirst()
    }

    override fun read(): BigDecimal? {
        if (buffer.size < n) return null
        return when (fn) {
            AggFn.MIN -> buffer.reduce { a, b -> a.min(b) }
            AggFn.MAX -> buffer.reduce { a, b -> a.max(b) }
            AggFn.SUM -> buffer.fold(BigDecimal.ZERO) { acc, x -> acc.add(x, Money.CONTEXT) }
            AggFn.MEAN ->
                buffer
                    .fold(BigDecimal.ZERO) { acc, x -> acc.add(x, Money.CONTEXT) }
                    .divide(BigDecimal(n), Money.CONTEXT)
        }
    }

    override fun reset() {
        buffer.clear()
    }
}
```

- [ ] **Step 3: Run tests**

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AggregateState.kt src/test/kotlin/com/qkt/dsl/compile/AggregateStateTest.kt
git commit -m "feat(dsl): add running aggregate state machines"
```

---

### Task 7: Compile `Aggregate`

`Aggregate(fn, series, window)` compiles to a `CompiledExpr` that owns one `AggregateState` per binding instance and reads it on evaluation. Update happens externally (Task 9 wires it into `CompiledStrategy.onCandle`).

The compiler needs to register every `AggregateState` it creates with the `CompiledStrategy` so the strategy knows what to update each bar and what to reset on position-open transitions. Use a `MutableList<AggregateBinding>` analogous to `IndicatorBinding.Bag`.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/AggregateBinding.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerAggregateTest.kt`

- [ ] **Step 1: Implement `AggregateBinding.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window

class AggregateBinding(
    val seriesEvaluator: CompiledExpr,
    val window: Window,
    val state: AggregateState,
    val ruleSymbol: String,
) {
    fun update(ctx: EvalContext) {
        val v = seriesEvaluator.evaluate(ctx)
        if (v is Value.Num) state.update(v.v)
    }

    fun resetIfSinceOpen() {
        if (window is SinceOpen) state.reset()
    }

    class Bag {
        private val list: MutableList<AggregateBinding> = mutableListOf()

        internal fun add(binding: AggregateBinding) {
            list.add(binding)
        }

        fun all(): List<AggregateBinding> = list

        fun bindingsForSymbol(symbol: String): List<AggregateBinding> = list.filter { it.ruleSymbol == symbol }

        companion object {
            fun stateFor(
                fn: AggFn,
                window: Window,
            ): AggregateState =
                when (window) {
                    SinceOpen -> AggregateState.sinceOpen(fn)
                    is SinceTPast -> AggregateState.sinceT(fn, window.n)
                }
        }
    }
}
```

- [ ] **Step 2: Update `ExprCompiler` to take an `AggregateBinding.Bag` and compile `Aggregate`**

Add to ctor:

```kotlin
class ExprCompiler(
    private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag(),
    private val aggregates: AggregateBinding.Bag = AggregateBinding.Bag(),
) {
```

Add the `Aggregate` branch:

```kotlin
            is Aggregate -> compileAggregate(expr, ruleSymbol)
```

```kotlin
    private fun compileAggregate(
        agg: Aggregate,
        ruleSymbol: String?,
    ): CompiledExpr {
        val sym = ruleSymbol ?: error("Aggregate requires rule symbol context")
        val state = AggregateBinding.Bag.stateFor(agg.fn, agg.window)
        val seriesEval = compile(agg.series, ruleSymbol)
        val binding = AggregateBinding(seriesEval, agg.window, state, sym)
        aggregates.add(binding)
        return CompiledExpr {
            val v = state.read()
            if (v == null) Value.Undefined else Value.Num(v)
        }
    }
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerAggregateTest {
    private fun candle(price: String) =
        Candle("BTCUSDT", BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ZERO, 0L, 60_000L)

    private fun ctx(c: Candle) =
        EvalContext(
            candle = c,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `MAX SINCE OPEN starts Undefined and tracks max after update`() {
        val aggBag = AggregateBinding.Bag()
        val ec = ExprCompiler(aggregates = aggBag)
        val expr = Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen)
        val compiled = ec.compile(expr, ruleSymbol = "BTCUSDT")
        val c1 = ctx(candle("100"))
        assertThat(compiled.evaluate(c1)).isEqualTo(Value.Undefined)
        aggBag.all().forEach { it.update(c1) }
        val c2 = ctx(candle("130"))
        aggBag.all().forEach { it.update(c2) }
        val c3 = ctx(candle("110"))
        aggBag.all().forEach { it.update(c3) }
        assertThat((compiled.evaluate(c3) as Value.Num).v).isEqualByComparingTo("130")
        aggBag.all().forEach { it.resetIfSinceOpen() }
        assertThat(compiled.evaluate(c3)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `MEAN SINCE T-3 returns Undefined until 3 samples`() {
        val aggBag = AggregateBinding.Bag()
        val ec = ExprCompiler(aggregates = aggBag)
        val expr = Aggregate(AggFn.MEAN, StreamFieldRef("btc", "close"), SinceTPast(3))
        val compiled = ec.compile(expr, ruleSymbol = "BTCUSDT")
        for (price in listOf("100", "110")) {
            val c = ctx(candle(price))
            aggBag.all().forEach { it.update(c) }
        }
        val c = ctx(candle("120"))
        assertThat(compiled.evaluate(c)).isEqualTo(Value.Undefined)
        aggBag.all().forEach { it.update(c) }
        val v = compiled.evaluate(c) as Value.Num
        assertThat(v.v).isEqualByComparingTo("110")
    }
}
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AggregateBinding.kt src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerAggregateTest.kt
git commit -m "feat(dsl): compile Aggregate expressions"
```

---

### Task 8: `PositionTransitions`

Per-stream `prev_qty` tracker. At the start of every `onCandle`, `observe(symbol, currentQty)` returns one of `Stay` / `OpenedFromZero` / `ClosedToZero` / `Flipped`. Used by `CompiledStrategy` to decide whether to reset SINCE-OPEN aggregates and clear @open snapshots.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/PositionTransitions.kt`

- [ ] **Step 1: Implement and commit**

```kotlin
package com.qkt.dsl.compile

import java.math.BigDecimal

enum class PositionTransition { Stay, OpenedFromZero, ClosedToZero, Flipped }

class PositionTransitions {
    private val prev: MutableMap<String, BigDecimal> = HashMap()

    fun observe(
        symbol: String,
        currentQty: BigDecimal,
    ): PositionTransition {
        val previousQty = prev[symbol] ?: BigDecimal.ZERO
        prev[symbol] = currentQty
        val wasZero = previousQty.signum() == 0
        val isZero = currentQty.signum() == 0
        return when {
            wasZero && isZero -> PositionTransition.Stay
            wasZero && !isZero -> PositionTransition.OpenedFromZero
            !wasZero && isZero -> PositionTransition.ClosedToZero
            !wasZero && !isZero && previousQty.signum() != currentQty.signum() -> PositionTransition.Flipped
            else -> PositionTransition.Stay
        }
    }
}
```

```bash
git add src/main/kotlin/com/qkt/dsl/compile/PositionTransitions.kt
git commit -m "feat(dsl): add per-stream position-transition tracker"
```

---

### Task 9: Wire `CompiledStrategy` lifecycle — onCandle prelude

At the start of every `onCandle`, before indicators update:
1. Observe transitions per stream symbol. If `ClosedToZero`: clear `@open` slots for that symbol.
2. After indicators update: push rolling snapshot values (per-candle `@T-N` capture) and update aggregates.
3. If `OpenedFromZero` was observed (prev_qty zero, now non-zero — i.e., the previous candle's emitted Buy/Sell filled): reset SINCE-OPEN aggregates for that symbol.

The order matters: clear @open *before* rule fire (so a rule reading `@open` after position closed sees `Undefined`); reset SINCE-OPEN *after* indicators but *before* aggregate update for the new bar.

To achieve this we need to compile-time know:
- The strategy's stream symbols (we have these).
- For each stream, the LET names captured for `@open` (from `SnapshotPlan`).
- For each stream, the rolling `(name → maxN)` map (from `SnapshotPlan`).
- The set of `AggregateBinding`s with `SinceOpen` (from `AggregateBinding.Bag`).

Each `LET name` referenced as `@open`/`@T-N` needs its compiled expression so we can evaluate it during capture. This is the per-rule LET RHS, but compiled against the *shared* `IndicatorBinding.Bag` so the indicator state aligns. Wire all this in `AstCompiler`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`

- [ ] **Step 1: Re-shape `AstCompiler.compile`**

Walk:
1. `LetResolver` over rule conditions (already keeps snapshot Refs).
2. `SnapshotPlan.scan(resolvedConditions)` → captureOnBuy/Sell/Open lists, rollingMaxN.
3. Build a `Map<String, CompiledExpr>` from each captured LET name to its compiled RHS, using a fresh `ExprCompiler` over the shared bindings (no ruleSymbol — LET RHSs don't contain snapshot refs, that would be circular).
4. Build the rules list: each `WhenThen.cond` is resolved + compiled with `ruleSymbol = streamSymbols[rule.action.stream]`.
5. Build a `SnapshotStore(maxRollingPerName = plan.rollingMaxN)`.
6. `CompiledStrategy` holds: `streamSymbols`, `bindings`, `aggregates`, `snapshotStore`, `snapshotPlan`, `letCompiledRhs`, `transitions`, `rules`.

Sketch of `CompiledStrategy.onCandle`:

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
            snapshotStore = snapshotStore,
        )

    // 1. Position transitions (per stream)
    for ((alias, symbol) in streamSymbols) {
        if (candle.symbol != symbol) continue
        val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
        val t = transitions.observe(symbol, qty)
        when (t) {
            PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                for (name in snapshotPlan.captureOnOpen) {
                    snapshotStore.clearSlot(symbol, name, SnapshotOpen)
                }
                aggregates.bindingsForSymbol(symbol).forEach { it.resetIfSinceOpen() }
            }
            PositionTransition.OpenedFromZero -> {
                aggregates.bindingsForSymbol(symbol).forEach { it.resetIfSinceOpen() }
            }
            PositionTransition.Stay -> {}
        }
    }

    // 2. Indicators update
    bindings.updateAll(ec)

    // 3. Per-candle rolling snapshot capture (after indicators are warm)
    for (name in snapshotPlan.rollingMaxN.keys) {
        val rhs = letCompiledRhs[name] ?: continue
        val v = rhs.evaluate(ec)
        for ((_, sym) in streamSymbols) {
            if (sym != candle.symbol) continue
            snapshotStore.pushRolling(sym, name, if (v is Value.Num) v.v else null)
        }
    }

    // 4. Aggregate updates
    for (b in aggregates.all()) {
        if (streamSymbols.values.first() != b.ruleSymbol) {
            // 11c2 has at most one stream — defensive guard for future
        }
        if (b.window is SinceOpen) {
            val curQty = ctx.positions.positionFor(b.ruleSymbol)?.quantity ?: BigDecimal.ZERO
            if (curQty.signum() != 0) b.update(ec)
        } else {
            b.update(ec)
        }
    }

    // 5. Rules
    for (rule in rules) {
        val sig = rule.fire(ec, ctx) ?: continue
        emit(sig)
    }
}
```

- [ ] **Step 2: Update `CompiledRule.fire` to handle the rule-fire snapshot capture**

Pass strategy-level capture handles into `CompiledRule`. On fire, after computing the Signal:
- If action is Buy or Sell: capture Buy/Sell snapshots for the rule's symbol (evaluate compiled LET RHSs).
- If pre-fire position quantity == 0: capture @open snapshots for that symbol.

```kotlin
class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> Signal,
    private val ruleSymbol: String,
    private val isBuy: Boolean,
    private val isSell: Boolean,
    private val onBuyCaptures: List<Pair<String, CompiledExpr>>,
    private val onSellCaptures: List<Pair<String, CompiledExpr>>,
    private val onOpenCaptures: List<Pair<String, CompiledExpr>>,
    private val store: SnapshotStore,
) {
    fun fire(
        ec: EvalContext,
        ctx: com.qkt.strategy.StrategyContext,
    ): com.qkt.strategy.Signal? {
        val v = condition.evaluate(ec)
        if (v !is Value.Bool || !v.v) return null

        val preFireQty = ctx.positions.positionFor(ruleSymbol)?.quantity ?: java.math.BigDecimal.ZERO
        val isOpen = preFireQty.signum() == 0 && (isBuy || isSell)

        if (isBuy) {
            for ((name, e) in onBuyCaptures) {
                val r = e.evaluate(ec)
                if (r is Value.Num) store.captureSlot(ruleSymbol, name, com.qkt.dsl.ast.SnapshotBuy, r.v)
            }
        }
        if (isSell) {
            for ((name, e) in onSellCaptures) {
                val r = e.evaluate(ec)
                if (r is Value.Num) store.captureSlot(ruleSymbol, name, com.qkt.dsl.ast.SnapshotSell, r.v)
            }
        }
        if (isOpen) {
            for ((name, e) in onOpenCaptures) {
                val r = e.evaluate(ec)
                if (r is Value.Num) store.captureSlot(ruleSymbol, name, com.qkt.dsl.ast.SnapshotOpen, r.v)
            }
        }
        return action(ec)
    }
}
```

- [ ] **Step 3: Wire everything in `AstCompiler.compile`**

Build the LET-RHS compile cache, the SnapshotPlan, the `AggregateBinding.Bag`, and pass the right capture lists to each `CompiledRule`. (Detail in the existing `AstCompiler`.)

- [ ] **Step 4: Run all DSL tests**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: PASS — all 11b/11c1 tests still green; the 11c2 tests in this branch pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt
git commit -m "feat(dsl): wire snapshot and aggregate lifecycle in CompiledStrategy"
```

---

### Task 10: Kotlin DSL — snapshot helpers

```kotlin
val ast = strategy("trail", version = 1) {
    val btc = stream(...)
    val fast by letting(ema(btc.close, 9))
    rule {
        whenever(fast crossesAbove ...)
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever(btc.close lt (fast at atOpen) - 5.bd)
        then { sell(btc, qty = 1.bd) }
    }
}
```

`fast at atOpen` reads `Ref("fast", SnapshotOpen)`.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Snapshots.kt`

- [ ] **Step 1: Implement and commit**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast

val atBuy: SnapshotKind = SnapshotBuy
val atSell: SnapshotKind = SnapshotSell
val atOpen: SnapshotKind = SnapshotOpen

fun atT(n: Int): SnapshotKind = SnapshotTPast(n)

infix fun ExprAst.at(snapshot: SnapshotKind): ExprAst {
    require(this is Ref) {
        "Snapshots only apply to LET references; got ${this::class.simpleName}"
    }
    return Ref(this.name, snapshot)
}
```

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/Snapshots.kt
git commit -m "feat(dsl): kotlin snapshot helpers"
```

---

### Task 11: Kotlin DSL — aggregate helpers

```kotlin
val highWaterMark by letting(runMax(btc.close, sinceOpen))
val twentyBarMean by letting(runMean(btc.close, sinceT(20)))
```

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Aggregates.kt`
- Create: `src/test/kotlin/com/qkt/dsl/kotlin/SnapshotAggregateBuildersTest.kt`

- [ ] **Step 1: Implement `Aggregates.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window

val sinceOpen: Window = SinceOpen

fun sinceT(n: Int): Window = SinceTPast(n)

fun runMin(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MIN, series, window)

fun runMax(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MAX, series, window)

fun runMean(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.MEAN, series, window)

fun runSum(
    series: ExprAst,
    window: Window,
): ExprAst = Aggregate(AggFn.SUM, series, window)
```

- [ ] **Step 2: Write the integration test**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotTPast
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SnapshotAggregateBuildersTest {
    @Test
    fun `at atOpen builds Ref with SnapshotOpen`() {
        val r = Ref("fast")
        assertThat(r at atOpen).isEqualTo(Ref("fast", SnapshotOpen))
    }

    @Test
    fun `at atT(n) builds Ref with SnapshotTPast`() {
        val r = Ref("fast")
        assertThat(r at atT(3)).isEqualTo(Ref("fast", SnapshotTPast(3)))
    }

    @Test
    fun `at on non-Ref throws`() {
        assertThatThrownBy { 1.bd at atOpen }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runMax with sinceOpen builds Aggregate`() {
        assertThat(runMax(1.bd, sinceOpen)).isEqualTo(Aggregate(AggFn.MAX, NumLit(1.toBigDecimal()), SinceOpen))
    }

    @Test
    fun `runMean with sinceT builds Aggregate with SinceTPast`() {
        assertThat(runMean(1.bd, sinceT(20))).isEqualTo(Aggregate(AggFn.MEAN, NumLit(1.toBigDecimal()), SinceTPast(20)))
    }
}
```

- [ ] **Step 3: Run tests, commit**

```bash
./gradlew test --tests 'com.qkt.dsl.kotlin.SnapshotAggregateBuildersTest'
git add src/main/kotlin/com/qkt/dsl/kotlin/Aggregates.kt src/test/kotlin/com/qkt/dsl/kotlin/SnapshotAggregateBuildersTest.kt
git commit -m "feat(dsl): kotlin runMin runMax runMean runSum helpers"
```

---

### Task 12: Refresh boundary lock

11c1's `UnsupportedAstTest` rejected `Aggregate` and snapshot Refs. Both work now. The remaining unsupported surface is `OPEN_ORDERS` and `Log/CLOSE/CANCEL` actions.

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `OPEN_ORDERS state is not supported in 11c2`() {
        assertThatThrownBy {
            ec.compile(StateAccessor(StateSource.OPEN_ORDERS, "btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

(`Log`/`CLOSE`/`CANCEL` actions are in `ActionCompilerTest` already; they continue to error in 11c2.)

- [ ] **Step 2: Run, commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.UnsupportedAstTest'
git add src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt
git commit -m "test(dsl): refresh boundary lock for 11c2"
```

---

### Task 13: End-to-end snapshot + aggregate test

A non-trivial strategy:
- Long entry on `fast crossesAbove slow`.
- Long exit when `btc.close < runMax(btc.close, sinceOpen) - X` — a chandelier-style trailing stop.
- Compare against a hand-written trailing-stop strategy on the same fixture.

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/SnapshotEndToEndTest.kt`

- [ ] **Step 1: Implement the test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.runMax
import com.qkt.dsl.kotlin.sinceOpen
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.indicators.catalog.EMA
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SnapshotEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    /** Hand-written reference: long-entry on fast crossing slow,
     *  exit when close < runningMaxClose - 5. */
    private class TrailingStopRef(
        symbol: String,
        fastPeriod: Int,
        slowPeriod: Int,
        private val drop: BigDecimal,
    ) : Strategy {
        private val sym = symbol
        private val fast = EMA(fastPeriod)
        private val slow = EMA(slowPeriod)
        private var prevFastAbove: Boolean? = null
        private var runningMax: BigDecimal? = null
        private var inLong = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}

        override fun onCandle(
            candle: Candle,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (candle.symbol != sym) return
            // close hits — re-evaluate state
            val qty = ctx.positions.positionFor(sym)?.quantity ?: BigDecimal.ZERO
            if (qty.signum() == 0 && inLong) {
                runningMax = null
                inLong = false
            }
            fast.update(candle.close)
            slow.update(candle.close)
            if (!fast.isReady || !slow.isReady) return
            val above = fast.value()!! > slow.value()!!
            val prev = prevFastAbove
            prevFastAbove = above
            if (qty.signum() != 0) {
                runningMax = runningMax?.let { it.max(candle.close) } ?: candle.close
                if (candle.close < runningMax!!.subtract(drop)) {
                    emit(Signal.Sell(sym, BigDecimal.ONE))
                }
            }
            if (prev == false && above && qty.signum() == 0) {
                emit(Signal.Buy(sym, BigDecimal.ONE))
            }
        }
    }

    @Test
    fun `dsl trailing stop equals handwritten reference`() {
        val sample =
            ticks(
                listOf(
                    "100", "101", "102", "104", "108", "112", "115", "120",
                    "118", "115", "112", "110", "108", "105", "100", "95",
                    "92", "90", "88", "85", "80", "82", "85", "90",
                    "95", "100", "108", "115", "120", "125",
                ),
            )

        val ast =
            strategy("trail", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 3))
                val slow by letting(ema(btc.close, period = 7))
                val hwm by letting(runMax(btc.close, sinceOpen))
                rule {
                    whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (btc.close lt (hwm - 5.bd)))
                    then { sell(btc, qty = 1.bd) }
                }
            }

        val dslStrategy = AstCompiler().compile(ast)
        val refStrategy = TrailingStopRef("BTCUSDT", 3, 7, BigDecimal("5"))

        val dsl =
            Backtest(
                strategies = listOf("trail" to dslStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val ref =
            Backtest(
                strategies = listOf("trail" to refStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(dsl.global.totalPnL).isEqualByComparingTo(ref.global.totalPnL)
        assertThat(dsl.trades.map { it.trade.symbol to it.trade.side })
            .isEqualTo(ref.trades.map { it.trade.symbol to it.trade.side })
        assertThat(dsl.trades).isNotEmpty
    }
}
```

> **Note:** if the reference and DSL diverge, the divergence is informative — most likely either the SINCE-OPEN reset timing or the position-check ordering. Stop after one failed attempt and reassess.

- [ ] **Step 2: Run, commit**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.SnapshotEndToEndTest'
git add src/test/kotlin/com/qkt/dsl/compile/SnapshotEndToEndTest.kt
git commit -m "test(dsl): end-to-end trailing stop with snapshot and aggregate"
```

---

### Task 14: Build green + ktlint

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew ktlintFormat`. If anything reformatted, commit `style: ktlint format 11c2 sources`.
- [ ] Re-run `./gradlew build`.

---

### Task 15: Phase changelog

**Files:**
- Create: `docs/phases/phase-11c2-snapshots-aggregates.md`

Cover: summary, what's new, usage cookbook (trailing stop with `runMax sinceOpen`, breakeven stop with `entry@open`, N-bar momentum filter with `runMean(close, sinceT(20))`, "did fast cross above slow in the last 5 bars" using `@T-N`), testing patterns (the `TrailingStopRef` pattern), known limitations (still no Log/CLOSE/CANCEL; multi-stream still 11e), references.

- [ ] Commit: `docs: phase 11c2 changelog`.

---

### Task 16: Pre-push checklist

Per qkt skill §4. Then hand off to `superpowers:finishing-a-development-branch`. Merge with `merge: phase 11c2 snapshots and aggregates`. Fill in the merge SHA on `main` as a `docs:` commit afterward.
