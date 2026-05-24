# Phase 37 — Proportional STACK_AT sizing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `STACK_AT SIZING` to reference the parent leg's filled quantity via a new keyword `ENTRY_QTY`, defer the sizing evaluation from compile time to parent-fill time, and keep MFE/bracket fields compile-time constant.

**Architecture:** A new `data object EntryQty : ExprAst` is parsed as `TokenKind.ENTRY_QTY` (auto-keyword via `Lexer.KEYWORDS` once added to the enum). `StackAtCompiler` produces a `(BigDecimal) -> BigDecimal` lambda per tier instead of a constant. A new `ResolvedStackTier` mirrors today's `CompiledStackTier` shape with the lambda already applied. `StackOrchestrator.onPrimaryFilled` gains a `parentQty: BigDecimal` argument, resolves each tier once, and hands a `List<ResolvedStackTier>` to `StackEngine` whose per-tick body is unchanged in behavior.

**Tech Stack:** Kotlin 2.x, JUnit 5, AssertJ.

**Spec:** [`docs/superpowers/specs/2026-05-24-phase37-proportional-stack-sizing-design.md`](../specs/2026-05-24-phase37-proportional-stack-sizing-design.md)

**Issue:** <https://github.com/elitekaycy/qkt/issues/49>

---

## File map

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` | modify | Add `data object EntryQty : ExprAst`. |
| `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` | modify | Add `ENTRY_QTY,` to the enum so `Lexer.KEYWORDS` picks it up. |
| `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` | modify | Route `TokenKind.ENTRY_QTY` to `EntryQty` in the primary-expression switch. |
| `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` | modify | Reject `EntryQty` outside STACK_AT-sizing context with a pointed message. |
| `src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt` | modify | Replace constant `compileSizing` with lambda-producing `compileSizingExpr`. |
| `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt` | modify | Split `CompiledStackTier` (lambda) from new `ResolvedStackTier` (BigDecimal); engine consumes resolved. |
| `src/main/kotlin/com/qkt/dsl/compile/StackOrchestrator.kt` | modify | `onPrimaryFilled` gains `parentQty: BigDecimal`; resolves tiers before constructing engine. |
| `src/main/kotlin/com/qkt/app/OrderManager.kt` (and/or `TradingPipeline.kt`) | modify | Pass parent fill qty when calling `onPrimaryFilled`. |
| `src/test/kotlin/com/qkt/dsl/compile/StackAtCompilerEntryQtyTest.kt` | create | Lambda-output tests for ENTRY_QTY + arithmetic + literal regression + rejections. |
| `src/test/kotlin/com/qkt/dsl/compile/StackOrchestratorEntryQtyTest.kt` | create | Integration: `ENTRY_QTY * 0.5` with parentQty=0.40 → stack signal with qty=0.20. |
| `src/test/kotlin/com/qkt/dsl/parse/ParserStackAtTest.kt` | extend | One assertion: `SIZING ENTRY_QTY * 0.3` parses to the right AST. |
| `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerEntryQtyTest.kt` | create | `EntryQty` outside STACK_AT SIZING fails compile with the pointed message. |
| `docs/phases/phase-37-proportional-stack-sizing.md` | create | Phase changelog. |

---

### Task 1: Add `EntryQty` AST + token

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Extend: `src/test/kotlin/com/qkt/dsl/parse/ParserStackAtTest.kt`

- [ ] **Step 1: Write the failing parser test**

Append to `src/test/kotlin/com/qkt/dsl/parse/ParserStackAtTest.kt`:

```kotlin
@Test
fun `STACK_AT SIZING accepts ENTRY_QTY arithmetic`() {
    val src =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            gold = EXNESS:XAUUSD EVERY 1m
        RULES
            WHEN gold.close > 0 THEN BUY gold SIZING 0.3
                STACK_AT MFE >= 5 WITHIN 30m
                    SIZING ENTRY_QTY * 0.3
                    BRACKET { STOP LOSS BY 2.0; TAKE PROFIT BY 4.0 }
        """.trimIndent()
    val parsed = Parser(Lexer(src).tokenize()).parseFile()
    require(parsed is ParseResult.Success) { "parse failed: $parsed" }
    val ast = (parsed.value as ParsedFile.StrategyFile).ast
    val rule = ast.rules.first() as com.qkt.dsl.ast.WhenThen
    val buy = rule.action as com.qkt.dsl.ast.Buy
    val clause = buy.opts.stackAts.first()
    val sizingExpr = (clause.sizing as com.qkt.dsl.ast.SizeQty).expr
    assertThat(sizingExpr).isEqualTo(
        com.qkt.dsl.ast.BinaryOp(
            com.qkt.dsl.ast.BinOp.MUL,
            com.qkt.dsl.ast.EntryQty,
            com.qkt.dsl.ast.NumLit(BigDecimal("0.3")),
        ),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserStackAtTest.STACK_AT SIZING accepts ENTRY_QTY arithmetic'`
Expected: FAIL with `Unresolved reference: EntryQty` (the AST node doesn't exist yet).

- [ ] **Step 3: Add the AST node**

Append at the bottom of `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` (after `data object StackEntryRef`):

```kotlin
/**
 * Phase 37: references the parent leg's filled quantity inside a `STACK_AT SIZING`
 * expression. Rejected by [com.qkt.dsl.compile.ExprCompiler] outside that context.
 */
data object EntryQty : ExprAst
```

- [ ] **Step 4: Add the TokenKind**

Modify `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `ENTRY_QTY,` near the other STACK_AT-area keywords (around line 99 where `STACK_AT, MFE,` live):

```kotlin
STACK_AT,
MFE,
ENTRY_QTY,
```

The `Lexer.KEYWORDS` map auto-discovers it via `TokenKind.values().filter { ... }` — no lexer change required.

- [ ] **Step 5: Wire ENTRY_QTY in the primary-expression parser**

In `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`, locate the primary-expression switch (search for `data object StackEntryRef` callsite, around the IDENT-handling block). Add a case alongside other leaf tokens:

```kotlin
TokenKind.ENTRY_QTY -> { advance(); EntryQty }
```

Place it in the same `when` that handles `NUMBER`, `STRING`, `IDENT` — the file is large, locate by searching for an existing primary-expression-leaf case like `TokenKind.NUMBER ->` in the body of the expression parser.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserStackAtTest.STACK_AT SIZING accepts ENTRY_QTY arithmetic'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt \
        src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt \
        src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/test/kotlin/com/qkt/dsl/parse/ParserStackAtTest.kt
git commit -m "feat(dsl): parse ENTRY_QTY as an ExprAst leaf"
```

---

### Task 2: Reject `EntryQty` outside STACK_AT SIZING

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerEntryQtyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.EntryQty
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerEntryQtyTest {
    @Test
    fun `EntryQty outside STACK_AT SIZING fails compile with a pointed message`() {
        assertThatThrownBy { ExprCompiler().compile(EntryQty) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ENTRY_QTY")
            .hasMessageContaining("STACK_AT SIZING")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerEntryQtyTest'`
Expected: FAIL — `ExprCompiler.compile(EntryQty)` currently falls through with an unknown-variant error.

- [ ] **Step 3: Add the rejection branch**

In `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`, locate the top-level `compile(expr, ruleAlias)` `when`. Add a branch alongside the other leaf cases (Ref, NowAccessor, AccountRef, PositionRef, StateAccessor, StackEntryRef):

```kotlin
is com.qkt.dsl.ast.EntryQty ->
    error("ENTRY_QTY is only valid inside STACK_AT SIZING; got it in a non-STACK_AT expression")
```

Imports: add `import com.qkt.dsl.ast.EntryQty` to the imports block.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerEntryQtyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ExprCompilerEntryQtyTest.kt
git commit -m "feat(dsl): reject EntryQty in non-STACK_AT expressions"
```

---

### Task 3: Lambda-producing `compileSizing` in `StackAtCompiler`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/StackAtCompilerEntryQtyTest.kt`

This task replaces the constant-only sizing path with a lambda. `StackEngine` still consumes a resolved `BigDecimal`; the lambda lives only between compile and parent-fill time. To keep the two shapes type-distinct, introduce `ResolvedStackTier` alongside the modified `CompiledStackTier`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.Ref
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackAtCompilerEntryQtyTest {
    private val baseBracket =
        BracketAst(
            stopLoss = ChildBy(NumLit(BigDecimal("2.0"))),
            takeProfit = ChildBy(NumLit(BigDecimal("4.0"))),
        )

    private fun clause(sizingExpr: com.qkt.dsl.ast.ExprAst): StackAtClause =
        StackAtClause(
            mfeThreshold = NumLit(BigDecimal("5")),
            withinDuration = DurationAst(millis = 30 * 60_000L),
            sizing = SizeQty(sizingExpr),
            bracket = baseBracket,
        )

    @Test
    fun `ENTRY_QTY alone resolves to parentQty`() {
        val tier = StackAtCompiler.compile(clause(EntryQty))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.40")))
            .isEqualByComparingTo(BigDecimal("0.40"))
    }

    @Test
    fun `scaled fraction of ENTRY_QTY`() {
        val expr = BinaryOp(BinOp.MUL, NumLit(BigDecimal("0.3")), EntryQty)
        val tier = StackAtCompiler.compile(clause(expr))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.40")))
            .isEqualByComparingTo(BigDecimal("0.12"))
    }

    @Test
    fun `affine combination of ENTRY_QTY`() {
        val expr =
            BinaryOp(
                BinOp.ADD,
                BinaryOp(BinOp.MUL, NumLit(BigDecimal("0.5")), EntryQty),
                NumLit(BigDecimal("0.05")),
            )
        val tier = StackAtCompiler.compile(clause(expr))
        assertThat(tier.resolveStackQuantity(BigDecimal("0.30")))
            .isEqualByComparingTo(BigDecimal("0.20"))
    }

    @Test
    fun `division rounds via Money SCALE`() {
        val expr = BinaryOp(BinOp.DIV, EntryQty, NumLit(BigDecimal("3")))
        val tier = StackAtCompiler.compile(clause(expr))
        val resolved = tier.resolveStackQuantity(BigDecimal("1"))
        // Money.SCALE truncation: 1 / 3 → exactly Money.SCALE decimal places.
        assertThat(resolved.scale()).isEqualTo(Money.SCALE)
        assertThat(resolved).isLessThan(BigDecimal("0.34"))
        assertThat(resolved).isGreaterThan(BigDecimal("0.33"))
    }

    @Test
    fun `pure literal still works as a regression`() {
        val tier = StackAtCompiler.compile(clause(NumLit(BigDecimal("0.10"))))
        assertThat(tier.resolveStackQuantity(BigDecimal("999")))
            .isEqualByComparingTo(BigDecimal("0.10"))
    }

    @Test
    fun `ENTRY_QTY in mfeThreshold fails compile`() {
        val c =
            StackAtClause(
                mfeThreshold = EntryQty,
                withinDuration = DurationAst(millis = 30 * 60_000L),
                sizing = SizeQty(NumLit(BigDecimal("0.10"))),
                bracket = baseBracket,
            )
        assertThatThrownBy { StackAtCompiler.compile(c) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT MFE threshold")
    }

    @Test
    fun `Ref or StreamFieldRef in sizing fails compile with the unified message`() {
        val ref = clause(Ref("foo"))
        assertThatThrownBy { StackAtCompiler.compile(ref) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only")

        val stream = clause(StreamFieldRef("gold", "close"))
        assertThatThrownBy { StackAtCompiler.compile(stream) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only")
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.StackAtCompilerEntryQtyTest'`
Expected: every test FAILS (most with `Unresolved reference: resolveStackQuantity`).

- [ ] **Step 3: Update `CompiledStackTier` to carry a lambda + add `ResolvedStackTier`**

In `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt`, replace the existing `CompiledStackTier` and add the resolved form:

```kotlin
/**
 * Phase 27 + Phase 37: one compiled `STACK_AT` tier. `mfeThreshold`, `slDistance`,
 * `tpDistance` are evaluated at compile time. Sizing is deferred to parent-fill time —
 * [resolveStackQuantity] takes the parent leg's filled quantity and returns the absolute
 * lot size for this tier. For literal-only sizing (no `ENTRY_QTY`) the lambda ignores
 * its argument and returns the constant.
 */
data class CompiledStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val resolveStackQuantity: (BigDecimal) -> BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)

/**
 * Phase 37: a [CompiledStackTier] with [resolveStackQuantity] already applied. Held by
 * [StackEngine] from parent-fill time onward — the per-tick path reads a plain
 * `BigDecimal` and never re-evaluates the sizing expression.
 */
internal data class ResolvedStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)
```

In the same file, change every occurrence of `tier.stackQuantity` (inside `StackEngine`) to read from `ResolvedStackTier.stackQuantity` — the engine's `tiers` field becomes `private val tiers: List<ResolvedStackTier>`. The `buildStackSignal` body is unchanged in logic; only the type name on `tiers` differs.

- [ ] **Step 4: Rewrite `StackAtCompiler.compileSizing`**

Replace `compileSizing` + its caller in `compile(clause)` with the lambda-producing pair from the spec. Full updated file:

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import java.math.BigDecimal

object StackAtCompiler {
    fun compileAll(clauses: List<StackAtClause>): List<CompiledStackTier> = clauses.map(::compile)

    fun compile(clause: StackAtClause): CompiledStackTier {
        val threshold = evalConstant(clause.mfeThreshold, context = "STACK_AT MFE threshold")
        val withinMs = clause.withinDuration.millis
        val resolve = compileSizing(clause.sizing)
        val (sl, tp) = compileBracket(clause.bracket)
        return CompiledStackTier(
            mfeThreshold = threshold,
            withinMs = withinMs,
            resolveStackQuantity = resolve,
            slDistance = sl,
            tpDistance = tp,
        )
    }

    private fun compileSizing(sizing: SizingAst): (BigDecimal) -> BigDecimal =
        when (sizing) {
            is SizeQty -> compileSizingExpr(sizing.expr)
            else ->
                error("STACK_AT only supports SIZING <qty-expr> (lots); got ${sizing::class.simpleName}")
        }

    private fun compileSizingExpr(expr: ExprAst): (BigDecimal) -> BigDecimal =
        when (expr) {
            is NumLit -> { _ -> expr.value }
            EntryQty -> { parentQty -> parentQty }
            is UnaryOp ->
                when (expr.op) {
                    UnOp.NEG -> {
                        val inner = compileSizingExpr(expr.arg);
                        { p -> inner(p).negate() }
                    }
                    UnOp.NOT ->
                        error("STACK_AT SIZING: boolean NOT is not a numeric expression")
                }
            is BinaryOp -> compileSizingBinary(expr)
            else ->
                error(
                    "STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only; " +
                        "got ${expr::class.simpleName}",
                )
        }

    private fun compileSizingBinary(expr: BinaryOp): (BigDecimal) -> BigDecimal {
        val l = compileSizingExpr(expr.lhs)
        val r = compileSizingExpr(expr.rhs)
        return when (expr.op) {
            BinOp.ADD -> { p -> l(p).add(r(p)) }
            BinOp.SUB -> { p -> l(p).subtract(r(p)) }
            BinOp.MUL -> { p -> l(p).multiply(r(p)) }
            BinOp.DIV -> { p -> l(p).divide(r(p), Money.SCALE, Money.ROUNDING) }
            BinOp.AND, BinOp.OR ->
                error(
                    "STACK_AT SIZING: boolean operator ${expr.op} is not a numeric expression",
                )
        }
    }

    private fun compileBracket(bracket: BracketAst): Pair<BigDecimal, BigDecimal> {
        val sl = bracket.stopLoss ?: error("STACK_AT BRACKET requires STOP LOSS")
        val tp = bracket.takeProfit ?: error("STACK_AT BRACKET requires TAKE PROFIT")
        return bracketDistance(sl, "STOP LOSS") to bracketDistance(tp, "TAKE PROFIT")
    }

    private fun bracketDistance(
        child: ChildPriceAst,
        leg: String,
    ): BigDecimal =
        when (child) {
            is ChildBy -> evalConstant(child.distance, context = "STACK_AT BRACKET $leg BY")
            else ->
                error(
                    "STACK_AT BRACKET $leg must use BY <distance>; got ${child::class.simpleName}",
                )
        }

    private fun evalConstant(
        expr: ExprAst,
        context: String,
    ): BigDecimal =
        when (expr) {
            is NumLit -> expr.value
            is UnaryOp ->
                when (expr.op) {
                    UnOp.NEG -> evalConstant(expr.arg, context).negate()
                    UnOp.NOT ->
                        error("$context: boolean NOT is not a numeric expression")
                }
            is BinaryOp -> evalBinary(expr, context)
            else ->
                error(
                    "$context must be a compile-time constant; got ${expr::class.simpleName}",
                )
        }

    private fun evalBinary(
        expr: BinaryOp,
        context: String,
    ): BigDecimal {
        val l = evalConstant(expr.lhs, context)
        val r = evalConstant(expr.rhs, context)
        return when (expr.op) {
            BinOp.ADD -> l.add(r)
            BinOp.SUB -> l.subtract(r)
            BinOp.MUL -> l.multiply(r)
            BinOp.DIV -> l.divide(r, Money.SCALE, Money.ROUNDING)
            BinOp.AND, BinOp.OR ->
                error("$context: boolean operator ${expr.op} is not a numeric expression")
        }
    }
}
```

- [ ] **Step 5: Run the new tests**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.StackAtCompilerEntryQtyTest'`
Expected: 7 PASSED.

- [ ] **Step 6: Regression — existing StackEngine tests still pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.StackEngineTest' --tests 'com.qkt.dsl.compile.StackAtCompiler*'`
Expected: all existing tests still PASSED. (The engine's per-tick path is unchanged in behavior; only the tier-type rename matters at compile time.)

If the existing `StackEngineTest` constructs `CompiledStackTier` directly, those construction sites need their `stackQuantity = X` argument renamed to `resolveStackQuantity = { _ -> X }` — do the renames and re-run.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt \
        src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt \
        src/test/kotlin/com/qkt/dsl/compile/StackAtCompilerEntryQtyTest.kt \
        src/test/kotlin/com/qkt/dsl/compile/StackEngineTest.kt
git commit -m "feat(dsl): defer STACK_AT sizing to parent-fill time via ENTRY_QTY lambda"
```

(Drop `StackEngineTest.kt` from the add list if no construction-site renames were needed.)

---

### Task 4: Resolve tiers at parent-fill time in `StackOrchestrator`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackOrchestrator.kt`
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (and any other caller of `onPrimaryFilled`)
- Create: `src/test/kotlin/com/qkt/dsl/compile/StackOrchestratorEntryQtyTest.kt`

- [ ] **Step 1: Write the failing integration test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackOrchestratorEntryQtyTest {
    @Test
    fun `proportional STACK_AT fires with qty equal to parentQty times scaling factor`() {
        val emitted = mutableListOf<Signal>()
        val orchestrator =
            StackOrchestrator(
                clock = FixedClock(0L),
                strategyId = "test",
                emit = { sig -> emitted.add(sig) },
            )
        val tiers =
            listOf(
                CompiledStackTier(
                    mfeThreshold = BigDecimal("1.0"),
                    withinMs = 60_000L,
                    resolveStackQuantity = { p -> p.multiply(BigDecimal("0.5")) },
                    slDistance = BigDecimal("2.0"),
                    tpDistance = BigDecimal("4.0"),
                ),
            )
        orchestrator.onPrimaryFilled(
            parentLegId = "parent-1",
            parentSymbol = "EXNESS:XAUUSD",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4500"),
            parentQty = BigDecimal("0.40"),
            tiers = tiers,
        )

        // Tick crosses MFE >= 1.0 → engine should fire one bracket signal.
        orchestrator.onTick("EXNESS:XAUUSD", BigDecimal("4501.5"))

        val submitted = emitted.filterIsInstance<Signal.Submit>().map { it.request }
        val bracket = submitted.filterIsInstance<OrderRequest.Bracket>().single()
        assertThat(bracket.quantity).isEqualByComparingTo(BigDecimal("0.20"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.StackOrchestratorEntryQtyTest'`
Expected: FAIL — `onPrimaryFilled` does not take `parentQty` yet.

- [ ] **Step 3: Add `parentQty` to `onPrimaryFilled`**

In `src/main/kotlin/com/qkt/dsl/compile/StackOrchestrator.kt`, modify `onPrimaryFilled`:

```kotlin
fun onPrimaryFilled(
    parentLegId: String,
    parentSymbol: String,
    parentSide: Side,
    parentEntryPrice: BigDecimal,
    parentQty: BigDecimal,
    tiers: List<CompiledStackTier>,
    closeWatchIds: Set<String> = emptySet(),
) {
    if (tiers.isEmpty()) return
    check(parentLegId !in engines) { "StackEngine already registered for $parentLegId" }
    val engineEmit: (Signal) -> Unit = { sig ->
        if (sig is Signal.Submit) {
            val req = sig.request
            if (req is OrderRequest.Bracket) onStackBracketEmit(req, parentLegId)
        }
        emit(sig)
    }
    val persistedTiers =
        runCatching { persistor.loadPendingStacks(strategyId)[parentLegId] }.getOrNull()
    val initialFired: Set<Int> =
        persistedTiers
            ?.tiers
            ?.filter { it.fired }
            ?.map { it.index }
            ?.toSet() ?: emptySet()
    val initialFiredLegIds: Map<Int, String> =
        persistedTiers
            ?.tiers
            ?.mapNotNull { t -> t.firedLegId?.let { t.index to it } }
            ?.toMap()
            ?: emptyMap()
    val resolved =
        tiers.map { c ->
            ResolvedStackTier(
                mfeThreshold = c.mfeThreshold,
                withinMs = c.withinMs,
                stackQuantity = c.resolveStackQuantity(parentQty),
                slDistance = c.slDistance,
                tpDistance = c.tpDistance,
            )
        }
    engines[parentLegId] =
        StackEngine(
            parentLegId = parentLegId,
            parentSymbol = parentSymbol,
            closeWatchIds = closeWatchIds,
            parentSide = parentSide,
            parentEntryPrice = parentEntryPrice,
            tiers = resolved,
            clock = clock,
            emit = engineEmit,
            strategyId = strategyId,
            persistor = persistor,
            initialFiredTierIndices = initialFired,
            initialFiredLegIds = initialFiredLegIds,
        )
}
```

`StackEngine`'s `tiers` parameter type changed to `List<ResolvedStackTier>` in Task 3 — this call site matches.

- [ ] **Step 4: Thread `parentQty` from callers**

Find every caller of `stackOrchestrator.onPrimaryFilled(...)` (typically inside `com.qkt.app.OrderManager` or `TradingPipeline`):

```bash
grep -rn 'onPrimaryFilled' src/main/kotlin | grep -v 'fun onPrimaryFilled'
```

At each site, the parent fill quantity is already in scope as `BrokerEvent.OrderFilled.quantity` (or the equivalent field on the local fill record). Pass it through as the new `parentQty` argument. Example:

```kotlin
stackOrchestrator.onPrimaryFilled(
    parentLegId = filled.clientOrderId,
    parentSymbol = filled.symbol,
    parentSide = filled.side,
    parentEntryPrice = filled.price,
    parentQty = filled.quantity,
    tiers = tiers,
    closeWatchIds = closeWatchIds,
)
```

- [ ] **Step 5: Run the new test**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.StackOrchestratorEntryQtyTest'`
Expected: PASS.

- [ ] **Step 6: Regression — every test under app + dsl**

Run: `./gradlew test --tests 'com.qkt.app.*' --tests 'com.qkt.dsl.*'`
Expected: BUILD SUCCESSFUL.

If any existing test constructs `onPrimaryFilled(...)` directly, it needs a `parentQty = …` argument added. Use a sensible default like `BigDecimal("1")` for tests that don't care about proportional behavior.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/StackOrchestrator.kt \
        src/main/kotlin/com/qkt/app/OrderManager.kt \
        src/test/kotlin/com/qkt/dsl/compile/StackOrchestratorEntryQtyTest.kt
git commit -m "feat(app): thread parent fill qty into STACK_AT tier resolution"
```

(Add any other modified test/source files to the staging list.)

---

### Task 5: Phase changelog

**Files:**
- Create: `docs/phases/phase-37-proportional-stack-sizing.md`

- [ ] **Step 1: Write the changelog**

Create the file. Follow the qkt-skill template; reference `docs/phases/phase-32-bid-ask.md` and `docs/phases/phase-39-instrument-meta.md` as the canonical format. The changelog must contain:

1. **Summary** (2–4 sentences): Phase 37 relaxes the Phase 27 compile-time-constant restriction on `STACK_AT SIZING` so the expression can reference the parent leg's filled quantity via the new `ENTRY_QTY` keyword. MFE and bracket distances stay constant; the relaxation is sizing-only. The lambda is built at compile and resolved once at parent-fill time, so the per-tick stack-engine path stays free of expression evaluation.
2. **What's new**:
   - `ENTRY_QTY` keyword + `EntryQty` AST leaf — references the parent leg's filled qty inside `STACK_AT SIZING`.
   - `STACK_AT SIZING` accepts `NumLit | EntryQty | arithmetic` (ADD/SUB/MUL/DIV/NEG).
   - `CompiledStackTier.resolveStackQuantity: (BigDecimal) -> BigDecimal` — replaces the prior `stackQuantity: BigDecimal` field.
   - `ResolvedStackTier` — internal post-fill snapshot, consumed by `StackEngine`.
   - `StackOrchestrator.onPrimaryFilled` gains `parentQty: BigDecimal`.
3. **Migration from previous phase**:
   - In-tree callers of `CompiledStackTier(..., stackQuantity = X, ...)` change to `CompiledStackTier(..., resolveStackQuantity = { _ -> X }, ...)`. No persisted-state migration — Phase 29's persistor stores already-resolved `BigDecimal` values.
4. **Usage cookbook**: at least three worked examples:
   - Proportional: `SIZING 0.3 * ENTRY_QTY`
   - Affine: `SIZING 0.5 * ENTRY_QTY + 0.05`
   - Pure literal (regression-only): `SIZING 0.10`
5. **Testing patterns**: point at `StackAtCompilerEntryQtyTest` for the compile-side cases and `StackOrchestratorEntryQtyTest` for the end-to-end fire-with-resolved-qty case.
6. **Known limitations**:
   - `ENTRY_QTY` is rejected in `MFE >=` thresholds and bracket SL/TP distances. Sizing only.
   - Non-`SizeQty` variants (`RISK $`, `% OF EQUITY`, etc.) still rejected by `STACK_AT`.
   - Indicators / stream fields / `NOW` still rejected inside `STACK_AT SIZING`.
7. **References**: spec, plan, merge commit (filled at merge).

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-37-proportional-stack-sizing.md
git commit -m "docs(phases): phase 37 proportional STACK_AT sizing changelog"
```

---

### Task 6: Regression sweep + push + PR + merge

- [ ] **Step 1: Full test sweep**

Run: `./gradlew test --tests 'com.qkt.dsl.*' --tests 'com.qkt.app.*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. If any new files trip ktlint, run `./gradlew ktlintFormat` and re-stage.

- [ ] **Step 3: Push**

The branch is `phase37-proportional-stack-sizing` (already created during brainstorming).

```bash
git push -u origin phase37-proportional-stack-sizing
```

- [ ] **Step 4: Open the PR**

Title: `[phase 37] feat(dsl): proportional STACK_AT sizing via ENTRY_QTY`

PR body must follow the qkt skill PR template — link both spec (`docs/superpowers/specs/2026-05-24-phase37-proportional-stack-sizing-design.md`) and plan (`docs/superpowers/plans/2026-05-24-phase37-proportional-stack-sizing.md`), enumerate the changes, list test files, mark risk as Medium-low.

- [ ] **Step 5: Watch CI**

```bash
gh pr checks <PR#> --watch
```

- [ ] **Step 6: Merge when green**

```bash
gh pr merge <PR#> --merge --subject "merge: phase 37 proportional STACK_AT sizing" --delete-branch
```

---

## Self-review notes

- **Spec coverage:** Every spec section maps to a task. ENTRY_QTY parsing → Task 1; outside-context rejection → Task 2; lambda compile → Task 3; parent-fill resolution + orchestrator wiring → Task 4; docs → Task 5; merge → Task 6.
- **Type consistency:** `resolveStackQuantity: (BigDecimal) -> BigDecimal` and `ResolvedStackTier.stackQuantity: BigDecimal` are referenced consistently across Task 3 and Task 4. `parentQty: BigDecimal` is the same name everywhere.
- **Placeholder check:** Task 4 Step 4 names a search rather than enumerating exact line numbers — acceptable because the grep is a one-line command that produces the exact list, and the engineer can act on its output mechanically. Every step that introduces *new* code shows the code.
- **Known weakness:** if any out-of-tree caller invokes `StackOrchestrator.onPrimaryFilled` (we don't have any today), it would break at compile. Mitigation: the param is added at the end of the parameter list and is required, so the build fails loudly rather than silently mis-routing.
