# Phase 36 — Armed Trailing Stop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add DSL syntax `STOP LOSS TRAIL <distance> AFTER MFE >= <threshold>` for a bracket-leg stop that sits at entry until MFE crosses a threshold, then converts to a trailing stop at the given distance from the running favorable extreme.

**Architecture:** Picked Option A from the design spec (`docs/superpowers/specs/2026-05-25-phase36-armed-trailing-stop-design.md`): replace `Bracket.stopLoss: BigDecimal` with `stopLoss: StopLossSpec` — a sealed sum type with `Fixed(price)` and `ArmedTrail(distance, mfeThreshold)`. New variants in the future (volatility, time-based, indicator-based stops) plug in as additional sealed-class members. OrderManager dispatches on the variant when seeding the per-order stop state; the arming gate piggy-backs on the existing `trailingHwm` map + `trailLevel` + `triggerHit` infrastructure.

**Tech Stack:** Kotlin 2.1.0, JUnit 5, AssertJ. No new dependencies.

---

## File Structure

**New files:**
- `src/main/kotlin/com/qkt/execution/StopLossSpec.kt` — sealed interface with `Fixed` and `ArmedTrail` variants
- `src/test/kotlin/com/qkt/dsl/parse/ParserArmedTrailingStopTest.kt` — parse coverage
- `src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverArmedTrailTest.kt` — compiler coverage
- `src/test/kotlin/com/qkt/app/OrderManagerArmedTrailTest.kt` — engine arming/trail logic
- `src/test/kotlin/com/qkt/dsl/compile/ArmedTrailEndToEndTest.kt` — backtest end-to-end
- `docs/phases/phase-36-armed-trailing-stop.md` — phase changelog

**Modified files:**
- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `AFTER`
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — `parseChildPrice` TRAILING branch (~line 659), `parseBracket` TP-TRAIL rejection (~line 1080)
- `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt` — add `ChildArmedTrail` (after line 109)
- `src/main/kotlin/com/qkt/execution/OrderRequest.kt:240-261` — `Bracket.stopLoss` type change
- `src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt` — wrap existing variants in `StopLossSpec.Fixed`, dispatch `ChildArmedTrail` to `StopLossSpec.ArmedTrail`
- `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt:166` — wrap `stopLoss` literal in `StopLossSpec.Fixed`
- `src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt:46,95` — return shape from `Pair<BigDecimal, BigDecimal>` to `Pair<StopLossSpec, BigDecimal>`
- `src/main/kotlin/com/qkt/app/OrderManager.kt:56,800,1010,1083-1142` — armed-state map + arming gate
- `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt:505-512` — `BracketPairsDto` shape with sum-type discriminator
- `src/main/kotlin/com/qkt/persistence/StatePersistor.kt:109` — `BracketPair` shape
- Test files that construct `OrderRequest.Bracket` directly: `OrderRequestTest`, `OrderRequestWithExpiresAtTest`, `OrderRequestWithStrategyIdTest`, `StackEngineTest`, `BracketCompileTest`, `DefaultsEndToEndTest`, `OrderSurfaceEndToEndTest`, `ActionCompilerStackAtTest`, `StackOrchestratorEntryQtyTest`, `OrderManagerPersistFilterTest`, `MT5PollerRaceTest`, `MT5BrokerIntegrationTest`, `FileStatePersistorPendingOrdersTest`, `TradingPipelineStackTest`, `StackPnlSanityTest` — all wrap `stopLoss = BigDecimal(...)` in `StopLossSpec.Fixed(BigDecimal(...))` (mechanical)
- `docs/reference/dsl/bracket.md` — armed-trail subsection
- `docs/how-to/add-stop-loss.md` — armed-trail row in the decision table

---

## Task 1: AFTER token

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Modify (if a keyword table exists): `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` (existing — add a case)

- [ ] **Step 1: Add failing lexer test**

Find the existing keyword-lexing test class (`LexerTest.kt` or similar). Add:

```kotlin
@Test
fun `AFTER is tokenised as TokenKind AFTER`() {
    val tokens = Lexer("AFTER").tokenise()
    assertThat(tokens.first().kind).isEqualTo(TokenKind.AFTER)
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'LexerTest.AFTER is tokenised as TokenKind AFTER' -q
```

Expected: FAIL — `TokenKind.AFTER` does not exist (compile error).

- [ ] **Step 3: Add the token**

