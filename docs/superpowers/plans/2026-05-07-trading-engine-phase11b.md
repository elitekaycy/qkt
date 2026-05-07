# Phase 11b — DSL Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the minimal end-to-end slice of the qkt DSL: an internal Kotlin DSL builder produces a `StrategyAst`, a compiler walks it to emit a runnable `Strategy`. The slice covers single-stream, single-timeframe, single-broker, market-order strategies with direct quantity sizing, comparisons, boolean composition, math, and the `EMA`/`RSI`/`ATR` indicators. The full AST shape from the master design ships in this phase even though only a subset is used at runtime — locking the contract early lets 11c–11f extend the compiler without reshaping AST nodes.

**Architecture:** Three new packages: `com.qkt.dsl.ast` (pure data hierarchy), `com.qkt.dsl.stdlib` (constants + indicator registry), `com.qkt.dsl.compile` (AST → `Strategy`). Plus `com.qkt.dsl.kotlin` for the Kotlin-side builders. The compiled `Strategy` updates indicators in `onCandle`, then evaluates each rule's condition; on `true`, emits the rule's action as a `Signal`. No changes to existing engine, broker, or backtest code — the DSL sits on top.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`

**Branch:** `phase11b-dsl-foundation` (to be created off `main` after the master-design merge).

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/dsl/ast/
├── StrategyAst.kt           # top-level + StreamDecl + LetDecl + ConstantDecl
├── ExprAst.kt               # ExprAst sealed hierarchy (literals, refs, ops, indicator calls)
├── Operators.kt             # BinOp / UnOp / Cmp / CrossDir / AggFn / StateSource enums
├── Window.kt                # Window sealed (SinceOpen, SinceTPast)  -- declared, unused in 11b
├── Snapshot.kt              # SnapshotKind sealed                   -- declared, unused in 11b
├── RuleAst.kt               # RuleAst + WhenThen + ActionAst hierarchy
├── ActionOpts.kt            # ActionOpts + SizingAst + OrderTypeAst + TifAst + ChildPriceAst
└── DefaultsBlock.kt         # DefaultsBlock                         -- declared, unused in 11b

src/main/kotlin/com/qkt/dsl/stdlib/
├── Constants.kt             # ONE_PERCENT family + BPS
└── IndicatorRegistry.kt     # name -> indicator factory

src/main/kotlin/com/qkt/dsl/compile/
├── CompiledExpr.kt          # interface + EvalContext
├── CompiledRule.kt          # rule binding (condition + action)
├── IndicatorBinding.kt      # one Indicator per IndicatorCall site
├── ExprCompiler.kt          # ExprAst -> CompiledExpr
├── ActionCompiler.kt        # ActionAst -> (EvalContext) -> Signal
└── AstCompiler.kt           # StrategyAst -> Strategy

src/main/kotlin/com/qkt/dsl/kotlin/
├── StrategyBuilder.kt       # top-level `strategy { ... }` builder
├── StreamRef.kt             # type-safe handle returned by SYMBOLS builder
├── ExprBuilders.kt          # arithmetic + comparison + boolean operator overloads
├── Indicators.kt            # EMA(), RSI(), ATR() returning IndicatorCall
└── Actions.kt               # buy(), sell() builders

src/test/kotlin/com/qkt/dsl/ast/
├── StrategyAstTest.kt
├── ExprAstTest.kt
└── RuleAstTest.kt

src/test/kotlin/com/qkt/dsl/stdlib/
├── ConstantsTest.kt
└── IndicatorRegistryTest.kt

src/test/kotlin/com/qkt/dsl/compile/
├── ExprCompilerLiteralsTest.kt
├── ExprCompilerOperatorsTest.kt
├── ExprCompilerStreamFieldTest.kt
├── ExprCompilerLetTest.kt
├── ExprCompilerIndicatorTest.kt
├── ActionCompilerTest.kt
├── AstCompilerTest.kt
├── EmaCrossoverEquivalenceTest.kt   # end-to-end: DSL strategy === handwritten
└── UnsupportedAstTest.kt            # 11b boundary lock

src/test/kotlin/com/qkt/dsl/kotlin/
└── StrategyBuilderTest.kt

docs/phases/
└── phase-11b-dsl-foundation.md      # phase changelog
```

### Modified files

None. Phase 11b is purely additive.

---

## Tasks

