# LATCH Primitive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `LATCH` directional-trigger entry primitive to the qkt DSL — arm `ref ± offset` trip-wires, watch ticks engine-side, and on the first break resolve a direction + anchor `O` and fan out direction-relative entries.

**Architecture:** A `Latch` AST node compiles each entry into a builder `(direction, anchor, ctx) -> OrderRequest` (reusing the existing order/bracket/sizing/GTD constructors); arming flows through a new `Signal.ArmLatch`; a per-tick `LatchManager` holds armed latches, detects the first cross, and submits the built orders through the existing risk-approve → `OrderEvent` → `OrderManager` path. Only the pre-trip state machine is new.

**Tech Stack:** Kotlin/JVM 21, `com.qkt.dsl.{ast,parse,compile}`, `com.qkt.app.{TradingPipeline,LatchManager}`, JUnit5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-05-latch-primitive-design.md`
**Issue:** #264  **Branch:** `issue264-latch-primitive`

**Conventions (qkt):** Conventional Commits, **subject line only — no body, no footer, no AI attribution.** Reference `(#264)`. ktlint runs in `check.yml`; leave a **blank line before any standalone `//` comment**. Run targeted tests with `./gradlew test --tests '<FQCN>'`; let CI do the full build. PRs target `dev`.

**Key facts extracted from the codebase (do not re-derive):**
- `ActionAst` is a `sealed interface` in `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt`. Members may live in other files of the same package, so the new `Latch` member can go in a new `Latch.kt`.
- Compiled action shape: `ActionCompiler.compile(action): (EvalContext) -> List<Signal>` — actions **return** signals; the pipeline routes them. There is no emit callback.
- New keywords auto-register: the lexer maps every `TokenKind` enum name (uppercased) to a keyword (`Lexer.kt:251-287`), except the punctuation/literal exclusion set. **Adding a name to `TokenKind` is all that's needed for the lexer.**
- `OrderRequest.Bracket(id, symbol, side, quantity, entry: OrderRequest, takeProfit: BigDecimal, stopLoss: StopLossSpec, timeInForce, timestamp, strategyId, expiresAt)` wraps an entry order; `StopLossSpec.Fixed(price: BigDecimal)`.
- `OrderRequest.Limit(id, symbol, side, quantity, limitPrice, timeInForce, timestamp, strategyId, expiresAt)`, `.Stop(... stopPrice ...)`, `.Market(id, symbol, side, quantity, timeInForce, timestamp, strategyId)`.
- `Tick(symbol: String, price: BigDecimal, timestamp: Long, volume, bid, ask, …)`; `Clock.now(): Long`.
- `EvalContext(candle, streams, lets, strategyContext, snapshotStore, hub, currentAlias)` (`CompiledExpr.kt:23`).
- `SizingCompiler.compile(sizing: SizingAst, stopDistance: BigDecimal?, streamAlias: String): CompiledSize`; `CompiledSize.evaluate(ec, entryPrice): BigDecimal`. `SizeRiskAbs` requires non-null positive `stopDistance`.
- `StreamFieldRef(stream: String, field: String)` (`ExprAst.kt:24`).

---

## Task 1: `Latch` AST + sum-type wiring

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/Latch.kt`
- Test: `src/test/kotlin/com/qkt/dsl/ast/LatchAstTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/ast/LatchAstTest.kt`:

```kotlin
package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchAstTest {
    @Test
    fun `latch is an action with sensor, arm window, and entries`() {
        val latch =
            Latch(
                stream = "gold",
                sensor = BreakOffset(reference = null, offset = NumLit(java.math.BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries =
                    listOf(
                        LatchEntry(
                            order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(java.math.BigDecimal("4")))),
                            bracket =
                                LatchBracket(
                                    stopLoss = DirRel(DirSense.AGAINST, NumLit(java.math.BigDecimal("12"))),
                                    takeProfit = DirRel(DirSense.WITH, NumLit(java.math.BigDecimal("5"))),
                                ),
                            sizing = SizeRiskAbs(NumLit(java.math.BigDecimal("250"))),
                            expire = DurationAst(7_200_000L),
                        ),
                    ),
            )
        assertThat(latch).isInstanceOf(ActionAst::class.java)
        assertThat(latch.sensor).isInstanceOf(BreakOffset::class.java)
        assertThat(latch.entries).hasSize(1)
        assertThat((latch.entries[0].order as LatchLimit).price.sense).isEqualTo(DirSense.AGAINST)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.LatchAstTest'`
Expected: FAIL — `Latch`, `BreakOffset`, `LatchEntry`, etc. unresolved.

- [ ] **Step 3: Create the AST file**

Create `src/main/kotlin/com/qkt/dsl/ast/Latch.kt`:

```kotlin
package com.qkt.dsl.ast

/**
 * A directional-trigger entry. Arms two price trip-wires (`ref ± offset`); the
 * first one the market crosses sets a direction (BUY on the up-wire, SELL on the
 * down-wire) and an anchor price `O`. The [entries] are then placed relative to
 * `O` and that direction. e.g. an up-break at O=2000.5 turns `RETRACE 4` into a
 * BUY LIMIT at 1996.5.
 */
data class Latch(
    val stream: String,
    val sensor: LatchSensor,
    val armWindow: DurationAst,
    val name: String?,
    val entries: List<LatchEntry>,
) : ActionAst

/** How a latch decides it has tripped. Sealed so future sensors add as members. */
sealed interface LatchSensor

/** Trip when price crosses [reference] ± [offset]. [reference] null => `<stream>.close`. */
data class BreakOffset(
    val reference: ExprAst?,
    val offset: ExprAst,
) : LatchSensor

/** One entry placed when the latch trips, written relative to the break. */
data class LatchEntry(
    val order: LatchOrder,
    val bracket: LatchBracket? = null,
    val sizing: SizingAst? = null,
    val expire: DurationAst? = null,
)

sealed interface LatchOrder

data object LatchMarket : LatchOrder

data class LatchLimit(
    val price: DirRel,
) : LatchOrder

data class LatchStop(
    val price: DirRel,
) : LatchOrder

/** WITH = with the break (O + dir*d); AGAINST = against it (O - dir*d). RETRACE parses to AGAINST. */
enum class DirSense { WITH, AGAINST }

data class DirRel(
    val sense: DirSense,
    val dist: ExprAst,
)

data class LatchBracket(
    val stopLoss: DirRel? = null,
    val takeProfit: DirRel? = null,
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.ast.LatchAstTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/Latch.kt src/test/kotlin/com/qkt/dsl/ast/LatchAstTest.kt
git commit -m "feat: add Latch AST node and direction-relative entry types (#264)"
```

---

## Task 2: Parser — tokens + `parseLatch`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` (add tokens)
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseAction`, `isActionStart`, add `parseLatch`/`parseLatchEntry`/`parseLatchOrder`/`parseDirRel`)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserLatchTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserLatchTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserLatchTest {
    private fun firstAction(body: String): Any {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 5m
            RULES
                WHEN NOW.minute_utc = 55
                THEN $body
            """.trimIndent()
        val strat = Parser(Lexer(src).lex()).parseStrategy()
        return strat.rules.first().action
    }

    @Test
    fun `parses a latch with one limit retrace entry`() {
        val action =
            firstAction(
                """
                LATCH gold OFFSET 0.50 ARM 5m {
                    ENTER LIMIT RETRACE 4 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING RISK ${'$'} 250 EXPIRE 2h
                }
                """.trimIndent(),
            )
        assertThat(action).isInstanceOf(Latch::class.java)
        val latch = action as Latch
        assertThat(latch.stream).isEqualTo("gold")
        assertThat(latch.armWindow.millis).isEqualTo(300_000L)
        val sensor = latch.sensor as BreakOffset
        assertThat(sensor.reference).isNull()
        assertThat(latch.entries).hasSize(1)
        val order = latch.entries[0].order as LatchLimit
        assertThat(order.price.sense).isEqualTo(DirSense.AGAINST)
        assertThat(latch.entries[0].expire?.millis).isEqualTo(7_200_000L)
    }

    @Test
    fun `parses market entry and multiple semicolon-separated entries`() {
        val action =
            firstAction(
                """
                LATCH gold OFFSET 0.50 ARM 5m {
                    ENTER MARKET BRACKET { STOP LOSS AGAINST 10, TAKE PROFIT WITH 30 } SIZING RISK ${'$'} 200 ;
                    ENTER LIMIT RETRACE 6 SIZING RISK ${'$'} 200
                }
                """.trimIndent(),
            )
        val latch = action as Latch
        assertThat(latch.entries).hasSize(2)
        assertThat(latch.entries[0].order).isEqualTo(LatchMarket)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserLatchTest'`
Expected: FAIL — `LATCH`/`ENTER`/`OFFSET`/`ARM`/`WITH`/`AGAINST`/`RETRACE` tokens and `parseLatch` do not exist.

- [ ] **Step 3: Add the tokens**

In `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`, add these enum members (anywhere in the keyword section — order is irrelevant; the lexer maps by name):

```kotlin
    LATCH,
    ENTER,
    OFFSET,
    ARM,
    WITH,
    AGAINST,
    RETRACE,
    FROM,
```

(The lexer auto-registers them as keywords — no other lexer change needed.)

- [ ] **Step 4: Wire `parseAction` and `isActionStart`**

In `Parser.kt` `parseAction()`'s `when`, add before the `else`:

```kotlin
            TokenKind.LATCH -> parseLatch()
```

In `isActionStart()` (around line 844), add `TokenKind.LATCH` to the set of tokens it recognizes as the start of an action (so multi-action `THEN` blocks containing a latch are gated correctly). Find the existing list (it includes `BUY, SELL, CLOSE, CANCEL, LOG, OCO_ENTRY, …`) and add `TokenKind.LATCH`.

- [ ] **Step 5: Add the parse functions**

Add these to `Parser.kt` (near `parseOcoEntry`):

```kotlin
    private fun parseLatch(): ActionAst {
        expect(TokenKind.LATCH, "expected LATCH")
        val stream = expect(TokenKind.IDENT, "expected stream alias after LATCH").lexeme
        expect(TokenKind.OFFSET, "expected OFFSET after LATCH stream")
        val offset = parseExpr()
        val reference =
            if (match(TokenKind.FROM)) {
                parseExpr()
            } else {
                null
            }
        expect(TokenKind.ARM, "expected ARM <duration> in LATCH")
        val armWindow = parseDuration()
        val name =
            if (match(TokenKind.AS)) {
                expect(TokenKind.IDENT, "expected name after AS").lexeme
            } else {
                null
            }
        expect(TokenKind.LBRACE, "expected '{' to open LATCH block")
        val entries = mutableListOf(parseLatchEntry())

        while (match(TokenKind.SEMICOLON)) {
            entries.add(parseLatchEntry())
        }
        expect(TokenKind.RBRACE, "expected '}' to close LATCH block")
        return Latch(stream, BreakOffset(reference, offset), armWindow, name, entries)
    }

    private fun parseLatchEntry(): LatchEntry {
        expect(TokenKind.ENTER, "expected ENTER in LATCH block")
        val order = parseLatchOrder()
        var bracket: LatchBracket? = null
        var sizing: SizingAst? = null
        var expire: DurationAst? = null
        loop@ while (true) {
            when (peek().kind) {
                TokenKind.BRACKET -> {
                    advance()
                    bracket = parseLatchBracket()
                }
                TokenKind.SIZING -> {
                    advance()
                    sizing = parseSizing()
                }
                TokenKind.EXPIRE -> {
                    advance()
                    expire = parseDuration()
                }
                else -> break@loop
            }
        }
        return LatchEntry(order, bracket, sizing, expire)
    }

    private fun parseLatchOrder(): LatchOrder =
        when (peek().kind) {
            TokenKind.MARKET -> {
                advance()
                LatchMarket
            }
            TokenKind.LIMIT -> {
                advance()
                LatchLimit(parseDirRel())
            }
            TokenKind.STOP -> {
                advance()
                LatchStop(parseDirRel())
            }
            else -> error("expected MARKET/LIMIT/STOP after ENTER, got '${peek().lexeme}'")
        }

    private fun parseDirRel(): DirRel {
        val sense =
            when (peek().kind) {
                TokenKind.WITH -> DirSense.WITH
                TokenKind.AGAINST -> DirSense.AGAINST
                TokenKind.RETRACE -> DirSense.AGAINST
                else -> error("expected WITH/AGAINST/RETRACE, got '${peek().lexeme}'")
            }
        advance()
        return DirRel(sense, parseExpr())
    }

    private fun parseLatchBracket(): LatchBracket {
        expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: DirRel? = null
        var takeProfit: DirRel? = null
        do {
            when (peek().kind) {
                TokenKind.STOP -> {
                    advance()
                    expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = parseDirRel()
                }
                TokenKind.TAKE -> {
                    advance()
                    expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    takeProfit = parseDirRel()
                }
                else -> error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${peek().lexeme}'")
            }
        } while (match(TokenKind.COMMA))
        expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return LatchBracket(stopLoss, takeProfit)
    }
```

Add the imports at the top of `Parser.kt` for the new AST types (`Latch`, `BreakOffset`, `LatchEntry`, `LatchOrder`, `LatchMarket`, `LatchLimit`, `LatchStop`, `DirRel`, `DirSense`, `LatchBracket`).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserLatchTest'`
Expected: PASS (2 tests).

- [ ] **Step 7: Add the `Latch` branch to `IterVarSubstitution`**

In `src/main/kotlin/com/qkt/dsl/parse/IterVarSubstitution.kt`, in the `subst(action, v, alias)` `when`, add:

```kotlin
        is Latch -> if (action.stream == v) action.copy(stream = alias) else action
```

(Latch entries reference no stream alias themselves, so only the latch's own `stream` substitutes.)

- [ ] **Step 8: Run the parse package tests to confirm nothing regressed**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.*'`
Expected: PASS (existing parse tests + the new latch tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/main/kotlin/com/qkt/dsl/parse/IterVarSubstitution.kt src/test/kotlin/com/qkt/dsl/parse/ParserLatchTest.kt
git commit -m "feat: parse the LATCH entry block (#264)"
```

---

## Task 3: `LatchCompiler` — entries → builders

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/LatchCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/LatchCompilerTest.kt`

The compiler turns a `Latch` into a `CompiledLatch` whose entry builders, given `(direction: Int, anchor: BigDecimal, ec: EvalContext)`, produce an `OrderRequest` (or null to skip on inverted geometry). The **stop distance is static** — `|slDist ∓ entryDist|` after `O` and direction cancel — so risk sizing reuses `SizingCompiler` unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/LatchCompilerTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.execution.OrderRequest
import com.qkt.execution.Side
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchCompilerTest {
    private fun limitLatch(): Latch =
        Latch(
            stream = "gold",
            sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
            armWindow = DurationAst(300_000L),
            name = null,
            entries =
                listOf(
                    LatchEntry(
                        order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(BigDecimal("4")))),
                        bracket =
                            LatchBracket(
                                stopLoss = DirRel(DirSense.AGAINST, NumLit(BigDecimal("12"))),
                                takeProfit = DirRel(DirSense.WITH, NumLit(BigDecimal("5"))),
                            ),
                        sizing = SizeRiskAbs(NumLit(BigDecimal("250"))),
                        expire = DurationAst(7_200_000L),
                    ),
                ),
        )

    @Test
    fun `long break builds a BUY bracketed limit below the anchor`() {
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(direction = 1, anchor = BigDecimal("2000.50"), ec = ec)!!
        val bracket = req as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.BUY)
        val entry = bracket.entry as OrderRequest.Limit
        assertThat(entry.limitPrice).isEqualByComparingTo("1996.50") // O - 4
        assertThat(bracket.takeProfit).isEqualByComparingTo("2005.50") // O + 5
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1988.50") // O - 12
    }

    @Test
    fun `short break mirrors the geometry as a SELL`() {
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(direction = -1, anchor = BigDecimal("1999.50"), ec = ec)!!
        val bracket = req as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.SELL)
        val entry = bracket.entry as OrderRequest.Limit
        assertThat(entry.limitPrice).isEqualByComparingTo("2003.50") // O + 4
        assertThat(bracket.takeProfit).isEqualByComparingTo("1994.50") // O - 5
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2011.50") // O + 12
    }

    @Test
    fun `risk sizing uses the static stop distance abs(sl - entryDepth)`() {
        // stopDistance = |12 - 4| = 8 ; risk $250 / (8 * contractSize)
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(1, BigDecimal("2000.50"), ec) as OrderRequest.Bracket
        // contractSize for XAUUSD test instrument is 1 in the fixture -> qty = 250 / 8 = 31.25
        assertThat(req.quantity).isEqualByComparingTo("31.25")
    }
}
```

Create the test fixture `src/test/kotlin/com/qkt/dsl/compile/LatchCompilerFixture.kt` that constructs a `LatchCompiler` with the same collaborators `ActionCompiler` uses (`ExprCompiler`, `SizingCompiler`, `ChildPriceResolver`, an `IdSource`/`Clock` stub, and a `StrategyContext` with a unit-contractSize XAUUSD instrument). Model it on the setup in the existing `OrderTypeCompilerTest`/`ActionCompiler` tests — read those for the exact constructor wiring of `ExprCompiler`/`SizingCompiler` and the `EvalContext` builder, and reproduce a minimal version here. (This fixture is test-only glue; keep it under `src/test`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.LatchCompilerTest'`
Expected: FAIL — `LatchCompiler`/`CompiledLatch` unresolved.

- [ ] **Step 3: Implement `LatchCompiler`**

Create `src/main/kotlin/com/qkt/dsl/compile/LatchCompiler.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.execution.Side
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** A compiled latch: how to compute its triggers and how to build each entry on a trip. */
class CompiledLatch(
    val streamAlias: String,
    val offset: CompiledExpr,
    val reference: CompiledExpr,
    val armWindowMs: Long,
    val name: String?,
    val entryBuilders: List<LatchEntryBuilder>,
)

fun interface LatchEntryBuilder {
    /** direction: +1 long / -1 short. anchor: the trigger price O. Returns null to skip (bad geometry). */
    fun build(
        direction: Int,
        anchor: BigDecimal,
        ec: EvalContext,
    ): OrderRequest?
}

class LatchCompiler(
    private val exprCompiler: ExprCompiler,
    private val sizingCompiler: SizingCompiler,
    private val ids: IdSource,
    private val clock: com.qkt.common.Clock,
) {
    private val log = LoggerFactory.getLogger(LatchCompiler::class.java)

    fun compile(
        latch: Latch,
        strategyId: String,
    ): CompiledLatch {
        val sensor = latch.sensor as BreakOffset
        val referenceExpr = sensor.reference ?: StreamFieldRef(latch.stream, "close")
        val builders = latch.entries.map { compileEntry(latch.stream, it, strategyId) }
        return CompiledLatch(
            streamAlias = latch.stream,
            offset = exprCompiler.compile(sensor.offset),
            reference = exprCompiler.compile(referenceExpr),
            armWindowMs = latch.armWindow.millis,
            name = latch.name,
            entryBuilders = builders,
        )
    }

    private fun compileEntry(
        stream: String,
        entry: LatchEntry,
        strategyId: String,
    ): LatchEntryBuilder {
        // dist contribution in "direction units": WITH = +d, AGAINST = -d, MARKET = 0.
        val entryRel: DirRel? = (entry.order as? LatchLimit)?.price ?: (entry.order as? LatchStop)?.price
        val entryContrib: BigDecimal = signedDist(entryRel)
        val slRel = entry.bracket?.stopLoss
        val tpRel = entry.bracket?.takeProfit

        // Static stop distance = |entryContrib - slContrib| (O and direction cancel).
        val stopDistance: BigDecimal? =
            if (slRel != null) {
                (entryContrib - signedDist(slRel)).abs()
            } else {
                null
            }
        val compiledSize = entry.sizing?.let { sizingCompiler.compile(it, stopDistance, stream) }
        val expiresInMs = entry.expire?.millis

        return LatchEntryBuilder { direction, anchor, ec ->
            val dir = BigDecimal(direction)
            val side = if (direction > 0) Side.BUY else Side.SELL
            val now = clock.now()
            val id = ids.next()

            // Resolve direction-relative prices: WITH = O + dir*d ; AGAINST = O - dir*d.
            fun resolve(rel: DirRel): BigDecimal {
                val d = exprCompiler.compile(rel.dist).evaluate(ec).asNum()
                return if (rel.sense == DirSense.WITH) anchor + dir * d else anchor - dir * d
            }

            val entryReq: OrderRequest =
                when (val o = entry.order) {
                    is LatchMarket -> OrderRequest.Market(id, ec.candle.symbol, side, BigDecimal.ONE, TimeInForce.GTC, now, strategyId)
                    is LatchLimit -> OrderRequest.Limit(id, ec.candle.symbol, side, BigDecimal.ONE, resolve(o.price), TimeInForce.GTC, now, strategyId, expiresAt(now, expiresInMs))
                    is LatchStop -> OrderRequest.Stop(id, ec.candle.symbol, side, BigDecimal.ONE, resolve(o.price), TimeInForce.GTC, now, strategyId, expiresAt(now, expiresInMs))
                }

            val entryPrice =
                when (val o = entry.order) {
                    is LatchMarket -> anchor
                    is LatchLimit -> resolve(o.price)
                    is LatchStop -> resolve(o.price)
                }
            val qty = compiledSize?.evaluate(ec, entryPrice) ?: BigDecimal.ONE

            if (slRel == null && tpRel == null) {
                return@LatchEntryBuilder withQty(entryReq, qty)
            }
            val slPrice = slRel?.let { resolve(it) }
            val tpPrice = tpRel?.let { resolve(it) } ?: entryPrice

            // Inverted-geometry guard: a long entry whose stop sits at/above the fill (or short
            // whose stop sits at/below) is invalid — skip it rather than place a broken order.
            if (slPrice != null && invalidStop(side, entryPrice, slPrice)) {
                log.warn("latch entry skipped (inverted geometry): entry=$entryPrice sl=$slPrice side=$side")
                return@LatchEntryBuilder null
            }
            OrderRequest.Bracket(
                id, ec.candle.symbol, side, qty,
                entry = withQty(entryReq, qty),
                takeProfit = tpPrice,
                stopLoss = StopLossSpec.Fixed(slPrice ?: entryPrice),
                timeInForce = TimeInForce.GTC,
                timestamp = now,
                strategyId = strategyId,
                expiresAt = expiresAt(now, expiresInMs),
            )
        }
    }

    private fun signedDist(rel: DirRel?): BigDecimal {
        if (rel == null) return BigDecimal.ZERO
        val d = (rel.dist as? NumLit)?.value
            ?: error("LATCH distances must be compile-time constants (literal or LET); got ${rel.dist}")
        return if (rel.sense == DirSense.WITH) d else d.negate()
    }

    private fun expiresAt(now: Long, ms: Long?): Long? = ms?.let { now + it }

    private fun invalidStop(side: Side, entry: BigDecimal, sl: BigDecimal): Boolean =
        if (side == Side.BUY) sl >= entry else sl <= entry

    private fun withQty(req: OrderRequest, qty: BigDecimal): OrderRequest =
        when (req) {
            is OrderRequest.Market -> req.copy(quantity = qty)
            is OrderRequest.Limit -> req.copy(quantity = qty)
            is OrderRequest.Stop -> req.copy(quantity = qty)
            else -> req
        }
}
```

Note on `asNum()`/`.value`: use the same `Value.Num` access the existing compilers use (see `OrderTypeCompiler.compileStopLimit`: `(stopEval.evaluate(ec) as Value.Num).v`). Match that exact accessor; the snippet's `asNum()` is shorthand for it. `NumLit`'s value accessor: confirm whether it is `.value` or `.v` by reading `ExprAst.kt` and match it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.LatchCompilerTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire `Latch` into `ActionCompiler` dispatch**

`Latch` does not emit orders at rule-fire — it arms. In `ActionCompiler.compile(action)`'s `when`, add a branch that returns a single arming signal:

```kotlin
        is Latch -> { ec -> listOf(Signal.ArmLatch(latchCompiler.compile(action, strategyId))) }
```

This requires: (a) a `LatchCompiler` collaborator injected into `ActionCompiler` (construct it alongside the existing compilers); (b) a new `Signal.ArmLatch(compiled: CompiledLatch)` variant (Task 4 adds it). For now, add the dispatch branch and the `LatchCompiler` field; the `Signal.ArmLatch` type lands in Task 4. If compiling the dispatch before Task 4 fails on the missing `Signal.ArmLatch`, do Task 4 Step 1-2 first, then return here. (They are tightly coupled — implement Task 3 Step 5 and Task 4 together.)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/LatchCompiler.kt src/test/kotlin/com/qkt/dsl/compile/LatchCompilerTest.kt src/test/kotlin/com/qkt/dsl/compile/LatchCompilerFixture.kt src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt
git commit -m "feat: compile LATCH entries into direction-relative order builders (#264)"
```

---

## Task 4: `LatchManager` engine + wiring

**Files:**
- Create: `src/main/kotlin/com/qkt/app/LatchManager.kt`
- Modify: the `Signal` sealed type (find it: `grep -rn 'sealed.*Signal' src/main/kotlin`) — add `ArmLatch`
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt` (construct + subscribe `LatchManager`; route `ArmLatch`)
- Test: `src/test/kotlin/com/qkt/app/LatchManagerTest.kt`

- [ ] **Step 1: Add the `Signal.ArmLatch` variant**

Locate the `Signal` sealed type (`grep -rn 'sealed interface Signal\|sealed class Signal' src/main/kotlin`). Add:

```kotlin
    data class ArmLatch(
        val compiled: com.qkt.dsl.compile.CompiledLatch,
    ) : Signal
```

In the pipeline's `emit`/signal-routing (`TradingPipeline.init`, the lambda around lines 171-173 that calls `sig.toOrderRequest(...)`), branch on the signal type: a `Signal.ArmLatch` routes to `latchManager.arm(sig.compiled, ec, strategyId)` instead of `toOrderRequest`. Read the existing routing closure and add the branch in the same style (the other signals stay unchanged).

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/com/qkt/app/LatchManagerTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.execution.OrderRequest
import com.qkt.execution.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchManagerTest {
    @Test
    fun `up-break fans out the entries as BUY orders`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        // ref 2000.0, offset 0.50 -> up=2000.5, down=1999.5; 5-min window; one limit builder
        mgr.arm(LatchManagerFixture.compiledLatch(ref = "2000.0", offset = "0.50", windowMs = 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 2_000L)) // crosses up
        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `down-break fans out as SELL`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "1999.4", 2_000L))
        assertThat(emitted.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `no cross within the arm window emits nothing and drops the latch`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.0", 1_000L + 300_001L)) // past expiry, no cross
        assertThat(emitted).isEmpty()
        // a later in-range cross does nothing — the latch is gone
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 1_000L + 300_002L))
        assertThat(emitted).isEmpty()
    }
}
```

Create `LatchManagerFixture` (test-only): `manager(emit, now)` builds a `LatchManager` with a fixed `Clock`; `ec(symbol)` builds a minimal `EvalContext` whose candle carries that symbol; `compiledLatch(ref, offset, windowMs)` builds a `CompiledLatch` whose `reference`/`offset` are constant `CompiledExpr`s evaluating to those numbers and whose single `entryBuilder` returns an `OrderRequest.Limit` of the resolved side (BUY if direction>0 else SELL). The fixture bypasses `LatchCompiler` so this test isolates the manager's arm/cross/expire logic — `arm()` still computes `up`/`down` from the constant ref/offset via the real path.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.app.LatchManagerTest'`
Expected: FAIL — `LatchManager` unresolved.