Open `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. The spec asks for `AFTER` to live "between `WITHIN` and `MFE`" for grouping coherence. Insert `AFTER` in that position.

If `Lexer.kt` has an explicit keyword table (map of `String → TokenKind`), add `"AFTER" to TokenKind.AFTER`. If the lexer derives keywords from `TokenKind.values()` reflectively (per the qkt KEYWORDS pattern — see `Parser.kt`'s comment about `KEYWORDS` being built from `TokenKind.values() minus denylist`), no further wiring is needed.

To confirm which model applies:

```bash
grep -n 'KEYWORDS\|keywordOf\|keywords\[' src/main/kotlin/com/qkt/dsl/parse/Lexer.kt
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests 'LexerTest.AFTER is tokenised as TokenKind AFTER' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt \
        src/main/kotlin/com/qkt/dsl/parse/Lexer.kt \
        src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): add AFTER token for armed trailing stop syntax (#48)"
```

---

## Task 2: ChildArmedTrail AST + StopLossSpec sum type

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/StopLossSpec.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt:109`

- [ ] **Step 1: Create StopLossSpec.kt**

```kotlin
package com.qkt.execution

import java.math.BigDecimal

/**
 * The stop-loss leg of a Bracket. Either a fixed absolute price ([Fixed]) or
 * an engine-managed armed trailing stop ([ArmedTrail]) that sits at entry until
 * MFE crosses [ArmedTrail.mfeThreshold] then trails at [ArmedTrail.trailDistance]
 * from the running favorable extreme.
 *
 * Sealed for exhaustive dispatch in [com.qkt.app.OrderManager] and the persistor.
 * Future variants (e.g. volatility-based, time-based, indicator-triggered stops)
 * plug in as additional members; the `when` blocks the compiler surfaces them.
 */
sealed interface StopLossSpec {
    data class Fixed(val price: BigDecimal) : StopLossSpec {
        init {
            require(price.signum() > 0) { "Fixed stop price must be > 0: $price" }
        }
    }

    data class ArmedTrail(
        val trailDistance: BigDecimal,
        val mfeThreshold: BigDecimal,
    ) : StopLossSpec {
        init {
            require(trailDistance.signum() > 0) { "trailDistance must be > 0: $trailDistance" }
            require(mfeThreshold.signum() >= 0) { "mfeThreshold must be >= 0: $mfeThreshold" }
        }
    }
}
```

- [ ] **Step 2: Add ChildArmedTrail AST variant**

In `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`, after the existing `ChildRr` (line 107-109), add:

```kotlin
data class ChildArmedTrail(
    val trailDistance: ExprAst,
    val mfeThreshold: ExprAst,
) : ChildPriceAst
```

- [ ] **Step 3: Compile to verify it's syntactically valid**

```bash
./gradlew compileKotlin --no-daemon -q
```

Expected: SUCCESS (this task only adds types; nothing depends on them yet).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/StopLossSpec.kt \
        src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt
git commit -m "feat(execution): add StopLossSpec sum type and ChildArmedTrail AST (#48)"
```

---

## Task 3: Parser — TRAILING branch + TP-TRAIL rejection

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt:659,1080`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserArmedTrailingStopTest.kt`

- [ ] **Step 1: Write failing parser test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserArmedTrailingStopTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParserArmedTrailingStopTest {
    private fun parse(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `STOP LOSS TRAILING distance AFTER MFE GTE threshold parses to ChildArmedTrail`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN g.close > 0 THEN BUY g SIZING 0.1 BRACKET {
                STOP LOSS TRAILING 5 AFTER MFE >= 10,
                TAKE PROFIT BY 50
              }
            """.trimIndent()
        val ast = parse(src).value
        val rule = ast.rules.first()
        val bracket = (rule.actions.first() as? com.qkt.dsl.ast.BuyAst)?.opts?.bracket
            ?: error("expected BRACKET on BUY action")
        assertThat(bracket.stopLoss).isInstanceOf(ChildArmedTrail::class.java)
    }

    @Test
    fun `TAKE PROFIT TRAILING is rejected at parse time`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN g.close > 0 THEN BUY g SIZING 0.1 BRACKET {
                STOP LOSS AT 100,
                TAKE PROFIT TRAILING 5 AFTER MFE >= 10
              }
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message)
            .containsIgnoringCase("TAKE PROFIT")
            .containsIgnoringCase("TRAILING")
    }

    @Test
    fun `missing AFTER after TRAILING distance is a parse error`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN g.close > 0 THEN BUY g SIZING 0.1 BRACKET {
                STOP LOSS TRAILING 5 MFE >= 10,
                TAKE PROFIT BY 50
              }
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message).contains("AFTER")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'ParserArmedTrailingStopTest' -q
```

Expected: FAIL on all three — parser does not yet recognise `TRAILING` in the bracket-leg position.

- [ ] **Step 3: Add TRAILING branch to parseChildPrice**

In `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` at line ~659 (`parseChildPrice`), add a branch to the `when` block before the `else` line:

```kotlin
TokenKind.TRAILING -> {
    advance()
    val distance = parseExpr()
    expect(TokenKind.AFTER, "expected AFTER after TRAILING <distance>")
    expect(TokenKind.MFE, "expected MFE after AFTER")
    expect(TokenKind.GTE, "expected '>=' after MFE")
    val threshold = parseExpr()
    ChildArmedTrail(distance, threshold)
}
```

Add the import: `import com.qkt.dsl.ast.ChildArmedTrail` at the top of `Parser.kt` (around line 49 where other `dsl.ast` imports live).

- [ ] **Step 4: Reject TAKE PROFIT TRAILING in parseBracket**

In `parseBracket` (line 1069), the `TokenKind.TAKE` branch (line 1080) currently calls `parseChildPrice()` unconditionally. Replace the body with a peek-then-parse so we can catch the disallowed case with a pointed message:

```kotlin
TokenKind.TAKE -> {
    advance()
    expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
    if (peek().kind == TokenKind.TRAILING) {
        error("TAKE PROFIT TRAILING is not supported — TRAILING is stop-only (armed trail). Use TAKE PROFIT AT/BY/PCT/RR.")
    }
    takeProfit = parseChildPrice()
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests 'ParserArmedTrailingStopTest' -q
```

Expected: PASS (all three).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/test/kotlin/com/qkt/dsl/parse/ParserArmedTrailingStopTest.kt
git commit -m "feat(dsl): parse STOP LOSS TRAILING ... AFTER MFE >= ... in BRACKET (#48)"
```

---

## Task 4: Bracket.stopLoss → StopLossSpec — the intrusive change

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt:240-261`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt:166`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt:46,95`
- Modify: `src/main/kotlin/com/qkt/persistence/StatePersistor.kt:109` and `FileStatePersistor.kt:505-512`
- Modify (~15 test files): `OrderRequestTest`, `OrderRequestWithExpiresAtTest`, `OrderRequestWithStrategyIdTest`, `StackEngineTest`, `BracketCompileTest`, `DefaultsEndToEndTest`, `OrderSurfaceEndToEndTest`, `ActionCompilerStackAtTest`, `StackOrchestratorEntryQtyTest`, `OrderManagerPersistFilterTest`, `MT5PollerRaceTest`, `MT5BrokerIntegrationTest`, `FileStatePersistorPendingOrdersTest`, `TradingPipelineStackTest`, `StackPnlSanityTest`

This task is mechanical: change one type, then sweep all call sites. Do it in small steps so the compiler tells you the exact list to fix.

- [ ] **Step 1: Change Bracket.stopLoss type**

In `src/main/kotlin/com/qkt/execution/OrderRequest.kt:247`, change:

```kotlin
val stopLoss: BigDecimal,
```

to:

```kotlin
val stopLoss: StopLossSpec,
```

And replace the `init` block's stop-loss validation (lines 254-259) with:

```kotlin
init {
    require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
    require(takeProfit.signum() > 0) { "takeProfit must be > 0: $takeProfit" }
    if (stopLoss is StopLossSpec.Fixed) {
        require(takeProfit.compareTo(stopLoss.price) != 0) {
            "takeProfit and stopLoss must differ: tp=$takeProfit sl=${stopLoss.price}"
        }
    }
}
```

Add the import: `import com.qkt.execution.StopLossSpec` if `OrderRequest.kt` doesn't already see it (same package, no import needed).

- [ ] **Step 2: Compile to surface every broken call site**

```bash
./gradlew compileKotlin --no-daemon -q
./gradlew compileTestKotlin --no-daemon -q
```

Expected: a list of "Type mismatch: required StopLossSpec, found BigDecimal" errors. That list IS your worklist for the next step.

- [ ] **Step 3: Update StackEngine.kt**

In `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt:166` (and nearby), wrap any `BigDecimal` passed as `stopLoss = ...` in `StopLossSpec.Fixed(...)`. Add import: `import com.qkt.execution.StopLossSpec`.

- [ ] **Step 4: Update StackAtCompiler.kt**

In `src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt`:

- Line 46: change `val (sl, tp) = compileBracket(clause.bracket)` to consume the new return shape. Pass `sl` directly to whichever Bracket constructor it feeds.
- Line 95: change the return type of `compileBracket` from `Pair<BigDecimal, BigDecimal>` to `Pair<StopLossSpec, BigDecimal>` and wrap the current `BigDecimal` stop in `StopLossSpec.Fixed(...)`.

Add import: `import com.qkt.execution.StopLossSpec`.

- [ ] **Step 5: Update the persistor**

`src/main/kotlin/com/qkt/persistence/StatePersistor.kt:109` defines `BracketPair`. If `stopLoss` is typed as `BigDecimal` there, change it to `StopLossSpec`.

`src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt:505,512` has `BracketPairsDto`/`BracketPairDto`. Inspect first:

```bash
sed -n '500,540p' src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt
```

For the DTO, switch the `BigDecimal` stop field to a polymorphic JSON shape. The simplest concrete encoding:

```kotlin
@Serializable
private data class BracketPairDto(
    // ...existing fields...
    val stopLossKind: String,                     // "FIXED" | "ARMED_TRAIL"
    val stopLossPrice: BigDecimal? = null,        // populated when FIXED
    val stopLossTrailDistance: BigDecimal? = null,// populated when ARMED_TRAIL
    val stopLossMfeThreshold: BigDecimal? = null, // populated when ARMED_TRAIL
    // ...
)
```

Provide encode/decode helpers:

```kotlin
private fun StopLossSpec.toDto(): Triple<String, BigDecimal?, Pair<BigDecimal?, BigDecimal?>> =
    when (this) {
        is StopLossSpec.Fixed -> Triple("FIXED", price, Pair(null, null))
        is StopLossSpec.ArmedTrail -> Triple("ARMED_TRAIL", null, Pair(trailDistance, mfeThreshold))
    }

private fun BracketPairDto.toSpec(): StopLossSpec =
    when (stopLossKind) {
        "FIXED" -> StopLossSpec.Fixed(stopLossPrice!!)
        "ARMED_TRAIL" -> StopLossSpec.ArmedTrail(stopLossTrailDistance!!, stopLossMfeThreshold!!)
        else -> error("unknown stopLossKind: $stopLossKind")
    }
```

Backwards-compat: any pre-#48 persisted file lacks `stopLossKind`. If the project has live persisted state on disk (it does on prod), make the deserializer treat a missing `stopLossKind` with a non-null `stopLossPrice` as `FIXED` for one release. Add a `@Serializable` default `val stopLossKind: String = "FIXED"`.

- [ ] **Step 6: Sweep test fixtures**

Run the compile loop and update each test file the compiler points at:

```bash
./gradlew compileTestKotlin --no-daemon -q 2>&1 | grep 'Type mismatch'
```

For each file flagged, replace `stopLoss = BigDecimal("X")` with `stopLoss = StopLossSpec.Fixed(BigDecimal("X"))` and add the import:

```kotlin
import com.qkt.execution.StopLossSpec
```

The files (per the spec's grep) are:
- `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`
- `src/test/kotlin/com/qkt/execution/OrderRequestWithExpiresAtTest.kt`
- `src/test/kotlin/com/qkt/execution/OrderRequestWithStrategyIdTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/StackEngineTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/BracketCompileTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/DefaultsEndToEndTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/OrderSurfaceEndToEndTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerStackAtTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/StackOrchestratorEntryQtyTest.kt`
- `src/test/kotlin/com/qkt/app/OrderManagerPersistFilterTest.kt`
- `src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt`
- `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`
- `src/test/kotlin/com/qkt/persistence/FileStatePersistorPendingOrdersTest.kt`
- `src/test/kotlin/com/qkt/app/TradingPipelineStackTest.kt`
- `src/test/kotlin/com/qkt/app/StackPnlSanityTest.kt`

- [ ] **Step 7: Sweep production code call sites the compiler flagged outside StackEngine/StackAtCompiler/persistor**

OrderManager reads `bracket.stopLoss` to seed the per-order stop trigger. The existing code likely does `val stop = bracket.stopLoss` — keep that line; downstream usage that pulls a `BigDecimal` out of it must now pattern-match. Defer the OrderManager logic change to Task 6; for THIS task, just unblock the compile by adding a temporary helper inside OrderManager:

```kotlin
private fun StopLossSpec.fixedPriceOrNull(): BigDecimal? =
    (this as? StopLossSpec.Fixed)?.price
```

…and use it where the old code assumed a `BigDecimal`. Task 6 will replace those call sites with proper dispatch. Add a TODO marker so the next task knows where to look:

```kotlin
// TODO(#48-task6): replace fixedPriceOrNull() with StopLossSpec dispatch
```

- [ ] **Step 8: Run the full test suite**

```bash
./gradlew test --no-daemon -q
```

Expected: PASS. The migration is type-shape only; no behaviour changed (every consumer still sees a `BigDecimal` stop price via `Fixed`).

- [ ] **Step 9: Commit**

```bash
git add -p src/ # review hunks; do not blanket -A
git commit -m "refactor(execution): Bracket.stopLoss becomes StopLossSpec sum type (#48)"
```

If `git add -p` is too granular, list the changed files explicitly:

```bash
git status --short | awk '{print $2}' | xargs git add
git commit -m "refactor(execution): Bracket.stopLoss becomes StopLossSpec sum type (#48)"
```

---

## Task 5: Compiler — ChildArmedTrail → StopLossSpec.ArmedTrail

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverArmedTrailTest.kt`

The current `ChildPriceResolver.compile(child, kind)` returns a `CompiledChildPrice` — a per-tick price evaluator. That model assumes a static price. For armed trail, no per-tick evaluation happens at the resolver; the resolved value IS the `StopLossSpec.ArmedTrail(distance, threshold)`. So the bracket-action compiler needs a new path that doesn't go through `CompiledChildPrice` for the armed case.

Approach: add a sibling method `compileStopLoss(child: ChildPriceAst): CompiledStopLoss`, where `CompiledStopLoss` is a sealed type:

```kotlin
sealed interface CompiledStopLoss {
    fun interface Dynamic : CompiledStopLoss { fun evaluate(ec: EvalContext, side: Side, entry: BigDecimal): StopLossSpec.Fixed }
    data class Static(val spec: StopLossSpec) : CompiledStopLoss
}
```

`ChildAt/By/Pct` compile to `Dynamic`; `ChildArmedTrail` compiles to `Static(StopLossSpec.ArmedTrail(...))` (constants evaluated at compile time — distance and threshold must be `NumLit`).

- [ ] **Step 1: Write failing compiler test**

Create `src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverArmedTrailTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPriceResolverArmedTrailTest {
    private val exprCompiler = ExprCompiler(streamAliases = emptySet(), indicatorBag = null)
    private val resolver = ChildPriceResolver(exprCompiler)

    @Test
    fun `ChildArmedTrail compiles to Static(StopLossSpec ArmedTrail)`() {
        val ast = ChildArmedTrail(trailDistance = NumLit(BigDecimal("5")), mfeThreshold = NumLit(BigDecimal("10")))
        val compiled = resolver.compileStopLoss(ast)
        assertThat(compiled).isInstanceOf(CompiledStopLoss.Static::class.java)
        val spec = (compiled as CompiledStopLoss.Static).spec
        assertThat(spec).isInstanceOf(StopLossSpec.ArmedTrail::class.java)
        val armed = spec as StopLossSpec.ArmedTrail
        assertThat(armed.trailDistance).isEqualByComparingTo("5")
        assertThat(armed.mfeThreshold).isEqualByComparingTo("10")
    }

    @Test
    fun `ChildArmedTrail with non-literal distance is rejected`() {
        // Distance must be a numeric literal (compile-time constant).
        val ast = ChildArmedTrail(
            trailDistance = com.qkt.dsl.ast.StreamFieldRef("g", "close"),
            mfeThreshold = NumLit(BigDecimal("10")),
        )
        assertThatThrownBy { resolver.compileStopLoss(ast) }
            .hasMessageContaining("literal")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'ChildPriceResolverArmedTrailTest' -q
```

Expected: FAIL — `compileStopLoss`, `CompiledStopLoss` do not exist.

- [ ] **Step 3: Add CompiledStopLoss sealed type**

At the top of `src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt`, after the existing `CompiledChildPrice` declaration (line 14-21), add:

```kotlin
import com.qkt.execution.StopLossSpec

sealed interface CompiledStopLoss {
    fun interface Dynamic : CompiledStopLoss {
        fun evaluate(ec: EvalContext, side: Side, entry: BigDecimal): StopLossSpec.Fixed
    }
    data class Static(val spec: StopLossSpec) : CompiledStopLoss
}
```

- [ ] **Step 4: Add compileStopLoss method**

Inside `class ChildPriceResolver`, add:

```kotlin
fun compileStopLoss(child: ChildPriceAst): CompiledStopLoss =
    when (child) {
        is ChildArmedTrail -> {
            require(child.trailDistance is NumLit) {
                "TRAILING <distance> must be a numeric literal; got ${child.trailDistance::class.simpleName}"
            }
            require(child.mfeThreshold is NumLit) {
                "AFTER MFE >= <threshold> must be a numeric literal; got ${child.mfeThreshold::class.simpleName}"
            }
            CompiledStopLoss.Static(
                StopLossSpec.ArmedTrail(child.trailDistance.value, child.mfeThreshold.value),
            )
        }
        else -> {
            val priced = compile(child, ChildKind.STOP_LOSS)
            CompiledStopLoss.Dynamic { ec, side, entry ->
                StopLossSpec.Fixed(priced.evaluate(ec, side, entry, null))
            }
        }
    }
```

Add imports for `ChildArmedTrail` and `NumLit` at the top.

- [ ] **Step 5: Wire compileStopLoss in the bracket-action compiler**

Find where the bracket action currently resolves stop-loss into a `BigDecimal` for `OrderRequest.Bracket`:

```bash
grep -n 'compile(.*STOP_LOSS\|ChildKind.STOP_LOSS\|stopLoss =' src/main/kotlin/com/qkt/dsl/compile/*.kt | head
```

Replace those sites with `compileStopLoss(...)`, evaluate to `StopLossSpec` at submission time (or use the `Static` spec directly if armed), and pass `StopLossSpec` into the Bracket constructor.

Also remove the temporary `fixedPriceOrNull()` helper added to OrderManager in Task 4 step 7 — the upstream now produces a proper `StopLossSpec`.

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew test --tests 'ChildPriceResolverArmedTrailTest' -q
```

Expected: PASS.

- [ ] **Step 7: Run full suite to check for regression**

```bash
./gradlew test --no-daemon -q
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ChildPriceResolver.kt \
        src/test/kotlin/com/qkt/dsl/compile/ChildPriceResolverArmedTrailTest.kt \
        src/main/kotlin/com/qkt/dsl/compile/StackAtCompiler.kt \
        src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt
git commit -m "feat(compile): compile ChildArmedTrail to StopLossSpec.ArmedTrail (#48)"
```

---

## Task 6: OrderManager — armed state + arming logic

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt:56,800,1010,1083-1142`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerArmedTrailTest.kt`

The engine plumbing:
- Add `private val armed: MutableMap<String, Boolean> = mutableMapOf()` next to `trailingHwm`.
- For each Bracket with `stopLoss is StopLossSpec.ArmedTrail`, seed `armed[managed.id] = false` at bracket-fill time (the moment the per-order stop is created — currently around line 800).
- In `updateTrailingHwm` (line 1083), if the managed order's stop is an armed-trail and currently NOT armed, compute MFE = |hwm - entry|; if MFE >= threshold, set armed=true and log.
- In `trailLevel` (line 1104), if armed-trail-and-not-yet-armed, return `entry` (the breakeven stop level). If armed, return `hwm ± distance` per side (existing trailing logic).
- `triggerHit` (line 1110+) — no change; uses `trailLevel`.

- [ ] **Step 1: Write failing arming test**

Create `src/test/kotlin/com/qkt/app/OrderManagerArmedTrailTest.kt`. Three behaviours:

```kotlin
package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerArmedTrailTest {
    private fun manager(): OrderManager {
        val clock = FixedClock(time = 0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        // Real-types pattern: use the existing OrderManager constructor with a no-op
        // broker. See OrderManagerPersistFilterTest for the canonical fixture shape.
        // (Concrete construction details depend on current constructor; copy from
        // OrderManagerPersistFilterTest setup.)
        TODO("copy real fixture from OrderManagerPersistFilterTest setup")
    }

    @Test
    fun `BUY armed-trail stop sits at entry until MFE crosses threshold`() {
        val om = manager()
        val req = OrderRequest.Bracket(
            id = "ord-1",
            symbol = "X",
            side = Side.BUY,
            quantity = BigDecimal("1"),
            entry = OrderRequest.Market(
                id = "ord-1-entry", symbol = "X", side = Side.BUY,
                quantity = BigDecimal("1"), timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
            takeProfit = BigDecimal("120"),
            stopLoss = StopLossSpec.ArmedTrail(
                trailDistance = BigDecimal("5"),
                mfeThreshold = BigDecimal("10"),
            ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )
        // Fill at 100; then ticks at 105 (MFE=5, not armed), 109 (MFE=9, not armed),
        // and 95 (price hits entry stop → should trigger).
        // (Exact fill/tick injection follows OrderManagerPersistFilterTest's pattern.)
        TODO("simulate fill, then ticks; assert trigger fires at 95")
    }

    @Test
    fun `BUY armed-trail stop arms at MFE = threshold and then trails`() {
        // Fill at 100; tick to 110 (MFE=10 → armed); tick to 115 (hwm=115, trail level=110);
        // tick to 109 → trigger (price below 110 post-arm).
        TODO("simulate; assert trigger fires at 109 not 95")
    }

    @Test
    fun `SELL armed-trail mirrors BUY semantics`() {
        // Fill at 100 SELL; tick to 90 (MFE=10 → armed); tick to 85 (hwm=85, trail level=90);
        // tick to 91 → trigger.
        TODO("simulate SELL side; assert trigger fires at 91")
    }
}
```

The `TODO` blocks aren't placeholder rot — they say "copy the canonical fixture from `OrderManagerPersistFilterTest`" because the OrderManager construction is too long to reproduce inline. Before running the test, read `OrderManagerPersistFilterTest` to grab the setup pattern (broker stub, position tracker, etc.) and inline it.

- [ ] **Step 2: Fill in the test fixtures from OrderManagerPersistFilterTest**

```bash
sed -n '1,80p' src/test/kotlin/com/qkt/app/OrderManagerPersistFilterTest.kt
```

Adapt its `setup()` and lift it into `OrderManagerArmedTrailTest.manager()`. Replace each `TODO` body with the concrete tick-injection sequence per the comments.

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew test --tests 'OrderManagerArmedTrailTest' -q
```

Expected: FAIL — the engine still treats every stop as `Fixed` (Task 4 unblocked compile via a coerce-to-fixed helper).

- [ ] **Step 4: Add the armed state map**

In `src/main/kotlin/com/qkt/app/OrderManager.kt` after line 56 (`private val trailingHwm`):

```kotlin
/**
 * Per-order arming state for [StopLossSpec.ArmedTrail] stops. `false` while the stop
 * sits at entry; `true` once MFE crosses the threshold. Once `true`, never reverts.
 * Bracket stops with [StopLossSpec.Fixed] do not appear in this map.
 */
private val armed: MutableMap<String, Boolean> = mutableMapOf()
```

- [ ] **Step 5: Seed armed map on bracket fill**

Around line 800 (where Bracket children are spawned post-entry-fill — find with `grep -n 'trailingHwm\[request.id\]' src/main/kotlin/com/qkt/app/OrderManager.kt`), add a sibling branch:

```kotlin
if (request is OrderRequest.Bracket && request.stopLoss is StopLossSpec.ArmedTrail) {
    armed[request.id] = false
}
```

- [ ] **Step 6: Arm-on-MFE inside updateTrailingHwm**

Modify `updateTrailingHwm` (line 1083). After updating `hwm`, check arming:

```kotlin
private fun updateTrailingHwm(managed: ManagedOrder, tickPrice: BigDecimal) {
    val current = trailingHwm[managed.id]
    when (managed.side) {
        Side.SELL -> if (current == null || tickPrice > current) trailingHwm[managed.id] = tickPrice
        Side.BUY -> if (current == null || tickPrice < current) trailingHwm[managed.id] = tickPrice
    }
    // Armed-trail arming gate (#48). MFE = |hwm - entry|. Cross-threshold → arm.
    val spec = managed.bracketStopSpec ?: return
    if (spec is StopLossSpec.ArmedTrail && armed[managed.id] == false) {
        val hwm = trailingHwm[managed.id] ?: return
        val mfe = hwm.subtract(managed.entryPrice).abs()
        if (mfe.compareTo(spec.mfeThreshold) >= 0) {
            armed[managed.id] = true
            log.info("armed: order_id={} symbol={} mfe={} threshold={}",
                managed.id, managed.symbol, mfe, spec.mfeThreshold)
        }
    }
}
```

`managed.bracketStopSpec` is a new field on `ManagedOrder` — add it. Find the `ManagedOrder` declaration (search `data class ManagedOrder` in the file) and add:

```kotlin
val bracketStopSpec: StopLossSpec? = null,
val entryPrice: BigDecimal = BigDecimal.ZERO,
```

Populate these at the same point in the code where the managed order is constructed for a Bracket (search `ManagedOrder(` near line 800).

- [ ] **Step 7: Pre-arm stop level = entry, post-arm stop level = hwm ± distance**

Modify `trailLevel` (line 1104):

```kotlin
private fun trailLevel(managed: ManagedOrder): BigDecimal? {
    val spec = managed.bracketStopSpec
    if (spec is StopLossSpec.ArmedTrail && armed[managed.id] != true) {
        // Pre-arm: stop sits at entry (breakeven).
        return managed.entryPrice
    }
    val hwm = trailingHwm[managed.id] ?: return null
    val distance = when (spec) {
        is StopLossSpec.ArmedTrail -> spec.trailDistance
        else -> managed.trailDistance ?: return null   // existing fixed trailing path
    }
    return when (managed.side) {
        Side.SELL -> hwm.add(distance)
        Side.BUY -> hwm.subtract(distance)
    }
}
```

(Adjust to whatever the current shape of `trailLevel` actually is — copy the unchanged surrounding logic.)

- [ ] **Step 8: Remove Task 4's temporary helper**

Delete `fixedPriceOrNull()` and the `TODO(#48-task6)` markers from OrderManager — Task 6's dispatch supersedes them.

- [ ] **Step 9: Run the test to verify it passes**

```bash
./gradlew test --tests 'OrderManagerArmedTrailTest' -q
```

Expected: PASS (all three test methods).

- [ ] **Step 10: Run the full suite**

```bash
./gradlew test --no-daemon -q
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt \
        src/test/kotlin/com/qkt/app/OrderManagerArmedTrailTest.kt
git commit -m "feat(execution): armed-trail stop arming + post-arm trail in OrderManager (#48)"
```

---

## Task 7: End-to-end Backtest test

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/compile/ArmedTrailEndToEndTest.kt`

- [ ] **Step 1: Write the end-to-end test**

Create `src/test/kotlin/com/qkt/dsl/compile/ArmedTrailEndToEndTest.kt`. Run a DSL strategy through Backtest with a tick sequence designed to exercise: (a) MFE reaches threshold and a retracement triggers the trail, (b) MFE never reaches threshold and price hits entry stop.

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArmedTrailEndToEndTest {
    private val src =
        """
        STRATEGY armed_demo VERSION 1
        SYMBOLS
          g = X:Y EVERY 1m
        RULES
          WHEN g.close > 100 AND POSITION.g = 0 THEN BUY g SIZING 0.1 BRACKET {
            STOP LOSS TRAILING 5 AFTER MFE >= 10,
            TAKE PROFIT BY 50
          }
        """.trimIndent()

    private fun strategy() = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    @Test
    fun `armed-trail triggers after MFE crosses threshold and price retraces`() {
        // Ticks: 99 (no entry), 101 (fill at ~101), 110 (MFE ~9, not armed),
        // 112 (MFE ~11, armed; trail=107), 106 (below 107 → exit via stop).
        val ticks = listOf(
            BigDecimal("99"), BigDecimal("101"),
            BigDecimal("110"), BigDecimal("112"), BigDecimal("106"),
        )
        // Convert to the Backtest synthetic-tick API (see SyntheticTickStream in
        // src/test/kotlin/com/qkt/app/SyntheticTickStream.kt for the canonical helper).
        val result = runBacktest(strategy(), ticks)
        assertThat(result.trades).hasSize(2)            // entry fill + stop fill
        assertThat(result.trades.last().price).isLessThan(BigDecimal("107"))
    }

    @Test
    fun `armed-trail never arms when MFE stays below threshold; exits at entry stop`() {
        // Ticks: 101 (fill), 105 (MFE 4), 99 (back through entry → stop).
        val ticks = listOf(
            BigDecimal("99"), BigDecimal("101"),
            BigDecimal("105"), BigDecimal("99"),
        )
        val result = runBacktest(strategy(), ticks)
        assertThat(result.trades).hasSize(2)
        assertThat(result.trades.last().price).isCloseTo(BigDecimal("101"), org.assertj.core.api.Assertions.within(BigDecimal("0.5")))
    }

    private fun runBacktest(strategy: com.qkt.strategy.Strategy, ticks: List<BigDecimal>): com.qkt.backtest.BacktestResult {
        // Copy fixture from any existing end-to-end test (e.g. BracketCompileTest,
        // ArmedTrailing is structurally identical to BracketCompileTest's harness).
        TODO("inline the SyntheticTickStream harness from BracketCompileTest")
    }
}
```

Inline the `runBacktest` body from whichever existing end-to-end test has the cleanest harness — `BracketCompileTest` or `OrderSurfaceEndToEndTest` are the usual references.

- [ ] **Step 2: Run the test**

```bash
./gradlew test --tests 'ArmedTrailEndToEndTest' -q
```

Expected: PASS (both methods).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/ArmedTrailEndToEndTest.kt
git commit -m "test(dsl): end-to-end backtest for armed trailing stop (#48)"
```

---

## Task 8: Documentation

**Files:**
- Modify: `docs/reference/dsl/bracket.md`
- Modify: `docs/how-to/add-stop-loss.md`
- Create: `docs/phases/phase-36-armed-trailing-stop.md`

- [ ] **Step 1: Add the reference section to bracket.md**

Open `docs/reference/dsl/bracket.md`. Add a subsection under the existing "Stop loss children" section:

```markdown
### Armed trailing stop

`STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>`

The stop sits at entry (breakeven) until the trade's maximum favorable
excursion (MFE) crosses `<threshold>`. From that moment forward, the
stop trails the running favorable extreme at `<distance>`.

```qkt
BUY btc SIZING 0.1 BRACKET {
  STOP LOSS TRAILING 5 AFTER MFE >= 10,
  TAKE PROFIT BY 50
}
```

Both `<distance>` and `<threshold>` must be positive numeric literals (no
expressions — the values are baked at compile time). Arming is one-way:
once armed, the stop never disarms. `TAKE PROFIT TRAILING` is not
supported (TP is a target, not a stop).
```

- [ ] **Step 2: Update the decision table in add-stop-loss.md**

Open `docs/how-to/add-stop-loss.md`. In the existing "Which stop-loss child price form to use" table, add a row:

```markdown
| Want stop at entry until profitable, then trail | `STOP LOSS TRAILING <dist> AFTER MFE >= <thresh>` |
```

- [ ] **Step 3: Write phase-36 changelog**

Create `docs/phases/phase-36-armed-trailing-stop.md`. Follow the qkt skill's phase-changelog requirements (Summary, What's new, Migration, Usage cookbook, Testing patterns, Known limitations, References):

```markdown
# Phase 36 — Armed Trailing Stop

## Summary
The DSL now supports armed trailing stops via `STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>` as a bracket leg. The stop holds at entry until the trade's MFE crosses the threshold, then converts to a trailing stop at the given distance from the favorable extreme. Closes GAP 1 in the hedge-straddle parity port (#48).

## What's new
- `OrderRequest.Bracket.stopLoss` is now a `StopLossSpec` sealed type with `Fixed(price)` and `ArmedTrail(distance, mfeThreshold)` variants.
- DSL syntax: `STOP LOSS TRAILING <numeric literal> AFTER MFE >= <numeric literal>` inside `BRACKET { ... }`.
- New `TokenKind.AFTER`.
- New AST node `ChildArmedTrail`.
- OrderManager arming gate piggy-backed on `trailingHwm`.

## Migration from previous phase
Code that constructs `OrderRequest.Bracket` directly must wrap the stop price:

```kotlin
// Before
OrderRequest.Bracket(..., stopLoss = BigDecimal("100"), ...)

// After
OrderRequest.Bracket(..., stopLoss = StopLossSpec.Fixed(BigDecimal("100")), ...)
```

Persisted bracket state encodes a `stopLossKind` discriminator; pre-#48 files (without the field) decode as `Fixed` for one release.

## Usage cookbook
[3–4 worked examples: simple armed trail; armed trail with TP RR; armed trail
on SELL; armed trail in STACK_AT context.]

## Testing patterns
`OrderManagerArmedTrailTest` is the canonical fixture for engine-side arming
behaviour. `ArmedTrailEndToEndTest` runs the full DSL → Backtest → trades path
with hand-crafted tick sequences.

## Known limitations
- Trail distance is ABSOLUTE only — no PERCENT support yet.
- One-time arming (no disarm/rearm cycles).
- `WITHIN <duration>` time-bounded arming is not in scope.
- TAKE PROFIT TRAILING is rejected at parse time.

## References
- Spec: `docs/superpowers/specs/2026-05-25-phase36-armed-trailing-stop-design.md`
- Plan: `docs/superpowers/plans/2026-05-29-phase36-armed-trailing-stop.md`
- Issue: [#48](https://github.com/elitekaycy/qkt/issues/48)
- Merge commit SHA: [populated after merge]
```

Fill the "Usage cookbook" section with 3–4 runnable examples before committing — use working DSL snippets, not pseudocode.

- [ ] **Step 4: Verify mkdocs builds cleanly**

```bash
./gradlew dokkaHtml --no-daemon -q  # KDoc still clean
mkdocs build --strict 2>&1 | tail -20  # if mkdocs is available locally
```

If `mkdocs` isn't installed, skip and let CI verify.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/dsl/bracket.md \
        docs/how-to/add-stop-loss.md \
        docs/phases/phase-36-armed-trailing-stop.md
git commit -m "docs(phase36): bracket armed trailing stop reference how-to and changelog (#48)"
```

---

## Task 9: PR

- [ ] **Step 1: Bring branch up to date with dev**

```bash
git fetch origin dev
git rebase origin/dev   # or merge if rebase causes conflicts
./gradlew build --no-daemon -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Open PR**

```bash
gh pr create --base dev --head issue48-armed-trailing-stop \
  --title "feat(dsl): armed trailing stop (#48)" \
  --body "$(cat <<'EOF'
Closes #48.

## Phase
Phase 36. Spec: \`docs/superpowers/specs/2026-05-25-phase36-armed-trailing-stop-design.md\`. Plan: \`docs/superpowers/plans/2026-05-29-phase36-armed-trailing-stop.md\`.

## Summary
DSL syntax \`STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>\` adds armed trailing stops as a bracket leg. The stop sits at entry until MFE crosses the threshold, then trails. Closes GAP 1 in the hedge-straddle parity port.

## Changes
- New \`StopLossSpec\` sealed type (Fixed | ArmedTrail).
- New token \`AFTER\`, new AST node \`ChildArmedTrail\`.
- Parser branch in \`parseChildPrice\`, TP-TRAIL rejection in \`parseBracket\`.
- Compiler dispatch via \`compileStopLoss\` → \`CompiledStopLoss\`.
- OrderManager arming gate on \`trailingHwm\`.
- Persistor migration for the sum-type discriminator.
- ~15 test fixtures updated to wrap stopLoss in \`StopLossSpec.Fixed\`.

## Tests
- Parser: \`ParserArmedTrailingStopTest\` (3 cases).
- Compiler: \`ChildPriceResolverArmedTrailTest\` (2 cases).
- Engine: \`OrderManagerArmedTrailTest\` (3 cases — pre-arm, post-arm, SELL).
- E2E: \`ArmedTrailEndToEndTest\` (2 cases — armed-and-triggered, never-armed).
- Local: \`./gradlew build\` green.

## Docs
- \`docs/reference/dsl/bracket.md\` — armed trail subsection.
- \`docs/how-to/add-stop-loss.md\` — decision-table row.
- \`docs/phases/phase-36-armed-trailing-stop.md\` — phase changelog.

## Backwards compatibility
Persisted bracket state pre-#48 decodes as \`Fixed\` via a default discriminator. New strategies use the new syntax; existing strategies parse and behave unchanged.

## Out of scope
- PERCENT trail distance (deferred).
- Disarm/rearm cycles (one-time arming by design).
- \`WITHIN <duration>\` modifier.

## Risk
Medium. Touches Bracket type signature, OrderManager arming logic, and the persistor. Mitigations: pure-function tests for matchOrphan-style logic, OrderManager tests cover BUY/SELL/pre-arm/post-arm, end-to-end test exercises both armed-and-triggered and never-armed paths.
EOF
)"
```

- [ ] **Step 3: Watch CI**

```bash
gh pr checks --watch
```

Expected: build PASS.

---

## Self-review checklist

**Spec coverage:**
- Goal (parse + run armed trail) → Tasks 3, 5, 6
- TAKE PROFIT TRAIL rejection → Task 3
- Positive-literal validation → Task 5 (compile-time NumLit check)
- BUY/SELL mirrored arming → Task 6 + Task 7
- OCO with TP → uses existing Bracket OCO; covered by Task 7 e2e
- Multi-leg / STACK_AT → existing path; Bracket-on-stack works because StackAtCompiler now returns `Pair<StopLossSpec, BigDecimal>` (Task 4 step 4)
- Fail-safe behaviours (MFE never crosses, threshold=0, threshold=∞) → covered by `StopLossSpec.ArmedTrail.init` for negatives, by Task 6/7 tests for the rest

**Placeholder scan:**
- Two `TODO` markers in test files — they are explicit "copy from `OrderManagerPersistFilterTest` setup" / "inline harness from `BracketCompileTest`" instructions with the source file named. Not rot; they point at a concrete, locatable place. Acceptable.
- One `// TODO(#48-task6)` marker in production code (Task 4 step 7) explicitly REMOVED in Task 6 step 8.

**Type consistency:**
- `StopLossSpec.Fixed.price` (Task 2) → consumed in Task 4 (`stopLoss.price`), Task 5 (`StopLossSpec.Fixed(BigDecimal)`), Task 6 (`bracketStopSpec` dispatch). Consistent.
- `StopLossSpec.ArmedTrail.trailDistance` / `.mfeThreshold` (Task 2) → consumed in Task 5 (`ChildArmedTrail` → `StopLossSpec.ArmedTrail(distance, threshold)`) and Task 6 (`spec.trailDistance` / `spec.mfeThreshold`). Consistent.
- `CompiledStopLoss.Dynamic.evaluate` returns `StopLossSpec.Fixed` (Task 5) → consumed at the bracket-action compile site to build `OrderRequest.Bracket(stopLoss = ...)`. Consistent.
- `ManagedOrder.bracketStopSpec: StopLossSpec?` (Task 6) — referenced in `trailLevel`, `updateTrailingHwm`. Consistent.

No gaps found.