### Task 1: AST enums and operator codes

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/Operators.kt`
- Create: `src/test/kotlin/com/qkt/dsl/ast/ExprAstTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprAstTest {
    @Test
    fun `BinOp enumerates arithmetic and boolean operators`() {
        assertThat(BinOp.entries).containsExactlyInAnyOrder(
            BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.DIV, BinOp.AND, BinOp.OR,
        )
    }

    @Test
    fun `Cmp enumerates the six comparisons`() {
        assertThat(Cmp.entries).containsExactlyInAnyOrder(
            Cmp.GT, Cmp.LT, Cmp.GE, Cmp.LE, Cmp.EQ, Cmp.NE,
        )
    }

    @Test
    fun `UnOp covers negation and logical not`() {
        assertThat(UnOp.entries).containsExactlyInAnyOrder(UnOp.NEG, UnOp.NOT)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.ExprAstTest'`
Expected: FAIL — symbols not found.

- [ ] **Step 3: Implement enums**

```kotlin
package com.qkt.dsl.ast

enum class BinOp { ADD, SUB, MUL, DIV, AND, OR }

enum class UnOp { NEG, NOT }

enum class Cmp { GT, LT, GE, LE, EQ, NE }

enum class CrossDir { ABOVE, BELOW }

enum class AggFn { MIN, MAX, MEAN, SUM }

enum class StateSource { ACCOUNT, POSITION, POSITION_AVG_PRICE, OPEN_ORDERS }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.ExprAstTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/Operators.kt src/test/kotlin/com/qkt/dsl/ast/ExprAstTest.kt
git commit -m "feat(dsl): add AST operator enums"
```

---

### Task 2: AST literals and refs

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Create: `src/main/kotlin/com/qkt/dsl/ast/Snapshot.kt`
- Create: `src/main/kotlin/com/qkt/dsl/ast/Window.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/ast/ExprAstTest.kt`

- [ ] **Step 1: Add failing tests for literals and refs**

Append to `ExprAstTest.kt`:

```kotlin
    @Test
    fun `NumLit holds a BigDecimal value`() {
        val lit = NumLit(java.math.BigDecimal("1.5"))
        assertThat(lit.value).isEqualByComparingTo("1.5")
    }

    @Test
    fun `BoolLit captures booleans`() {
        assertThat(BoolLit(true).value).isTrue()
        assertThat(BoolLit(false).value).isFalse()
    }

    @Test
    fun `Ref defaults to realtime when no snapshot is given`() {
        val r = Ref(name = "fast", snapshot = null)
        assertThat(r.snapshot).isNull()
    }

    @Test
    fun `StreamFieldRef binds a stream alias and a field name`() {
        val r = StreamFieldRef(stream = "btc", field = "close")
        assertThat(r.stream).isEqualTo("btc")
        assertThat(r.field).isEqualTo("close")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.ExprAstTest'`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement `ExprAst`, `Snapshot`, `Window`**

`Snapshot.kt`:

```kotlin
package com.qkt.dsl.ast

sealed interface SnapshotKind

data object SnapshotBuy : SnapshotKind

data object SnapshotSell : SnapshotKind

data object SnapshotOpen : SnapshotKind

data class SnapshotTPast(val n: Int) : SnapshotKind {
    init {
        require(n > 0) { "SnapshotTPast.n must be > 0: $n" }
    }
}
```

`Window.kt`:

```kotlin
package com.qkt.dsl.ast

sealed interface Window

data object SinceOpen : Window

data class SinceTPast(val n: Int) : Window {
    init {
        require(n > 0) { "SinceTPast.n must be > 0: $n" }
    }
}
```

`ExprAst.kt`:

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal

sealed interface ExprAst

data class NumLit(val value: BigDecimal) : ExprAst

data class BoolLit(val value: Boolean) : ExprAst

data class Ref(
    val name: String,
    val snapshot: SnapshotKind? = null,
) : ExprAst

data class StreamFieldRef(
    val stream: String,
    val field: String,
) : ExprAst

data class IndicatorCall(
    val name: String,
    val args: List<ExprAst>,
) : ExprAst

data class BinaryOp(
    val op: BinOp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class UnaryOp(
    val op: UnOp,
    val arg: ExprAst,
) : ExprAst

data class CmpOp(
    val op: Cmp,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class Between(
    val v: ExprAst,
    val lo: ExprAst,
    val hi: ExprAst,
) : ExprAst

data class InList(
    val v: ExprAst,
    val members: List<ExprAst>,
) : ExprAst

data class Crosses(
    val direction: CrossDir,
    val lhs: ExprAst,
    val rhs: ExprAst,
) : ExprAst

data class CaseWhen(
    val branches: List<Pair<ExprAst, ExprAst>>,
    val elseExpr: ExprAst,
) : ExprAst

data class Aggregate(
    val fn: AggFn,
    val series: ExprAst,
    val window: Window,
) : ExprAst

data class AccountRef(val field: String) : ExprAst

data class PositionRef(val stream: String) : ExprAst

data class StateAccessor(
    val source: StateSource,
    val key: String,
) : ExprAst
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.ExprAstTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt src/main/kotlin/com/qkt/dsl/ast/Snapshot.kt src/main/kotlin/com/qkt/dsl/ast/Window.kt src/test/kotlin/com/qkt/dsl/ast/ExprAstTest.kt
git commit -m "feat(dsl): add AST expression nodes"
```

---

### Task 3: AST rules, actions, and order shapes

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt`
- Create: `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`
- Create: `src/test/kotlin/com/qkt/dsl/ast/RuleAstTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleAstTest {
    @Test
    fun `WhenThen pairs a condition with an action`() {
        val rule = WhenThen(
            cond = BoolLit(true),
            action = Buy(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))),
        )
        assertThat(rule.cond).isEqualTo(BoolLit(true))
        assertThat(rule.action).isInstanceOf(Buy::class.java)
    }

    @Test
    fun `Buy carries stream alias and options`() {
        val buy = Buy(stream = "gold", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("2")))))
        assertThat(buy.stream).isEqualTo("gold")
        assertThat(buy.opts.sizing).isEqualTo(SizeQty(NumLit(BigDecimal("2"))))
    }

    @Test
    fun `ActionOpts has all-null defaults`() {
        val opts = ActionOpts()
        assertThat(opts.sizing).isNull()
        assertThat(opts.orderType).isNull()
        assertThat(opts.tif).isNull()
        assertThat(opts.bracket).isNull()
        assertThat(opts.oco).isNull()
    }

    @Test
    fun `CloseAll and CancelAll are singletons`() {
        assertThat(CloseAll).isSameAs(CloseAll)
        assertThat(CancelAll).isSameAs(CancelAll)
    }

    @Test
    fun `Market is the default order-type singleton`() {
        assertThat(Market).isSameAs(Market)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.RuleAstTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `RuleAst.kt`**

```kotlin
package com.qkt.dsl.ast

sealed interface RuleAst

data class WhenThen(
    val cond: ExprAst,
    val action: ActionAst,
) : RuleAst

sealed interface ActionAst

data class Buy(
    val stream: String,
    val opts: ActionOpts = ActionOpts(),
) : ActionAst

data class Sell(
    val stream: String,
    val opts: ActionOpts = ActionOpts(),
) : ActionAst

data class Close(val stream: String) : ActionAst

data object CloseAll : ActionAst

data class Cancel(val stream: String) : ActionAst

data object CancelAll : ActionAst

data class Log(val message: String) : ActionAst
```

- [ ] **Step 4: Implement `ActionOpts.kt`**

```kotlin
package com.qkt.dsl.ast

data class ActionOpts(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val bracket: BracketAst? = null,
    val oco: OcoAst? = null,
)

sealed interface SizingAst

data class SizeQty(val expr: ExprAst) : SizingAst

data class SizeNotional(val usd: ExprAst) : SizingAst

data class SizePctEquity(val frac: ExprAst) : SizingAst

data class SizePctBalance(val frac: ExprAst) : SizingAst

data class SizeRiskFrac(val frac: ExprAst) : SizingAst

data class SizeRiskAbs(val usd: ExprAst) : SizingAst

data class SizePositionFull(val stream: String) : SizingAst

sealed interface OrderTypeAst

data object Market : OrderTypeAst

data class Limit(val price: ExprAst) : OrderTypeAst

data class Stop(val price: ExprAst) : OrderTypeAst

data class StopLimit(
    val stopPrice: ExprAst,
    val limitPrice: ExprAst,
) : OrderTypeAst

data class TrailingBy(val distance: ExprAst) : OrderTypeAst

data class TrailingPct(val frac: ExprAst) : OrderTypeAst

sealed interface ChildPriceAst

data class ChildAt(val price: ExprAst) : ChildPriceAst

data class ChildBy(val distance: ExprAst) : ChildPriceAst

data class ChildPct(val frac: ExprAst) : ChildPriceAst

data class ChildRr(val multiplier: ExprAst) : ChildPriceAst

data class BracketAst(
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
)

data class OcoAst(
    val stop: ChildPriceAst,
    val limit: ChildPriceAst,
)

sealed interface TifAst

data object Gtc : TifAst

data object Ioc : TifAst

data object Fok : TifAst

data object Day : TifAst

data class Gtd(val until: ExprAst) : TifAst
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.RuleAstTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt src/test/kotlin/com/qkt/dsl/ast/RuleAstTest.kt
git commit -m "feat(dsl): add AST rule and action nodes"
```

---

### Task 4: AST top-level and `DefaultsBlock`

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`
- Create: `src/main/kotlin/com/qkt/dsl/ast/DefaultsBlock.kt`
- Create: `src/test/kotlin/com/qkt/dsl/ast/StrategyAstTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StrategyAstTest {
    @Test
    fun `StrategyAst captures name version streams lets defaults rules`() {
        val ast = StrategyAst(
            name = "ema_x",
            version = 1,
            streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
            constants = emptyList(),
            lets = listOf(LetDecl("fast", IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("9")))))),
            defaults = null,
            rules = emptyList(),
        )
        assertThat(ast.name).isEqualTo("ema_x")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.streams).hasSize(1)
        assertThat(ast.lets).hasSize(1)
    }

    @Test
    fun `StrategyAst rejects empty name`() {
        assertThatThrownBy {
            StrategyAst(name = "", version = 1, streams = emptyList(), constants = emptyList(), lets = emptyList(), defaults = null, rules = emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StrategyAst rejects negative version`() {
        assertThatThrownBy {
            StrategyAst(name = "x", version = -1, streams = emptyList(), constants = emptyList(), lets = emptyList(), defaults = null, rules = emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StreamDecl rejects empty alias`() {
        assertThatThrownBy {
            StreamDecl(alias = "", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.StrategyAstTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `StrategyAst.kt`**

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal

data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
) {
    init {
        require(name.isNotBlank()) { "StrategyAst.name must not be blank" }
        require(version >= 0) { "StrategyAst.version must be >= 0: $version" }
    }
}

data class StreamDecl(
    val alias: String,
    val broker: String,
    val symbol: String,
    val timeframe: String,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
    }
}

data class ConstantDecl(
    val name: String,
    val value: BigDecimal,
) {
    init {
        require(name.isNotBlank()) { "ConstantDecl.name must not be blank" }
    }
}

data class LetDecl(
    val name: String,
    val expr: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "LetDecl.name must not be blank" }
    }
}
```

- [ ] **Step 4: Implement `DefaultsBlock.kt`**

```kotlin
package com.qkt.dsl.ast

data class DefaultsBlock(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
    val trailing: OrderTypeAst? = null,
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.StrategyAstTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt src/main/kotlin/com/qkt/dsl/ast/DefaultsBlock.kt src/test/kotlin/com/qkt/dsl/ast/StrategyAstTest.kt
git commit -m "feat(dsl): add AST root and defaults block"
```

---

### Task 5: Standard-library constants

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/stdlib/Constants.kt`
- Create: `src/test/kotlin/com/qkt/dsl/stdlib/ConstantsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConstantsTest {
    @Test
    fun `named percent constants resolve to expected decimals`() {
        assertThat(Constants.HALF_PERCENT).isEqualByComparingTo("0.005")
        assertThat(Constants.ONE_PERCENT).isEqualByComparingTo("0.01")
        assertThat(Constants.TWO_PERCENT).isEqualByComparingTo("0.02")
        assertThat(Constants.THREE_PERCENT).isEqualByComparingTo("0.03")
        assertThat(Constants.FIVE_PERCENT).isEqualByComparingTo("0.05")
        assertThat(Constants.TEN_PERCENT).isEqualByComparingTo("0.10")
        assertThat(Constants.QUARTER_PERCENT).isEqualByComparingTo("0.0025")
        assertThat(Constants.BPS).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `lookup by name returns the same value`() {
        assertThat(Constants.byName("ONE_PERCENT")).isEqualByComparingTo("0.01")
        assertThat(Constants.byName("UNKNOWN")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.stdlib.ConstantsTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `Constants.kt`**

```kotlin
package com.qkt.dsl.stdlib

import java.math.BigDecimal

object Constants {
    val HALF_PERCENT: BigDecimal = BigDecimal("0.005")
    val ONE_PERCENT: BigDecimal = BigDecimal("0.01")
    val TWO_PERCENT: BigDecimal = BigDecimal("0.02")
    val THREE_PERCENT: BigDecimal = BigDecimal("0.03")
    val FIVE_PERCENT: BigDecimal = BigDecimal("0.05")
    val TEN_PERCENT: BigDecimal = BigDecimal("0.10")
    val QUARTER_PERCENT: BigDecimal = BigDecimal("0.0025")
    val BPS: BigDecimal = BigDecimal("0.0001")

    private val table: Map<String, BigDecimal> =
        mapOf(
            "HALF_PERCENT" to HALF_PERCENT,
            "ONE_PERCENT" to ONE_PERCENT,
            "TWO_PERCENT" to TWO_PERCENT,
            "THREE_PERCENT" to THREE_PERCENT,
            "FIVE_PERCENT" to FIVE_PERCENT,
            "TEN_PERCENT" to TEN_PERCENT,
            "QUARTER_PERCENT" to QUARTER_PERCENT,
            "BPS" to BPS,
        )

    fun byName(name: String): BigDecimal? = table[name]
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.stdlib.ConstantsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/stdlib/Constants.kt src/test/kotlin/com/qkt/dsl/stdlib/ConstantsTest.kt
git commit -m "feat(dsl): add named percent constants stdlib"
```

---

### Task 6: Indicator registry

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt`
- Create: `src/test/kotlin/com/qkt/dsl/stdlib/IndicatorRegistryTest.kt`

The registry knows two input shapes: indicators that take a numeric series (`EMA`, `RSI`) and indicators that take a `Candle` series (`ATR`). The compiler uses this distinction when wiring updates.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IndicatorRegistryTest {
    @Test
    fun `EMA RSI ATR are registered`() {
        assertThat(IndicatorRegistry.has("EMA")).isTrue()
        assertThat(IndicatorRegistry.has("RSI")).isTrue()
        assertThat(IndicatorRegistry.has("ATR")).isTrue()
    }

    @Test
    fun `EMA spec wants a numeric series and one period arg`() {
        val spec = IndicatorRegistry.spec("EMA")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.NUMERIC_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `ATR spec wants a candle series and one period arg`() {
        val spec = IndicatorRegistry.spec("ATR")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.CANDLE_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `creating EMA returns a Numeric indicator`() {
        val ind = IndicatorRegistry.create("EMA", listOf(java.math.BigDecimal("9")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.EMA::class.java)
    }

    @Test
    fun `creating with the wrong arity throws`() {
        assertThatThrownBy { IndicatorRegistry.create("EMA", emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown indicator returns null spec`() {
        assertThat(IndicatorRegistry.spec("UNKNOWN")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.stdlib.IndicatorRegistryTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `IndicatorRegistry.kt`**

```kotlin
package com.qkt.dsl.stdlib

import com.qkt.indicators.IndicatorOutput
import com.qkt.indicators.catalog.ATR
import com.qkt.indicators.catalog.EMA
import com.qkt.indicators.catalog.RSI
import java.math.BigDecimal

enum class IndicatorInput { NUMERIC_SERIES, CANDLE_SERIES }

data class IndicatorSpec(
    val name: String,
    val inputKind: IndicatorInput,
    val arity: Int,
    val factory: (List<BigDecimal>) -> IndicatorOutput,
)

object IndicatorRegistry {
    private val table: Map<String, IndicatorSpec> =
        mapOf(
            "EMA" to IndicatorSpec("EMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                EMA(period = args[0].toInt())
            },
            "RSI" to IndicatorSpec("RSI", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
                RSI(period = args[0].toInt())
            },
            "ATR" to IndicatorSpec("ATR", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
                ATR(period = args[0].toInt())
            },
        )

    fun has(name: String): Boolean = table.containsKey(name)

    fun spec(name: String): IndicatorSpec? = table[name]

    fun create(
        name: String,
        constArgs: List<BigDecimal>,
    ): IndicatorOutput {
        val s = spec(name) ?: error("Unknown indicator: $name")
        require(constArgs.size == s.arity - 1) {
            "Indicator $name expects ${s.arity - 1} constant args, got ${constArgs.size}"
        }
        return s.factory(constArgs)
    }
}
```

The arity convention: arg 0 is the series input (resolved at compile time, not passed here); the rest are constants. `arity` is total positional args including the series.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.stdlib.IndicatorRegistryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt src/test/kotlin/com/qkt/dsl/stdlib/IndicatorRegistryTest.kt
git commit -m "feat(dsl): add indicator registry"
```

---

### Task 7: Compiled-expr interface and eval context

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt`

This task introduces the runtime contract used by every compiled node. There are no tests of the interface itself — Tasks 8–12 cover behavior.

- [ ] **Step 1: Implement `CompiledExpr.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
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
)

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt
git commit -m "feat(dsl): add compiled-expression contract"
```

---

### Task 8: Compile literals

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLiteralsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerLiteralsTest {
    private val candle = Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle = candle, streamSymbols = emptyMap(), lets = emptyMap())

    @Test
    fun `numeric literal evaluates to its value`() {
        val compiled = ExprCompiler().compile(NumLit(BigDecimal("3.5")))
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Num(BigDecimal("3.5")))
    }

    @Test
    fun `boolean literal evaluates to its value`() {
        val compiled = ExprCompiler().compile(BoolLit(true))
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerLiteralsTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `ExprCompiler` literal handling**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit

class ExprCompiler {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerLiteralsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLiteralsTest.kt
git commit -m "feat(dsl): compile literal expressions"
```

---

### Task 9: Compile arithmetic, comparison, and boolean operators

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerOperatorsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerOperatorsTest {
    private val candle = Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle = candle, streamSymbols = emptyMap(), lets = emptyMap())

    @Test
    fun `addition`() {
        val v = ExprCompiler().compile(BinaryOp(BinOp.ADD, NumLit(BigDecimal("2")), NumLit(BigDecimal("3"))))
            .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("5")
    }

    @Test
    fun `subtraction`() {
        val v = ExprCompiler().compile(BinaryOp(BinOp.SUB, NumLit(BigDecimal("5")), NumLit(BigDecimal("2"))))
            .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("3")
    }

    @Test
    fun `multiplication`() {
        val v = ExprCompiler().compile(BinaryOp(BinOp.MUL, NumLit(BigDecimal("4")), NumLit(BigDecimal("3"))))
            .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("12")
    }

    @Test
    fun `division uses Money context`() {
        val v = ExprCompiler().compile(BinaryOp(BinOp.DIV, NumLit(BigDecimal("10")), NumLit(BigDecimal("3"))))
            .evaluate(ctx) as Value.Num
        // Just check that Money.CONTEXT precision is used:
        assertThat(v.v.precision()).isLessThanOrEqualTo(Money.CONTEXT.precision)
    }

    @Test
    fun `comparison greater-than`() {
        val v = ExprCompiler().compile(CmpOp(Cmp.GT, NumLit(BigDecimal("5")), NumLit(BigDecimal("3"))))
            .evaluate(ctx) as Value.Bool
        assertThat(v.v).isTrue()
    }

    @Test
    fun `boolean and`() {
        val v = ExprCompiler().compile(BinaryOp(BinOp.AND, BoolLit(true), BoolLit(false)))
            .evaluate(ctx) as Value.Bool
        assertThat(v.v).isFalse()
    }

    @Test
    fun `unary not`() {
        val v = ExprCompiler().compile(UnaryOp(UnOp.NOT, BoolLit(false)))
            .evaluate(ctx) as Value.Bool
        assertThat(v.v).isTrue()
    }

    @Test
    fun `unary neg`() {
        val v = ExprCompiler().compile(UnaryOp(UnOp.NEG, NumLit(BigDecimal("2"))))
            .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("-2")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerOperatorsTest'`
Expected: FAIL.

- [ ] **Step 3: Extend `ExprCompiler.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp

class ExprCompiler {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            is BinaryOp -> compileBinary(expr)
            is UnaryOp -> compileUnary(expr)
            is CmpOp -> compileCmp(expr)
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    private fun compileBinary(op: BinaryOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
        return when (op.op) {
            BinOp.ADD -> CompiledExpr { ctx -> Value.Num((l.evaluate(ctx) as Value.Num).v.add((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT)) }
            BinOp.SUB -> CompiledExpr { ctx -> Value.Num((l.evaluate(ctx) as Value.Num).v.subtract((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT)) }
            BinOp.MUL -> CompiledExpr { ctx -> Value.Num((l.evaluate(ctx) as Value.Num).v.multiply((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT)) }
            BinOp.DIV -> CompiledExpr { ctx -> Value.Num((l.evaluate(ctx) as Value.Num).v.divide((r.evaluate(ctx) as Value.Num).v, Money.CONTEXT)) }
            BinOp.AND -> CompiledExpr { ctx -> Value.Bool((l.evaluate(ctx) as Value.Bool).v && (r.evaluate(ctx) as Value.Bool).v) }
            BinOp.OR -> CompiledExpr { ctx -> Value.Bool((l.evaluate(ctx) as Value.Bool).v || (r.evaluate(ctx) as Value.Bool).v) }
        }
    }

    private fun compileUnary(op: UnaryOp): CompiledExpr {
        val a = compile(op.arg)
        return when (op.op) {
            UnOp.NEG -> CompiledExpr { ctx -> Value.Num((a.evaluate(ctx) as Value.Num).v.negate(Money.CONTEXT)) }
            UnOp.NOT -> CompiledExpr { ctx -> Value.Bool(!(a.evaluate(ctx) as Value.Bool).v) }
        }
    }

    private fun compileCmp(op: CmpOp): CompiledExpr {
        val l = compile(op.lhs)
        val r = compile(op.rhs)
        return CompiledExpr { ctx ->
            val lv = (l.evaluate(ctx) as Value.Num).v
            val rv = (r.evaluate(ctx) as Value.Num).v
            val c = lv.compareTo(rv)
            Value.Bool(
                when (op.op) {
                    Cmp.GT -> c > 0
                    Cmp.LT -> c < 0
                    Cmp.GE -> c >= 0
                    Cmp.LE -> c <= 0
                    Cmp.EQ -> c == 0
                    Cmp.NE -> c != 0
                },
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerOperatorsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerOperatorsTest.kt
git commit -m "feat(dsl): compile arithmetic comparison and boolean operators"
```

---

### Task 10: Compile `StreamFieldRef` against the current candle

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt`

The compiler validates the field name (`close`/`open`/`high`/`low`/`volume`) at compile time and resolves the stream alias to a symbol via `EvalContext.streamSymbols`. At evaluation time it pulls the value from the current candle. If the candle's symbol does not match, the result is `Value.Undefined`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerStreamFieldTest {
    private val candle =
        Candle(
            symbol = "BTCUSDT",
            open = BigDecimal("100"),
            high = BigDecimal("110"),
            low = BigDecimal("90"),
            close = BigDecimal("105"),
            volume = BigDecimal("1.5"),
            startTime = 0L,
            endTime = 60_000L,
        )
    private val ctx = EvalContext(candle = candle, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())

    @Test
    fun `close maps to candle close`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105")
    }

    @Test
    fun `open maps to candle open`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "open")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("100")
    }

    @Test
    fun `high low volume map correctly`() {
        val ec = ExprCompiler()
        assertThat((ec.compile(StreamFieldRef("btc", "high")).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("110")
        assertThat((ec.compile(StreamFieldRef("btc", "low")).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("90")
        assertThat((ec.compile(StreamFieldRef("btc", "volume")).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("1.5")
    }

    @Test
    fun `price is alias for close`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "price")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105")
    }

    @Test
    fun `unknown field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(StreamFieldRef("btc", "wat")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown stream alias is rejected at evaluation time`() {
        val noStream = EvalContext(candle = candle, streamSymbols = emptyMap(), lets = emptyMap())
        assertThatThrownBy { ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(noStream) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `mismatched candle symbol returns Undefined`() {
        val otherCandle = candle.copy(symbol = "GOLD")
        val otherCtx = EvalContext(candle = otherCandle, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())
        val v = ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(otherCtx)
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: FAIL.

- [ ] **Step 3: Extend `ExprCompiler.kt`**

Add a `StreamFieldRef` branch to the `when`:

```kotlin
            is StreamFieldRef -> compileStreamField(expr)
```

And:

```kotlin
    private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
        require(ref.field in setOf("close", "open", "high", "low", "volume", "price")) {
            "Unknown stream field for ${ref.stream}: ${ref.field}"
        }
        return CompiledExpr { ctx ->
            val symbol = ctx.streamSymbols[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            if (ctx.candle.symbol != symbol) {
                Value.Undefined
            } else {
                Value.Num(
                    when (ref.field) {
                        "close", "price" -> ctx.candle.close
                        "open" -> ctx.candle.open
                        "high" -> ctx.candle.high
                        "low" -> ctx.candle.low
                        "volume" -> ctx.candle.volume
                        else -> error("unreachable")
                    },
                )
            }
        }
    }
```

Don't forget to import `StreamFieldRef`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerStreamFieldTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStreamFieldTest.kt
git commit -m "feat(dsl): compile stream field references"
```

---

### Task 11: Resolve `LET` aliases via expression substitution

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLetTest.kt`

`LET` aliases are resolved at compile time by substituting their RHS expression at every `Ref` use. `Ref` with a non-null `snapshot` is rejected — snapshots arrive in 11c.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotOpen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerLetTest {
    @Test
    fun `single LET binding is substituted at the use site`() {
        val lets = listOf(LetDecl("two", NumLit(BigDecimal("2"))))
        val expr = BinaryOp(BinOp.ADD, Ref("two"), NumLit(BigDecimal("3")))
        val resolved = LetResolver(lets).resolve(expr)
        assertThat(resolved).isEqualTo(BinaryOp(BinOp.ADD, NumLit(BigDecimal("2")), NumLit(BigDecimal("3"))))
    }

    @Test
    fun `chained LETs resolve transitively`() {
        val lets = listOf(
            LetDecl("a", NumLit(BigDecimal("1"))),
            LetDecl("b", BinaryOp(BinOp.ADD, Ref("a"), NumLit(BigDecimal("1")))),
        )
        val resolved = LetResolver(lets).resolve(Ref("b"))
        assertThat(resolved).isEqualTo(BinaryOp(BinOp.ADD, NumLit(BigDecimal("1")), NumLit(BigDecimal("1"))))
    }

    @Test
    fun `unknown reference is rejected`() {
        assertThatThrownBy { LetResolver(emptyList()).resolve(Ref("missing")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `snapshot references are rejected in 11b`() {
        val lets = listOf(LetDecl("x", NumLit(BigDecimal.ONE)))
        assertThatThrownBy { LetResolver(lets).resolve(Ref("x", snapshot = SnapshotOpen)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerLetTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `LetResolver.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnaryOp

class LetResolver(lets: List<LetDecl>) {
    private val table: Map<String, ExprAst> = lets.associate { it.name to it.expr }

    fun resolve(expr: ExprAst): ExprAst =
        when (expr) {
            is Ref -> {
                require(expr.snapshot == null) {
                    "Snapshot references are not supported in 11b: ${expr.name}@${expr.snapshot}"
                }
                val target = table[expr.name] ?: error("Unknown reference: ${expr.name}")
                resolve(target)
            }
            is BinaryOp -> BinaryOp(expr.op, resolve(expr.lhs), resolve(expr.rhs))
            is UnaryOp -> UnaryOp(expr.op, resolve(expr.arg))
            is CmpOp -> CmpOp(expr.op, resolve(expr.lhs), resolve(expr.rhs))
            is IndicatorCall -> IndicatorCall(expr.name, expr.args.map { resolve(it) })
            is Between -> Between(resolve(expr.v), resolve(expr.lo), resolve(expr.hi))
            is InList -> InList(resolve(expr.v), expr.members.map { resolve(it) })
            is Crosses -> Crosses(expr.direction, resolve(expr.lhs), resolve(expr.rhs))
            is CaseWhen -> CaseWhen(expr.branches.map { resolve(it.first) to resolve(it.second) }, resolve(expr.elseExpr))
            is Aggregate -> Aggregate(expr.fn, resolve(expr.series), expr.window)
            is NumLit, is BoolLit, is StreamFieldRef, is AccountRef, is PositionRef, is StateAccessor -> expr
        }

    init {
        require(table.size == lets.size) {
            "Duplicate LET name in: ${lets.map { it.name }}"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerLetTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerLetTest.kt
git commit -m "feat(dsl): resolve LET aliases via substitution"
```

---

### Task 12: Indicator binding and `IndicatorCall` compilation

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIndicatorTest.kt`

`IndicatorCall` compiles to a `CompiledExpr` that reads the indicator's current value from a per-call instance. The compiler returns `Value.Undefined` if the indicator is not yet ready. The instance lives in an `IndicatorBinding`, owned by the AST compiler — see Task 14 for ownership.

11b only supports the input shape `IndicatorCall(name, [StreamFieldRef(stream, field), ...consts])` for `EMA`/`RSI` and `IndicatorCall(name, [streamSymbolRef, ...consts])` for `ATR`. Indicator-on-indicator composition arrives in 11c.

For 11b, the ATR input form is `IndicatorCall("ATR", [<bare-stream-name as Ref masquerading via StreamFieldRef("X", "candle")>, period])` — but rather than overload `StreamFieldRef`, we introduce a small marker: `StreamFieldRef(stream, "candle")` represents "the whole candle of `stream`". The compiler treats it as the candle-series input.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerIndicatorTest {
    @Test
    fun `EMA over close evaluates to Undefined when not warm`() {
        val bindings = IndicatorBinding.Bag()
        val expr = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(expr)
        val candle = Candle("BTCUSDT", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 1L)
        val ctx = EvalContext(candle = candle, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())
        bindings.updateAll(ctx)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `EMA over close yields a value once warm`() {
        val bindings = IndicatorBinding.Bag()
        val expr = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(expr)
        var ctx: EvalContext? = null
        for (price in listOf("100", "110", "120")) {
            val c = Candle("BTCUSDT", BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ZERO, 0L, 1L)
            ctx = EvalContext(candle = c, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())
            bindings.updateAll(ctx)
        }
        val v = compiled.evaluate(ctx!!) as Value.Num
        assertThat(v.v).isEqualByComparingTo("110")
    }

    @Test
    fun `unknown indicator name is rejected`() {
        assertThatThrownBy {
            ExprCompiler(IndicatorBinding.Bag())
                .compile(IndicatorCall("UNKNOWN", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal.ONE))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `wrong arity is rejected`() {
        assertThatThrownBy {
            ExprCompiler(IndicatorBinding.Bag())
                .compile(IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerIndicatorTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `IndicatorBinding.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import com.qkt.marketdata.Candle
import java.math.BigDecimal

class IndicatorBinding(
    val call: IndicatorCall,
    val indicator: IndicatorOutput,
    val streamAlias: String,
    val field: String?,
    val inputKind: IndicatorInput,
) {
    @Suppress("UNCHECKED_CAST")
    fun update(ctx: EvalContext) {
        val symbol = ctx.streamSymbols[streamAlias] ?: error("Unknown stream alias: $streamAlias")
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
                        else -> error("Numeric indicator on stream '$streamAlias' requires a numeric field; got '$field'")
                    }
                (indicator as Indicator<BigDecimal>).update(v)
            }
            IndicatorInput.CANDLE_SERIES -> {
                (indicator as Indicator<Candle>).update(ctx.candle)
            }
        }
    }

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
                    require(it is NumLit) { "Indicator ${call.name} non-series arg must be a numeric literal in 11b" }
                    it.value
                }
            val streamAlias: String
            val field: String?
            when (spec.inputKind) {
                IndicatorInput.NUMERIC_SERIES -> {
                    require(seriesArg is StreamFieldRef) {
                        "Indicator ${call.name} series arg must be a stream field in 11b"
                    }
                    streamAlias = seriesArg.stream
                    field = seriesArg.field
                }
                IndicatorInput.CANDLE_SERIES -> {
                    require(seriesArg is StreamFieldRef && seriesArg.field == "candle") {
                        "Indicator ${call.name} series arg must be the whole stream in 11b"
                    }
                    streamAlias = seriesArg.stream
                    field = null
                }
            }
            val ind = IndicatorRegistry.create(call.name, constArgs)
            val binding = IndicatorBinding(call, ind, streamAlias, field, spec.inputKind)
            bindings.add(binding)
            return binding
        }

        fun updateAll(ctx: EvalContext) {
            for (b in bindings) b.update(ctx)
        }
    }
}
```

- [ ] **Step 4: Extend `ExprCompiler.kt`**

Change the `ExprCompiler` constructor to take a `IndicatorBinding.Bag`, and add an `IndicatorCall` branch:

```kotlin
class ExprCompiler(private val bindings: IndicatorBinding.Bag = IndicatorBinding.Bag()) {
    fun compile(expr: ExprAst): CompiledExpr =
        when (expr) {
            is NumLit -> CompiledExpr { Value.Num(expr.value) }
            is BoolLit -> CompiledExpr { Value.Bool(expr.value) }
            is BinaryOp -> compileBinary(expr)
            is UnaryOp -> compileUnary(expr)
            is CmpOp -> compileCmp(expr)
            is StreamFieldRef -> compileStreamField(expr)
            is IndicatorCall -> compileIndicator(expr)
            else -> error("ExprCompiler: unsupported expression: ${expr::class.simpleName}")
        }

    private fun compileIndicator(call: IndicatorCall): CompiledExpr {
        val binding = bindings.bind(call)
        return CompiledExpr {
            val v = binding.indicator.value()
            if (v == null || !binding.indicator.isReady) Value.Undefined else Value.Num(v)
        }
    }
    // ... existing private helpers unchanged ...
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerIndicatorTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIndicatorTest.kt
git commit -m "feat(dsl): bind and compile indicator calls"
```

---

### Task 13: Compile actions to signals

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt`

11b only supports `Buy`/`Sell` with `SizeQty` and `Market` (or implicit market). Other actions and shapes throw at compile time.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionCompilerTest {
    private val candle = Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle = candle, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())

    @Test
    fun `BUY emits Signal Buy with SizeQty`() {
        val action = Buy(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("2"))), orderType = Market))
        val sig = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sig).isEqualTo(Signal.Buy("BTCUSDT", BigDecimal("2")))
    }

    @Test
    fun `SELL emits Signal Sell`() {
        val action = Sell(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("3")))))
        val sig = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sig).isEqualTo(Signal.Sell("BTCUSDT", BigDecimal("3")))
    }

    @Test
    fun `Buy without sizing is rejected`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(Buy(stream = "btc", opts = ActionOpts()))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-quantity sizing is rejected in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(
                Buy(stream = "btc", opts = ActionOpts(sizing = com.qkt.dsl.ast.SizeRiskFrac(NumLit(BigDecimal("0.01"))))),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-market order type is rejected in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(
                Buy(
                    stream = "btc",
                    opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)), orderType = com.qkt.dsl.ast.Limit(NumLit(BigDecimal("100")))),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Close action is unsupported in 11b`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(Close("btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `ActionCompiler.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.strategy.Signal

class ActionCompiler(private val exprCompiler: ExprCompiler) {
    fun compile(action: ActionAst): (EvalContext) -> Signal =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Buy(sym, qty) }
            is Sell -> compileBuySell(action.stream, action.opts) { sym, qty -> Signal.Sell(sym, qty) }
            else -> error("Action ${action::class.simpleName} is not supported in 11b")
        }

    private fun compileBuySell(
        stream: String,
        opts: com.qkt.dsl.ast.ActionOpts,
        ctor: (String, java.math.BigDecimal) -> Signal,
    ): (EvalContext) -> Signal {
        val sizing = opts.sizing ?: error("BUY/SELL requires SIZING in 11b")
        require(sizing is SizeQty) { "Only direct quantity sizing is supported in 11b" }
        require(opts.orderType == null || opts.orderType == Market) {
            "Only MARKET order type is supported in 11b"
        }
        require(opts.tif == null) { "TIF is not supported in 11b" }
        require(opts.bracket == null) { "BRACKET is not supported in 11b" }
        require(opts.oco == null) { "OCO is not supported in 11b" }
        val qtyExpr = exprCompiler.compile(sizing.expr)
        return { ctx ->
            val symbol = ctx.streamSymbols[stream] ?: error("Unknown stream alias: $stream")
            val v = qtyExpr.evaluate(ctx)
            require(v is Value.Num) { "SIZING must be numeric, got $v" }
            ctor(symbol, v.v)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ActionCompilerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt
git commit -m "feat(dsl): compile actions to signals"
```

---

### Task 14: `CompiledRule` and rule firing

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt`

A `CompiledRule` pairs a compiled condition with a compiled action factory. When the rule fires (condition evaluates to `Value.Bool(true)`), it produces a `Signal`. If the condition evaluates to `Undefined`, the rule does not fire.

- [ ] **Step 1: Implement `CompiledRule.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.strategy.Signal

class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> Signal,
) {
    fun fire(ctx: EvalContext): Signal? {
        val v = condition.evaluate(ctx)
        if (v is Value.Bool && v.v) return action(ctx)
        return null
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt
git commit -m "feat(dsl): add compiled-rule firing"
```

---

### Task 15: `AstCompiler` end-to-end

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/AstCompilerTest.kt`

Walks `StrategyAst`, resolves `LET`s, builds rule-by-rule `CompiledRule`s, and returns a `Strategy` whose `onCandle` updates the indicator bag and fires each rule in order.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerTest {
    @Test
    fun `compiled strategy emits BUY when fast crosses above slow`() {
        val ast = StrategyAst(
            name = "ema_x",
            version = 1,
            streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
            constants = emptyList(),
            lets = listOf(
                LetDecl("fast", IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))),
                LetDecl("slow", IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("5"))))),
            ),
            defaults = null,
            rules = listOf(
                WhenThen(
                    cond = CmpOp(Cmp.GT, Ref("fast"), Ref("slow")),
                    action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)), orderType = Market)),
                ),
            ),
        )
        val strategy = AstCompiler().compile(ast)

        val captured = mutableListOf<Signal>()
        for (price in listOf("100", "101", "102", "103", "104", "110", "120")) {
            val c = Candle("BTCUSDT", BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ZERO, 0L, 60_000L)
            strategy.onCandle(c, dummyCtx(), captured::add)
        }
        assertThat(captured).isNotEmpty
        assertThat(captured.first()).isInstanceOf(Signal.Buy::class.java)
    }

    @Test
    fun `unrelated symbol on candle does not fire rule`() {
        val ast = simpleAlwaysBuyStrategy()
        val strategy = AstCompiler().compile(ast)
        val captured = mutableListOf<Signal>()
        val c = Candle("OTHER", BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal("100"), BigDecimal.ZERO, 0L, 60_000L)
        strategy.onCandle(c, dummyCtx(), captured::add)
        assertThat(captured).isEmpty()
    }

    private fun simpleAlwaysBuyStrategy(): StrategyAst =
        StrategyAst(
            name = "always_buy",
            version = 1,
            streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
            constants = emptyList(),
            lets = emptyList(),
            defaults = null,
            rules = listOf(
                WhenThen(
                    cond = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("0"))),
                    action = Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))),
                ),
            ),
        )

    private fun dummyCtx() = TestStrategyContext.crypto()
}
```

- [ ] **Step 2: Reuse the existing test helper**

`src/test/kotlin/com/qkt/strategy/TestStrategyContext.kt` already exposes a top-level `testStrategyContext(...)` function with sensible defaults (FixedClock, crypto calendar, no-op risk view, empty positions/pnl). Replace `dummyCtx()` in the test above with `testStrategyContext()` and add the import:

```kotlin
import com.qkt.strategy.testStrategyContext
```

Then change `private fun dummyCtx() = TestStrategyContext.crypto()` to `private fun dummyCtx() = testStrategyContext()`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerTest'`
Expected: FAIL.

- [ ] **Step 4: Implement `AstCompiler.kt` (and remove the test helper file)**

The `TestStrategyContext.kt` listed in the plan's File Structure is a relic — delete that file from the structure (it does not need to exist). The test imports the existing top-level helper instead.

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

class AstCompiler {
    fun compile(ast: StrategyAst): Strategy {
        val streamSymbols: Map<String, String> = ast.streams.associate { it.alias to it.symbol }
        val resolver = LetResolver(ast.lets)
        val bindings = IndicatorBinding.Bag()
        val exprCompiler = ExprCompiler(bindings)
        val actionCompiler = ActionCompiler(exprCompiler)
        val rules: List<CompiledRule> =
            ast.rules.map { rule ->
                require(rule is WhenThen) { "Only WHEN-THEN rules are supported in 11b" }
                val cond = exprCompiler.compile(resolver.resolve(rule.cond))
                val action = actionCompiler.compile(rule.action)
                CompiledRule(cond, action)
            }
        return CompiledStrategy(streamSymbols, bindings, rules)
    }
}

private class CompiledStrategy(
    private val streamSymbols: Map<String, String>,
    private val bindings: IndicatorBinding.Bag,
    private val rules: List<CompiledRule>,
) : Strategy {
    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // 11b is candle-driven; ticks are ignored.
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (candle.symbol !in streamSymbols.values) return
        val ec = EvalContext(candle = candle, streamSymbols = streamSymbols, lets = emptyMap())
        bindings.updateAll(ec)
        for (rule in rules) {
            val sig = rule.fire(ec)
            if (sig != null) emit(sig)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/AstCompilerTest.kt
git commit -m "feat(dsl): compile StrategyAst into runnable Strategy"
```

---

### Task 16: Kotlin DSL — strategy and stream builders

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt`
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/StrategyBuilder.kt`
- Create: `src/test/kotlin/com/qkt/dsl/kotlin/StrategyBuilderTest.kt`

The Kotlin DSL is a builder. The user types something like:

```kotlin
val ast = strategy("ema_x", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by ema(btc.close, period = 3)
    val slow by ema(btc.close, period = 5)
    rule {
        whenever(fast gt slow)
        then { buy(btc, qty = 1.toBigDecimal()) }
    }
}
```

The builder produces a `StrategyAst`. Stream handles (`StreamRef`) carry the alias the AST references. Property delegates (`val fast by ema(...)`) wire `LetDecl`s.

This task lays in only the top-level `strategy { ... }`, the `stream(...)` helper, and the `StreamRef` type. Subsequent tasks add the rest.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyBuilderTest {
    @Test
    fun `empty strategy header round-trips`() {
        val ast: StrategyAst =
            strategy("ema_x", version = 1) {
            }
        assertThat(ast.name).isEqualTo("ema_x")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.streams).isEmpty()
        assertThat(ast.rules).isEmpty()
    }

    @Test
    fun `stream registers a StreamDecl and returns a handle`() {
        val ast =
            strategy("s", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                assertThat(btc.alias).isEqualTo("btc")
            }
        assertThat(ast.streams).containsExactly(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StrategyBuilderTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `StreamRef.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.StreamFieldRef

data class StreamRef(val alias: String) {
    val close: ExprAst = StreamFieldRef(alias, "close")
    val open: ExprAst = StreamFieldRef(alias, "open")
    val high: ExprAst = StreamFieldRef(alias, "high")
    val low: ExprAst = StreamFieldRef(alias, "low")
    val volume: ExprAst = StreamFieldRef(alias, "volume")
    val price: ExprAst = StreamFieldRef(alias, "price")
    val candle: ExprAst = StreamFieldRef(alias, "candle")
}
```

- [ ] **Step 4: Implement `StrategyBuilder.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ConstantDecl
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StrategyAst

@DslMarker
annotation class QktDsl

@QktDsl
class StrategyBuilder(
    private val name: String,
    private val version: Int,
) {
    private val streams: MutableList<StreamDecl> = mutableListOf()
    private val constants: MutableList<ConstantDecl> = mutableListOf()
    internal val lets: MutableList<LetDecl> = mutableListOf()
    private val rules: MutableList<RuleAst> = mutableListOf()

    fun stream(
        alias: String,
        broker: String,
        symbol: String,
        every: String,
    ): StreamRef {
        streams.add(StreamDecl(alias = alias, broker = broker, symbol = symbol, timeframe = every))
        return StreamRef(alias = alias)
    }

    internal fun addRule(rule: RuleAst) {
        rules.add(rule)
    }

    internal fun build(): StrategyAst =
        StrategyAst(
            name = name,
            version = version,
            streams = streams.toList(),
            constants = constants.toList(),
            lets = lets.toList(),
            defaults = null,
            rules = rules.toList(),
        )
}

fun strategy(
    name: String,
    version: Int,
    block: StrategyBuilder.() -> Unit,
): StrategyAst {
    val b = StrategyBuilder(name = name, version = version)
    b.block()
    return b.build()
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StrategyBuilderTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/StreamRef.kt src/main/kotlin/com/qkt/dsl/kotlin/StrategyBuilder.kt src/test/kotlin/com/qkt/dsl/kotlin/StrategyBuilderTest.kt
git commit -m "feat(dsl): kotlin builder for strategy and streams"
```

---

### Task 17: Kotlin DSL — expression operators and indicator helpers

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/ExprBuilders.kt`
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Indicators.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/kotlin/StrategyBuilderTest.kt`

We give `ExprAst` arithmetic via top-level `+`/`-`/`*`/`/` operator overloads, and comparisons via infix functions (`gt`, `lt`, `gte`, `lte`, `eq`, `neq`). Booleans get `and`/`or`/`not`. Indicator helpers (`ema`, `rsi`, `atr`) build `IndicatorCall` AST nodes.

- [ ] **Step 1: Add failing tests**

Append to `StrategyBuilderTest.kt`:

```kotlin
    @Test
    fun `comparison operators build CmpOp nodes`() {
        val ast =
            strategy("s", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val rule = ruleAst {
                    whenever(btc.close gt 100.bd)
                    then { _ -> buy(btc, qty = 1.bd) }
                }
                addRule(rule)
            }
        assertThat(ast.rules).hasSize(1)
    }

    @Test
    fun `ema helper builds an IndicatorCall`() {
        val btc = StreamRef("btc")
        val expr = ema(btc.close, period = 9)
        assertThat(expr).isInstanceOf(com.qkt.dsl.ast.IndicatorCall::class.java)
        val ic = expr as com.qkt.dsl.ast.IndicatorCall
        assertThat(ic.name).isEqualTo("EMA")
        assertThat(ic.args).hasSize(2)
    }
```

(`ruleAst` and `then` are introduced in Task 18; the first test will compile once that task lands. Keep this assertion gated behind a `@org.junit.jupiter.api.Disabled` annotation if running it before Task 18, then re-enable after Task 18 lands.)

- [ ] **Step 2: Implement `ExprBuilders.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

val Int.bd: ExprAst get() = NumLit(BigDecimal(this))
val Long.bd: ExprAst get() = NumLit(BigDecimal(this))
val Double.bd: ExprAst get() = NumLit(BigDecimal(this.toString()))
val String.bd: ExprAst get() = NumLit(BigDecimal(this))
val BigDecimal.bd: ExprAst get() = NumLit(this)

operator fun ExprAst.plus(other: ExprAst): ExprAst = BinaryOp(BinOp.ADD, this, other)

operator fun ExprAst.minus(other: ExprAst): ExprAst = BinaryOp(BinOp.SUB, this, other)

operator fun ExprAst.times(other: ExprAst): ExprAst = BinaryOp(BinOp.MUL, this, other)

operator fun ExprAst.div(other: ExprAst): ExprAst = BinaryOp(BinOp.DIV, this, other)

operator fun ExprAst.unaryMinus(): ExprAst = UnaryOp(UnOp.NEG, this)

infix fun ExprAst.gt(other: ExprAst): ExprAst = CmpOp(Cmp.GT, this, other)

infix fun ExprAst.lt(other: ExprAst): ExprAst = CmpOp(Cmp.LT, this, other)

infix fun ExprAst.gte(other: ExprAst): ExprAst = CmpOp(Cmp.GE, this, other)

infix fun ExprAst.lte(other: ExprAst): ExprAst = CmpOp(Cmp.LE, this, other)

infix fun ExprAst.eq(other: ExprAst): ExprAst = CmpOp(Cmp.EQ, this, other)

infix fun ExprAst.neq(other: ExprAst): ExprAst = CmpOp(Cmp.NE, this, other)

infix fun ExprAst.and(other: ExprAst): ExprAst = BinaryOp(BinOp.AND, this, other)

infix fun ExprAst.or(other: ExprAst): ExprAst = BinaryOp(BinOp.OR, this, other)

fun not(arg: ExprAst): ExprAst = UnaryOp(UnOp.NOT, arg)
```

- [ ] **Step 3: Implement `Indicators.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal

fun ema(series: ExprAst, period: Int): ExprAst =
    IndicatorCall("EMA", listOf(series, NumLit(BigDecimal(period))))

fun rsi(series: ExprAst, period: Int): ExprAst =
    IndicatorCall("RSI", listOf(series, NumLit(BigDecimal(period))))

fun atr(stream: StreamRef, period: Int): ExprAst =
    IndicatorCall("ATR", listOf(StreamFieldRef(stream.alias, "candle"), NumLit(BigDecimal(period))))
```

- [ ] **Step 4: Run the EMA test**

```bash
./gradlew test --tests 'com.qkt.dsl.kotlin.StrategyBuilderTest.ema helper builds an IndicatorCall'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/ExprBuilders.kt src/main/kotlin/com/qkt/dsl/kotlin/Indicators.kt src/test/kotlin/com/qkt/dsl/kotlin/StrategyBuilderTest.kt
git commit -m "feat(dsl): kotlin operator and indicator builders"
```

---

### Task 18: Kotlin DSL — `LET`, `RULE`, and action builders

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/StrategyBuilder.kt`
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Actions.kt`

We add three pieces:

1. A property delegate `by` for `LET` declarations: `val fast by ema(btc.close, 9)` registers a `LetDecl("fast", ...)` and exposes a `Ref("fast")`.
2. A `rule { ... }` block that accumulates a `WhenThen`.
3. `buy(stream, qty)` / `sell(stream, qty)` action builders.

- [ ] **Step 1: Add failing tests**

Append to `StrategyBuilderTest.kt`:

```kotlin
    @Test
    fun `LET delegate registers a LetDecl and produces a Ref`() {
        val ast = strategy("s", version = 1) {
            val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
            val fast by letting(ema(btc.close, period = 9))
            assertThat(fast).isInstanceOf(com.qkt.dsl.ast.Ref::class.java)
        }
        assertThat(ast.lets).hasSize(1)
        assertThat(ast.lets[0].name).isEqualTo("fast")
    }

    @Test
    fun `rule block builds a WhenThen with Buy action`() {
        val ast = strategy("s", version = 1) {
            val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
            rule {
                whenever(btc.close gt 100.bd)
                then { buy(btc, qty = 1.bd) }
            }
        }
        assertThat(ast.rules).hasSize(1)
        val r = ast.rules[0] as com.qkt.dsl.ast.WhenThen
        assertThat(r.action).isInstanceOf(com.qkt.dsl.ast.Buy::class.java)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StrategyBuilderTest'`
Expected: FAIL.

- [ ] **Step 3: Extend `StrategyBuilder.kt`**

Add to the body:

```kotlin
    fun letting(expr: com.qkt.dsl.ast.ExprAst): LetBindingProvider = LetBindingProvider(this, expr)

    @QktDsl
    class RuleBuilder {
        private var cond: com.qkt.dsl.ast.ExprAst? = null
        private var action: com.qkt.dsl.ast.ActionAst? = null

        fun whenever(c: com.qkt.dsl.ast.ExprAst) {
            cond = c
        }

        fun then(block: ActionScope.() -> com.qkt.dsl.ast.ActionAst) {
            action = ActionScope.block()
        }

        internal fun build(): com.qkt.dsl.ast.WhenThen {
            val c = cond ?: error("rule { ... } missing whenever(...)")
            val a = action ?: error("rule { ... } missing then { ... }")
            return com.qkt.dsl.ast.WhenThen(c, a)
        }
    }

    fun rule(block: RuleBuilder.() -> Unit) {
        val rb = RuleBuilder()
        rb.block()
        addRule(rb.build())
    }

    internal fun ruleAst(block: RuleBuilder.() -> Unit): com.qkt.dsl.ast.WhenThen {
        val rb = RuleBuilder()
        rb.block()
        return rb.build()
    }
```

Add a small support class:

```kotlin
class LetBindingProvider(
    private val builder: StrategyBuilder,
    private val expr: com.qkt.dsl.ast.ExprAst,
) {
    operator fun provideDelegate(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
    ): kotlin.properties.ReadOnlyProperty<Any?, com.qkt.dsl.ast.ExprAst> {
        builder.lets.add(com.qkt.dsl.ast.LetDecl(property.name, expr))
        val ref = com.qkt.dsl.ast.Ref(property.name)
        return kotlin.properties.ReadOnlyProperty { _, _ -> ref }
    }
}
```

- [ ] **Step 4: Implement `Actions.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty

object ActionScope {
    fun buy(
        stream: StreamRef,
        qty: ExprAst,
    ): ActionAst = Buy(stream.alias, ActionOpts(sizing = SizeQty(qty), orderType = Market))

    fun sell(
        stream: StreamRef,
        qty: ExprAst,
    ): ActionAst = Sell(stream.alias, ActionOpts(sizing = SizeQty(qty), orderType = Market))
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.kotlin.StrategyBuilderTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/StrategyBuilder.kt src/main/kotlin/com/qkt/dsl/kotlin/Actions.kt src/test/kotlin/com/qkt/dsl/kotlin/StrategyBuilderTest.kt
git commit -m "feat(dsl): kotlin let-delegate rule and action builders"
```

---

### Task 19: End-to-end equivalence with `EmaCrossoverStrategy`

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/EmaCrossoverEquivalenceTest.kt`

Compile a Kotlin DSL EMA crossover and run it through the existing `Backtest` harness alongside the hand-written `EmaCrossoverStrategy`. The two `BacktestResult`s must match on emitted trades and final equity. This is the headline acceptance test for 11b.

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import com.qkt.strategy.EmaCrossoverStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmaCrossoverEquivalenceTest {
    private fun ticks(prices: List<String>): List<Tick> {
        return prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }
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
    fun `dsl EMA crossover equals handwritten over the same fixture`() {
        val ast = strategy("ema_x", version = 1) {
            val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
            val fast by letting(ema(btc.close, period = 3))
            val slow by letting(ema(btc.close, period = 7))
            rule {
                whenever(fast gt slow)
                then { buy(btc, qty = 1.bd) }
            }
            rule {
                whenever(slow gt fast)
                then { sell(btc, qty = 1.bd) }
            }
        }

        val dslStrategy = AstCompiler().compile(ast)
        val handStrategy = EmaCrossoverStrategy(symbol = "BTCUSDT", fastPeriod = 3, slowPeriod = 7)

        val dslResult = Backtest(
            strategies = listOf("ema_x" to dslStrategy),
            ticks = sample,
            candleWindow = TimeWindow.ONE_MINUTE,
        ).run()
        val handResult = Backtest(
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

- [ ] **Step 2: Run the test**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.EmaCrossoverEquivalenceTest'
```

If results diverge, investigate before adjusting the test. The likely cause is one strategy emitting on a different bar (off-by-one warmup). Stop and reassess after one failed attempt — the divergence pin-points a real bug.

`BacktestResult.trades` is `List<TradeRecord>`, where `TradeRecord` exposes the underlying `Trade` via `.trade` (the actual fields `symbol`/`side` live on `Trade`, not on `TradeRecord`).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/EmaCrossoverEquivalenceTest.kt
git commit -m "test(dsl): end-to-end equivalence with handwritten ema crossover"
```

---

### Task 20: Reject unsupported AST nodes with clear errors

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt`

Confirm every AST node we ship-but-don't-yet-compile produces a clear error. This locks the 11b boundary: 11c can swap each `error` into a real implementation with no changes elsewhere.

- [ ] **Step 1: Write the tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `BETWEEN is not supported in 11b`() {
        assertThatThrownBy { ec.compile(Between(NumLit(BigDecimal.ONE), NumLit(BigDecimal.ZERO), NumLit(BigDecimal("2")))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `IN-list is not supported in 11b`() {
        assertThatThrownBy { ec.compile(InList(NumLit(BigDecimal.ONE), listOf(NumLit(BigDecimal.ONE)))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `CROSSES is not supported in 11b`() {
        assertThatThrownBy { ec.compile(Crosses(CrossDir.ABOVE, NumLit(BigDecimal.ONE), NumLit(BigDecimal.ZERO))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `CASE WHEN is not supported in 11b`() {
        assertThatThrownBy { ec.compile(CaseWhen(emptyList(), NumLit(BigDecimal.ZERO))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `aggregates are not supported in 11b`() {
        assertThatThrownBy { ec.compile(Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen)) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `account refs are not supported in 11b`() {
        assertThatThrownBy { ec.compile(AccountRef("equity")) }.hasMessageContaining("unsupported")
    }

    @Test
    fun `position refs are not supported in 11b`() {
        assertThatThrownBy { ec.compile(PositionRef("btc")) }.hasMessageContaining("unsupported")
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.UnsupportedAstTest'`
Expected: PASS (all unsupported nodes already error via the existing `else` branch).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/UnsupportedAstTest.kt
git commit -m "test(dsl): assert unsupported AST nodes error in 11b"
```

---

### Task 21: Full-build green and ktlint pass

- [ ] **Step 1: Run the full build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. Every test passes.

- [ ] **Step 2: Run ktlint**

```bash
./gradlew ktlintFormat
```

If anything is reformatted, commit it as a separate `style` commit per qkt convention:

```bash
git status -s
git add <changed files>
git commit -m "style: ktlint format for new dsl sources"
```

- [ ] **Step 3: Re-run the build to confirm formatting did not break anything**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 22: Phase changelog

**Files:**
- Create: `docs/phases/phase-11b-dsl-foundation.md`

Per qkt skill §6, every phase ships a user-facing changelog. Cover: summary, what's new, usage cookbook (multiple worked Kotlin DSL examples, including indicator composition once 11c lands — for 11b show the supported subset), testing patterns, known limitations (everything from §10 of the master spec that 11b doesn't yet cover), and references.

- [ ] **Step 1: Write `docs/phases/phase-11b-dsl-foundation.md`**

The skeleton:

```markdown
# Phase 11b — DSL Foundation

## Summary

Phase 11b lands the minimum end-to-end DSL slice: an internal Kotlin DSL builds a `StrategyAst`, a compiler turns it into a `Strategy` runnable by the existing engine and backtest. Single-stream, single-timeframe, candle-driven, market-only, direct-quantity sizing. Comparisons, boolean composition, math, and `EMA`/`RSI`/`ATR` indicators. Snapshots, multi-symbol, multi-timeframe, advanced order types, sizing modes, defaults, and the SQL parser arrive in 11c–11f.

## What's new

- `com.qkt.dsl.ast` — full AST shape (literals, refs, operators, indicator calls, rules, actions, full order/sizing/TIF surface declared even where not yet compiled).
- `com.qkt.dsl.stdlib.Constants` — `ONE_PERCENT` family + `BPS`.
- `com.qkt.dsl.stdlib.IndicatorRegistry` — `EMA`, `RSI`, `ATR`.
- `com.qkt.dsl.compile.AstCompiler` — `StrategyAst` → `Strategy`.
- `com.qkt.dsl.kotlin` — internal Kotlin DSL: `strategy { ... }`, `stream(...)`, `letting(...)`, `rule { ... }`, expression operators, `ema`/`rsi`/`atr` helpers, `buy`/`sell` actions.

## Migration from previous phase

None. Phase 11b is purely additive.

## Usage cookbook

### Minimal EMA crossover

\`\`\`kotlin
val ast = strategy("ema_x", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever(fast gt slow)
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever(slow gt fast)
        then { sell(btc, qty = 1.bd) }
    }
}
val strategy = AstCompiler().compile(ast)
\`\`\`

### Math in conditions

\`\`\`kotlin
rule {
    whenever((btc.high - btc.low) gt 5.bd)
    then { buy(btc, qty = 1.bd) }
}
\`\`\`

### Composite condition

\`\`\`kotlin
rule {
    whenever((fast gt slow) and (btc.volume gt 1000.bd))
    then { buy(btc, qty = 1.bd) }
}
\`\`\`

## Testing patterns

The compiled strategy is a normal `Strategy` — exercise it via `Backtest` with a deterministic tick fixture. Compare against a hand-written equivalent and assert identical `BacktestResult`s. (See `src/test/kotlin/com/qkt/dsl/compile/EmaCrossoverEquivalenceTest.kt`.)

## Known limitations

- Single stream, single timeframe, single broker per strategy — multi-stream rules fail at AST → Strategy time. (Phase 11e.)
- No snapshots (`@buy`, `@sell`, `@open`, `@T-N`). (Phase 11c.)
- No `CROSSES`, `BETWEEN`, `IN`, `CASE WHEN`, running aggregates. (Phase 11c.)
- No `ACCOUNT.*`, `POSITION.*` accessors. (Phase 11c.)
- No `LIMIT`, `STOP`, `BRACKET`, `OCO`, `TRAILING`, TIF. Only market orders. (Phase 11d.)
- No `RISK`, `% OF EQUITY`, `USD` notional sizing. Only direct quantity. (Phase 11d.)
- No `DEFAULTS` block at runtime. (Phase 11d.)
- No external `.qkt` parser. Kotlin DSL only. (Phase 11f.)
- No CLI runner. (Phase 12 — designed but out of scope for 11.)

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11b.md`
- Merge commit: TBD (fill in after merge to `main`).
```

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-11b-dsl-foundation.md
git commit -m "docs: phase 11b dsl foundation changelog"
```

---

### Task 23: Final pre-push checklist

Per qkt skill §4, run the pre-push gate.

- [ ] **Step 1: Full build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Status clean**

```bash
git status
```

Expected: clean working tree, branch ahead of `main` by Task 1–22 commits.

- [ ] **Step 3: Read every commit message**

```bash
git log --oneline main..HEAD
```

Every commit must follow `<type>(<scope>): <subject>` per qkt skill §3. No emoji. No AI footer. No body.

- [ ] **Step 4: Search for stragglers**

```bash
grep -rEn 'TODO|FIXME|XXX' src/main/kotlin/com/qkt/dsl/ src/test/kotlin/com/qkt/dsl/ || true
```

If any are present that don't link to a backlog issue, address them before pushing.

- [ ] **Step 5: Hand off**

After this task, follow `superpowers:finishing-a-development-branch` to merge or open a PR. Per qkt convention the merge into `main` uses `--no-ff` with the message `merge: phase 11b dsl foundation`. After merge, fill in the merge SHA in `docs/phases/phase-11b-dsl-foundation.md` (References section) and amend with a `docs:` commit on `main`.