- [ ] **Step 4: Implement `LatchManager`**

Create `src/main/kotlin/com/qkt/app/LatchManager.kt`:

```kotlin
package com.qkt.app

import com.qkt.common.Clock
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.EvalContext
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Holds armed latches and resolves them on ticks. A latch arms two price wires
 * (`ref ± offset`); the first wire a tick crosses sets the direction (+1 up / -1
 * down) and anchor `O`, then the latch's entry builders fan out concrete orders
 * via [emit]. If no wire is crossed before the arm window elapses, the latch is
 * dropped with no orders. Armed latches are transient (not persisted): a restart
 * mid-arm drops them and the strategy re-arms next session.
 */
class LatchManager(
    private val emit: (OrderRequest) -> Unit,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(LatchManager::class.java)

    private data class ArmedLatch(
        val symbol: String,
        val up: BigDecimal,
        val down: BigDecimal,
        val expiresAt: Long,
        val compiled: CompiledLatch,
        val ec: EvalContext,
    )

    private val armed = mutableListOf<ArmedLatch>()

    fun arm(
        compiled: CompiledLatch,
        ec: EvalContext,
        now: Long = clock.now(),
    ) {
        val ref = compiled.reference.evaluate(ec).asNum()
        val off = compiled.offset.evaluate(ec).asNum()
        armed.add(
            ArmedLatch(
                symbol = ec.candle.symbol,
                up = ref + off,
                down = ref - off,
                expiresAt = now + compiled.armWindowMs,
                compiled = compiled,
                ec = ec,
            ),
        )
    }

    fun onTick(tick: Tick) {
        if (armed.isEmpty()) return
        val it = armed.iterator()
        while (it.hasNext()) {
            val latch = it.next()
            if (latch.symbol != tick.symbol) continue
            if (tick.timestamp >= latch.expiresAt) {
                it.remove()
                continue
            }
            val direction =
                when {
                    tick.price >= latch.up -> 1
                    tick.price <= latch.down -> -1
                    else -> 0
                }
            if (direction == 0) continue
            val anchor = if (direction > 0) latch.up else latch.down
            fire(latch, direction, anchor)
            it.remove()
        }
    }

    private fun fire(
        latch: ArmedLatch,
        direction: Int,
        anchor: BigDecimal,
    ) {
        latch.compiled.entryBuilders.forEach { b ->
            b.build(direction, anchor, latch.ec)?.let(emit)
        }
    }
}
```

(Match the `Value.Num` accessor used elsewhere for `.asNum()`; replace with `(… as Value.Num).v` as the codebase does.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.app.LatchManagerTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Wire `LatchManager` into `TradingPipeline`**

In `TradingPipeline` (after `orderManager` is constructed, ~line 104): construct a `LatchManager` whose `emit` runs the **same** risk-approve → `OrderEvent` path the normal signal emit uses (so latch-fired orders are risk-checked exactly like rule-fired ones — reuse the `rawEmit`/approve closure, not a direct `orderManager.submit`). Subscribe it to ticks alongside the other subscribers:

```kotlin
bus.subscribe<TickEvent> { e -> latchManager.onTick(e.tick) }
```

And in the signal-routing closure (Task 4 Step 1), route `Signal.ArmLatch` → `latchManager.arm(sig.compiled, ec)`. Read the surrounding `init` block and match the existing wiring style; the `clock`, `bus`, and the approve/emit closure are all in scope there.

- [ ] **Step 7: Run the app package tests**

Run: `./gradlew test --tests 'com.qkt.app.LatchManagerTest' --tests 'com.qkt.app.TradingPipeline*'`
Expected: PASS (manager tests + any existing pipeline tests still green).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LatchManager.kt src/main/kotlin/com/qkt/app/TradingPipeline.kt src/test/kotlin/com/qkt/app/LatchManagerTest.kt src/test/kotlin/com/qkt/app/LatchManagerFixture.kt
git add $(grep -rl 'sealed interface Signal\|sealed class Signal' src/main/kotlin)
git commit -m "feat: LatchManager arms and fires latches on ticks (#264)"
```

---

## Task 5: End-to-end backtest + docs + example

**Files:**
- Test: `src/test/kotlin/com/qkt/app/LatchBacktestTest.kt`
- Create: `docs/reference/dsl/latch.md`
- Create: `examples/latch-stack/latch-stack.qkt`

- [ ] **Step 1: Write the end-to-end backtest (real ticks, no mocks)**

Create `src/test/kotlin/com/qkt/app/LatchBacktestTest.kt` modeled on an existing backtest test (find one: `grep -rln 'Backtest' src/test/kotlin/com/qkt/app | head`). It should:
1. Compile a latch strategy from source text (a `:00` arm, `OFFSET 0.50 ARM 5m`, one `ENTER LIMIT RETRACE 4 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING 1`).
2. Drive a synthetic tick series that: opens at 2000.00 → rises to 2000.60 (crosses the up-wire) → pulls back to 1996.40 (fills the limit at 1996.50) → rises to 2005.60 (hits the take-profit).
3. Assert: a BUY position opened at ~1996.50, then closed at the 2005.50 target with positive PnL.
4. A second case: ticks that never reach 2000.50 → assert no position opened.

Read the chosen reference backtest test for the exact harness (how to feed ticks, how to read resulting positions/PnL) and mirror it. Use a backtest instrument with a known contract size.

- [ ] **Step 2: Run it to verify it fails, then iterate to green**

Run: `./gradlew test --tests 'com.qkt.app.LatchBacktestTest'`
Expected: first FAIL (assertions unmet or wiring incomplete), then iterate the wiring from Tasks 3-4 until it PASSES. This is the integration proof that arming → cross → fan-out → fill → bracket-exit works through the real pipeline.

- [ ] **Step 3: Write the DSL reference doc**

Create `docs/reference/dsl/latch.md` documenting: the grammar, the `WITH`/`AGAINST`/`RETRACE` sign rule (with the long/short table from the spec), the worked $2000 example, `ENTER MARKET|LIMIT|STOP`, `ARM`/`EXPIRE`/`FROM`/`AS`, the tiebreak and inverted-geometry rules, and the transient-arm (no-persistence) caveat. Mirror the structure of `docs/reference/dsl/stack.md`. Add a nav entry in `mkdocs.yml` next to the other `dsl/` reference pages if the nav lists them explicitly.

- [ ] **Step 4: Write the example strategy**

Create `examples/latch-stack/latch-stack.qkt` — the full latch-stack from the spec (the 3-rung `ENTER LIMIT RETRACE near|mid|deep` ladder with shared bracket + sizing), with a header comment explaining the mechanism. Verify it parses:

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserLatchTest'` after adding a test case that loads `examples/latch-stack/latch-stack.qkt` and asserts it parses to a `Latch` with 3 entries. (Or a dedicated `qkt parse examples/latch-stack/latch-stack.qkt` smoke if a CLI test harness exists.)

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/qkt/app/LatchBacktestTest.kt docs/reference/dsl/latch.md examples/latch-stack/latch-stack.qkt mkdocs.yml
git commit -m "test: end-to-end latch backtest; docs and example (#264)"
```

---

## Final verification

- [ ] **Push and open the PR**

```bash
git push -u origin issue264-latch-primitive
gh pr create --base dev --title "LATCH primitive: composable directional trigger (#264)" --body "Refs #264. Implements LATCH per docs/superpowers/specs/2026-06-05-latch-primitive-design.md: directional-trigger entry primitive (arm wires, first-break resolves direction+anchor, fan out direction-relative entries). Approach 2 (open entry block); transient arm; Approach-3 signal accessors deferred."
```

Expected: `check` (build + ktlint + unit tests) green. Merge to `dev` once green.

---

## Notes for the implementer

- **ktlint:** blank line before standalone `//` comments (recurring CI failure in this repo).
- **`Value.Num` accessor:** the snippets use `.asNum()` / `.value` as shorthand — match the actual accessor the codebase uses (`(expr.evaluate(ec) as Value.Num).v` per `OrderTypeCompiler`; `NumLit`'s field per `ExprAst.kt`). Verify before relying on it.
- **Tasks 3 Step 5 and 4 Step 1 are coupled** (the `Signal.ArmLatch` type + the `ActionCompiler` dispatch + the pipeline routing land together). If a subagent does them separately, expect one compile failure bridging them — resolve by completing both.
- **Distances must be compile-time constants** (literals or `LET`), because the static stop distance is computed at compile time. A `RETRACE gold.close` (a runtime expression) is rejected with a clear error — that's intended for v1; document it in `latch.md`.
- **Don't touch** the untracked `docs/superpowers/plans/2026-05-26-issue139-multi-mt5.md`.
- **CI over local full build:** run the targeted `--tests` filters; let `check.yml` run the whole suite.
