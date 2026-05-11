# Phase 26 — Pending-entry OCO and clock accessors · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add DSL surface for entry-pair OCO (`OCO_ENTRY { ... }`) and DSL-visible clock accessors (`NOW.<field>`), plus verify MT5 broker routes `StandaloneOCO` correctly. The execution-layer primitive (`OrderRequest.StandaloneOCO`) already exists; this phase exposes it to the DSL.

**Architecture:** Two new AST nodes (`OcoEntry`, `NowAccessor`), additive parser/compiler paths, broker integration verification. No changes to the position model, no new strategy runtime concepts. Pending mode hedge-straddle becomes expressible in qkt; stacking ships in Phase 27 (separate spec).

**Tech stack:** Kotlin (single module), JUnit 5 + AssertJ. No new dependencies.

> Note on Kotlin snippets in this plan: `expr.evaluate(ctx)` stands for whatever the codebase's compiled-expression invocation method is named. Map to actual method by reading `ExprCompiler.kt` and neighboring tests.

---

### Task 1 — Verify spec assumptions in source

**Files:**
- Read: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Read: `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`
- Read: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Read: `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt`
- Read: `src/main/kotlin/com/qkt/dsl/parse/{Lexer,Parser,TokenKind}.kt`
- Read: `src/main/kotlin/com/qkt/dsl/compile/{ActionCompiler,ExprCompiler,DefaultsMerge}.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/Mt5Broker.kt`

- [ ] **Step 1: Confirm `StandaloneOCO` shape at `OrderRequest.kt:184`.**

```bash
grep -n 'StandaloneOCO\|class.*StandaloneOCO\|data class.*OCO' src/main/kotlin/com/qkt/execution/OrderRequest.kt
```

Expected: `data class StandaloneOCO(id, symbol, side, quantity, leg1: OrderRequest, leg2: OrderRequest, tif, timestamp, strategyId)`.

- [ ] **Step 2: Confirm the existing post-fill `OcoAst` at `ActionOpts.kt:90`.**

```bash
grep -n 'OcoAst\|class OcoAst' src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt
```

Expected: `data class OcoAst(stop: ChildPriceAst, limit: ChildPriceAst)`. Different concept than entry OCO — leave untouched.

- [ ] **Step 3: Confirm parser entry-points.**

```bash
grep -n 'fun parseAction\|internal fun parseAction\|fun parsePrimary' src/main/kotlin/com/qkt/dsl/parse/Parser.kt
```

Expected: `parseAction()` is the action dispatcher; primary-expression handler is in `parseAtom` or similar.

- [ ] **Step 4: Confirm EvalContext gives compiled expressions access to a Clock.**

```bash
grep -n 'class EvalContext\|clock\|Clock' src/main/kotlin/com/qkt/dsl/compile/EvalContext.kt
```

Expected: a `clock` field (or accessible from `strategyContext.clock`).

- [ ] **Step 5: Confirm MT5 broker handles `StandaloneOCO`.**

```bash
grep -rn 'StandaloneOCO\|when.*request' src/main/kotlin/com/qkt/broker/mt5/
```

Note whether `StandaloneOCO` is routed today, or hits a `TODO`/`error("not supported")`. This determines Task 14 scope.

- [ ] **Step 6: Run the existing test suite as baseline.**

```bash
./gradlew test --no-daemon
```

Expected: all green.

---

### Task 2 — Add NOW and OCO_ENTRY tokens

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

- [ ] **Step 1: Add to `TokenKind` enum.**

```kotlin
NOW,
OCO_ENTRY,
```

- [ ] **Step 2: Write the failing test.**

In `LexerTest.kt`:

```kotlin
@Test
fun `NOW and OCO_ENTRY are case-insensitive keywords`() {
    val tokens = Lexer("NOW now Now OCO_ENTRY oco_entry").tokenize()
    assertThat(tokens.dropLast(1).map { it.kind }).containsExactly(
        TokenKind.NOW, TokenKind.NOW, TokenKind.NOW,
        TokenKind.OCO_ENTRY, TokenKind.OCO_ENTRY,
    )
}
```

- [ ] **Step 3: Run the test, confirm it passes** (the `KEYWORDS` table auto-includes all enum names via `lex.uppercase()` lookup — no `Lexer.kt` change required).

```bash
./gradlew test --tests 'com.qkt.dsl.parse.LexerTest' --no-daemon
```

- [ ] **Step 4: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): add NOW and OCO_ENTRY tokens"
```

---

### Task 3 — Add NowAccessor AST node

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserNowTest.kt` (new)

- [ ] **Step 1: Add to `ExprAst.kt`.**

```kotlin
data class NowAccessor(
    val field: NowField,
) : ExprAst

enum class NowField {
    HOUR_UTC,
    MINUTE_UTC,
    WEEKDAY,
    DATE_UTC,
    EPOCH_MS,
}
```

- [ ] **Step 2: Create failing test file.**

`src/test/kotlin/com/qkt/dsl/parse/ParserNowTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserNowTest {
    private fun parseExprInLet(s: String): com.qkt.dsl.ast.ExprAst {
        val r = Parser(Lexer("STRATEGY x VERSION 1\nLET v = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `NOW dot hour_utc parses to NowAccessor`() {
        val e = parseExprInLet("NOW.hour_utc") as NowAccessor
        assertThat(e.field).isEqualTo(NowField.HOUR_UTC)
    }

    @Test
    fun `NOW field names are case-insensitive`() {
        val e = parseExprInLet("now.HOUR_UTC") as NowAccessor
        assertThat(e.field).isEqualTo(NowField.HOUR_UTC)
    }

    @Test
    fun `each NOW field parses`() {
        assertThat((parseExprInLet("NOW.hour_utc") as NowAccessor).field).isEqualTo(NowField.HOUR_UTC)
        assertThat((parseExprInLet("NOW.minute_utc") as NowAccessor).field).isEqualTo(NowField.MINUTE_UTC)
        assertThat((parseExprInLet("NOW.weekday") as NowAccessor).field).isEqualTo(NowField.WEEKDAY)
        assertThat((parseExprInLet("NOW.date_utc") as NowAccessor).field).isEqualTo(NowField.DATE_UTC)
        assertThat((parseExprInLet("NOW.epoch_ms") as NowAccessor).field).isEqualTo(NowField.EPOCH_MS)
    }

    @Test
    fun `unknown NOW field fails parse`() {
        val r = Parser(Lexer("STRATEGY x VERSION 1\nLET v = NOW.banana").tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }
}
```

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserNowTest' --no-daemon`. Expected: all four fail with parse errors (NOW dispatch not wired yet).

---

### Task 4 — Parser: NOW.<field> primary expression

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`

- [ ] **Step 1: Find the primary-expression dispatch.**

```bash
grep -n 'parseAtom\|parsePrimary\|TokenKind.IDENT\|parseExprAtom' src/main/kotlin/com/qkt/dsl/parse/Parser.kt | head -5
```

Locate the `when` block that handles identifiers and literals.

- [ ] **Step 2: Add NOW branch before the IDENT branch.**

```kotlin
TokenKind.NOW -> {
    advance() // consume NOW
    expect(TokenKind.DOT, "expected '.' after NOW")
    val fieldTok = expect(TokenKind.IDENT, "expected NOW field name")
    val field = when (fieldTok.lexeme.uppercase()) {
        "HOUR_UTC" -> NowField.HOUR_UTC
        "MINUTE_UTC" -> NowField.MINUTE_UTC
        "WEEKDAY" -> NowField.WEEKDAY
        "DATE_UTC" -> NowField.DATE_UTC
        "EPOCH_MS" -> NowField.EPOCH_MS
        else -> error("unknown NOW field '${fieldTok.lexeme}', expected one of: hour_utc, minute_utc, weekday, date_utc, epoch_ms")
    }
    NowAccessor(field)
}
```

- [ ] **Step 3: Add imports.**

```kotlin
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
```

- [ ] **Step 4: Re-run the Task 3 tests.**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserNowTest' --no-daemon
```

Expected: all four pass.

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserNowTest.kt
git commit -m "feat(dsl): NOW.<field> clock accessors"
```

---

### Task 5 — Compiler: NowAccessor → CompiledExpr

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/NowAccessorEvalTest.kt` (new)

- [ ] **Step 1: Find where ExprCompiler dispatches AST nodes.**

```bash
grep -n 'fun compile\|when.*expr\|is StreamFieldRef\|is IndicatorCall' src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt | head -10
```

- [ ] **Step 2: Add NowAccessor branch.**

```kotlin
is NowAccessor -> compileNow(expr.field)
```

```kotlin
private fun compileNow(field: NowField): CompiledExpr = CompiledExpr { ctx ->
    val nowMs = ctx.strategyContext.clock.now()
    when (field) {
        NowField.EPOCH_MS -> Value.Num(BigDecimal.valueOf(nowMs))
        else -> {
            val instant = java.time.Instant.ofEpochMilli(nowMs)
            val z = instant.atZone(java.time.ZoneOffset.UTC)
            val n = when (field) {
                NowField.HOUR_UTC -> z.hour
                NowField.MINUTE_UTC -> z.minute
                NowField.WEEKDAY -> z.dayOfWeek.value - 1 // Monday = 0
                NowField.DATE_UTC -> z.toLocalDate().toEpochDay().toInt()
                NowField.EPOCH_MS -> error("handled above")
            }
            Value.Num(BigDecimal.valueOf(n.toLong()))
        }
    }
}
```

(Adapt to the project's actual `CompiledExpr` / `Value` shape — confirm by reading neighboring `compileStreamField` or `compileIndicator`.)

- [ ] **Step 3: Write evaluation test.**

`src/test/kotlin/com/qkt/dsl/compile/NowAccessorEvalTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NowAccessorEvalTest {
    @Test
    fun `NOW hour_utc returns the UTC hour from FixedClock`() {
        // 2026-05-11 13:45:00 UTC = 1778857500000 ms
        val clock = FixedClock(1778857500000L)
        val ctx = evalContextWithClock(clock)
        val compiled = ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag())
            .compile(NowAccessor(NowField.HOUR_UTC))
        assertThat(compiled.evaluate(ctx).asBigDecimal()).isEqualByComparingTo("13")
    }

    @Test
    fun `each field returns the expected projection`() {
        val clock = FixedClock(1778857500000L) // Mon 2026-05-11 13:45:00 UTC
        val ctx = evalContextWithClock(clock)
        val ec = ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag())
        assertThat(ec.compile(NowAccessor(NowField.HOUR_UTC)).evaluate(ctx).asBigDecimal())
            .isEqualByComparingTo("13")
        assertThat(ec.compile(NowAccessor(NowField.MINUTE_UTC)).evaluate(ctx).asBigDecimal())
            .isEqualByComparingTo("45")
        assertThat(ec.compile(NowAccessor(NowField.WEEKDAY)).evaluate(ctx).asBigDecimal())
            .isEqualByComparingTo("0") // Monday = 0
        assertThat(ec.compile(NowAccessor(NowField.EPOCH_MS)).evaluate(ctx).asBigDecimal())
            .isEqualByComparingTo("1778857500000")
    }

    // Helper — adapt to actual project EvalContext construction
    private fun evalContextWithClock(clock: FixedClock): EvalContext { TODO() }
}
```

- [ ] **Step 4: Run test, fix helper to construct an EvalContext with the given clock (mirror existing tests), confirm green.**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.NowAccessorEvalTest' --no-daemon
```

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/NowAccessorEvalTest.kt
git commit -m "feat(dsl): compile NOW.<field> against StrategyContext clock"
```

---

### Task 6 — Plumb NowAccessor through DefaultsMerge + IterVarSubstitution

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/DefaultsMerge.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/parse/IterVarSubstitution.kt`

- [ ] **Step 1: Confirm NowAccessor doesn't contain stream refs (it doesn't).** Both visitor functions just need an `is NowAccessor -> expr` pass-through in their `when` branches.

- [ ] **Step 2: Run full DSL parser suite.**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.*' --tests 'com.qkt.dsl.compile.*' --no-daemon
```

Expected: green; the `else -> expr` arm already handles unrecognized expression types, but adding the explicit case prevents future drift.

- [ ] **Step 3: Commit (only if a change was actually needed after Step 1).**

---

### Task 7 — Parser: NOW + duration → epoch-ms expression

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (additive-expression rule)
- Test: extend `ParserNowTest.kt`

- [ ] **Step 1: Confirm Duration literals lex as `TokenKind.DURATION` with values like `10m`, `2h`.**

```bash
grep -n 'TokenKind.DURATION\|DURATION,' src/main/kotlin/com/qkt/dsl/parse/Parser.kt | head -3
```

- [ ] **Step 2: Find the additive-expression parse.**

```bash
grep -n 'parseAddExpr\|parseAdd' src/main/kotlin/com/qkt/dsl/parse/Parser.kt | head
```

- [ ] **Step 3: Decide on the representation.**

The cleanest path: `NOW + 10m` parses as `BinaryOp(ADD, NowAccessor(EPOCH_MS), NumLit(600_000))` where the duration literal is canonicalized to its millisecond value during parsing. This lets the existing arithmetic compile path handle it without a special case.

Where's duration canonicalization? `grep -n 'parseDuration\|DURATION ->' src/main/kotlin/com/qkt/dsl/parse/Parser.kt`. The duration parsing path already converts to ms (used by TIF GTD WITHIN). Reuse it.

- [ ] **Step 4: Add test in `ParserNowTest.kt`.**

```kotlin
@Test
fun `NOW plus duration evaluates to epoch_ms plus duration ms`() {
    val src = """
        STRATEGY x VERSION 1
        LET deadline = NOW + 10m
    """.trimIndent()
    val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success
    val expr = r.value.lets[0].expr
    // The exact AST shape depends on representation choice; assert behavior via eval test in Task 8
    assertThat(expr).isInstanceOf(com.qkt.dsl.ast.BinaryOp::class.java)
}
```

- [ ] **Step 5: If `NOW + DURATION` doesn't parse out of the box, modify primary-expression to coerce a bare `NOW` to `NowAccessor(EPOCH_MS)`, and let the additive parser handle the binary op.**

Cleanest: in `parseAtom`, when `NOW` is followed by `.`, branch to `NOW.<field>`. When followed by `+`/`-`, treat bare `NOW` as `NowAccessor(EPOCH_MS)`.

- [ ] **Step 6: Confirm tests pass.**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserNowTest' --no-daemon
```

- [ ] **Step 7: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserNowTest.kt
git commit -m "feat(dsl): NOW + duration arithmetic for relative deadlines"
```

---

### Task 8 — End-to-end NOW + duration evaluation test

**Files:**
- Extend: `src/test/kotlin/com/qkt/dsl/compile/NowAccessorEvalTest.kt`

- [ ] **Step 1: Add test.**

```kotlin
@Test
fun `NOW plus 10m compiled value is clock_now plus 600000ms`() {
    val nowMs = 1778857500000L
    val clock = FixedClock(nowMs)
    val ctx = evalContextWithClock(clock)
    val src = "STRATEGY x VERSION 1\nLET deadline = NOW + 10m"
    val ast = (Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success).value
    val compiled = ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag())
        .compile(ast.lets[0].expr)
    assertThat(compiled.evaluate(ctx).asBigDecimal())
        .isEqualByComparingTo(BigDecimal.valueOf(nowMs + 600_000L))
}
```

- [ ] **Step 2: Run, confirm green.**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.NowAccessorEvalTest' --no-daemon
```

---

### Task 9 — AST: OcoEntry

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt`

- [ ] **Step 1: Add `OcoEntry` to `RuleAst.kt`.**

```kotlin
data class OcoEntry(
    val leg1: ActionAst, // Buy or Sell
    val leg2: ActionAst, // Buy or Sell
) : ActionAst
```

No test yet; tested via parser tests in Task 10.

---

### Task 10 — Parser: OCO_ENTRY { leg1, leg2 }

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserOcoEntryTest.kt` (new)

- [ ] **Step 1: Write failing tests.**

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserOcoEntryTest {
    private fun parsedRule(actionDsl: String) =
        (Parser(Lexer("""
            STRATEGY x VERSION 1
            SYMBOLS gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN $actionDsl
        """.trimIndent()).tokenize()).parseStrategy() as ParseResult.Success).value.rules[0] as WhenThen

    @Test
    fun `OCO_ENTRY with BUY and SELL parses as OcoEntry`() {
        val r = parsedRule(
            """OCO_ENTRY {
                BUY  gold SIZING 0.20 STOP AT gold.close + 5,
                SELL gold SIZING 0.20 STOP AT gold.close - 5
            }"""
        )
        val oco = r.action as OcoEntry
        assertThat(oco.leg1).isInstanceOf(Buy::class.java)
        assertThat(oco.leg2).isInstanceOf(Sell::class.java)
    }

    @Test
    fun `OCO_ENTRY with one leg fails parse`() {
        val r = Parser(Lexer("""
            STRATEGY x VERSION 1
            SYMBOLS gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN OCO_ENTRY { BUY gold SIZING 0.1 }
        """.trimIndent()).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `OCO_ENTRY containing LOG fails parse`() {
        val r = Parser(Lexer("""
            STRATEGY x VERSION 1
            SYMBOLS gold = EXNESS:XAUUSD EVERY 5m
            RULES
                WHEN gold.close > 0 THEN OCO_ENTRY {
                    BUY gold SIZING 0.1,
                    LOG "fired"
                }
        """.trimIndent()).tokenize()).parseStrategy()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }

    @Test
    fun `OCO_ENTRY preserves per-leg BRACKET`() {
        val r = parsedRule(
            """OCO_ENTRY {
                BUY  gold SIZING 0.20 STOP AT gold.close + 5
                     BRACKET { STOP_LOSS BY 18, TAKE_PROFIT BY 15 },
                SELL gold SIZING 0.20 STOP AT gold.close - 5
                     BRACKET { STOP_LOSS BY 18, TAKE_PROFIT BY 15 }
            }"""
        )
        val oco = r.action as OcoEntry
        assertThat((oco.leg1 as Buy).opts.bracket).isNotNull
        assertThat((oco.leg2 as Sell).opts.bracket).isNotNull
    }
}
```

- [ ] **Step 2: Add `OCO_ENTRY` branch to `parseAction()`.**

```kotlin
TokenKind.OCO_ENTRY -> parseOcoEntry()
```

```kotlin
private fun parseOcoEntry(): ActionAst {
    advance() // consume OCO_ENTRY
    expect(TokenKind.LBRACE, "expected '{' after OCO_ENTRY")
    val leg1 = parseAction()
    require(leg1 is Buy || leg1 is Sell) {
        "OCO_ENTRY children must be BUY or SELL, got ${leg1::class.simpleName}"
    }
    expect(TokenKind.COMMA, "expected ',' between OCO_ENTRY legs")
    val leg2 = parseAction()
    require(leg2 is Buy || leg2 is Sell) {
        "OCO_ENTRY children must be BUY or SELL, got ${leg2::class.simpleName}"
    }
    expect(TokenKind.RBRACE, "expected '}' to close OCO_ENTRY (exactly two legs allowed)")
    return OcoEntry(leg1, leg2)
}
```

- [ ] **Step 3: Run the new tests.**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserOcoEntryTest' --no-daemon
```

Expected: all pass.

- [ ] **Step 4: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserOcoEntryTest.kt
git commit -m "feat(dsl): OCO_ENTRY { ... } two-leg pending-entry syntax"
```

---

### Task 11 — Plumb OcoEntry through DefaultsMerge, IterVarSubstitution, AstCompiler

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/DefaultsMerge.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/parse/IterVarSubstitution.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`

- [ ] **Step 1: DefaultsMerge — recursively merge into both legs.**

```kotlin
is OcoEntry -> OcoEntry(
    leg1 = mergeDefaults(action.leg1, defaults),
    leg2 = mergeDefaults(action.leg2, defaults),
)
```

- [ ] **Step 2: IterVarSubstitution — substitute in both legs.**

```kotlin
is OcoEntry -> OcoEntry(
    leg1 = subst(action.leg1, v, alias),
    leg2 = subst(action.leg2, v, alias),
)
```

- [ ] **Step 3: AstCompiler — primary action for stream-alias resolution.**

Update the `primary` extraction (added during the multi-action `Block` work) to recurse into `OcoEntry`:

```kotlin
val primary: ActionAst = when (val a = rule.action) {
    is Block -> a.actions.firstOrNull { it !is Log } ?: a.actions.first()
    is OcoEntry -> a.leg1 // both legs are on the same stream in typical usage; use leg1
    else -> a
}
```

- [ ] **Step 4: Run full DSL test suite.**

```bash
./gradlew test --tests 'com.qkt.dsl.*' --no-daemon
```

Expected: green.

- [ ] **Step 5: Commit.**

```bash
git commit -am "feat(dsl): OcoEntry plumbed through defaults, foreach, and ast compile"
```

---

### Task 12 — ActionCompiler: OcoEntry → Signal.Submit(StandaloneOCO)

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/OcoEntryCompileTest.kt` (new)

- [ ] **Step 1: Add `is OcoEntry -> compileOcoEntry(action)` to the `compile()` dispatch.**

- [ ] **Step 2: Implement `compileOcoEntry`.**

```kotlin
private fun compileOcoEntry(action: OcoEntry): (EvalContext) -> List<Signal> {
    val leg1Compiled = compile(action.leg1)
    val leg2Compiled = compile(action.leg2)
    return { ctx ->
        val sigs1 = leg1Compiled(ctx)
        val sigs2 = leg2Compiled(ctx)
        // Extract the OrderRequest from each leg's Signal.Submit
        val req1 = (sigs1.singleOrNull() as? Signal.Submit)?.request
            ?: error("OCO_ENTRY leg1 must compile to exactly one Signal.Submit")
        val req2 = (sigs2.singleOrNull() as? Signal.Submit)?.request
            ?: error("OCO_ENTRY leg2 must compile to exactly one Signal.Submit")
        val oco = OrderRequest.StandaloneOCO(
            id = ids.next(),
            symbol = req1.symbol,
            side = req1.side, // arbitrary — OCO has two sides, this field is informational
            quantity = req1.quantity,
            leg1 = req1,
            leg2 = req2,
            timeInForce = req1.timeInForce,
            timestamp = ctx.strategyContext.clock.now(),
        )
        listOf(Signal.Submit(oco))
    }
}
```

(Adapt to the actual `Signal.Submit` constructor — confirm by reading existing `compileBuySell`.)

- [ ] **Step 3: Write compile test.**

```kotlin
@Test
fun `OcoEntry compiles to a single Signal Submit wrapping StandaloneOCO`() {
    val src = """
        STRATEGY x VERSION 1
        SYMBOLS gold = BACKTEST:XAUUSD EVERY 5m
        RULES
            WHEN gold.close > 0 THEN OCO_ENTRY {
                BUY  gold SIZING 0.2 STOP AT gold.close + 5,
                SELL gold SIZING 0.2 STOP AT gold.close - 5
            }
    """.trimIndent()
    val strategy = AstCompiler().compile(
        (Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success).value
    )
    // Drive a candle, capture signals, assert exactly one Signal.Submit(StandaloneOCO)
    // (full integration shape depends on existing test harness)
}
```

- [ ] **Step 4: Run, confirm green.**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.OcoEntryCompileTest' --no-daemon
```

- [ ] **Step 5: Commit.**

```bash
git commit -am "feat(dsl): compile OcoEntry to Signal.Submit(StandaloneOCO)"
```

---

### Task 13 — Backtest fidelity: mock broker StandaloneOCO behavior

**Files:**
- Read: `src/main/kotlin/com/qkt/broker/mock/MockBroker.kt` (or equivalent)
- Test: `src/test/kotlin/com/qkt/broker/mock/StandaloneOcoFidelityTest.kt` (new)

- [ ] **Step 1: Confirm mock broker handles StandaloneOCO.**

```bash
grep -rn 'StandaloneOCO' src/main/kotlin/com/qkt/broker/mock/
```

If routing exists: write the fidelity test below. If it doesn't: this becomes a Phase 26 in-scope fix.

- [ ] **Step 2: Write test.**

```kotlin
@Test
fun `OCO leg1 fills then leg2 auto-cancels`() {
    val broker = MockBroker()
    val oco = OrderRequest.StandaloneOCO(/* … */)
    broker.submit(oco)
    // Drive a candle that triggers leg1's stop, not leg2's
    broker.onCandle(candleAt(price = leg1TriggerPrice))
    val fills = broker.recentFills()
    assertThat(fills).hasSize(1)
    assertThat(fills[0].orderId).isEqualTo(oco.leg1.id)
    assertThat(broker.workingOrders()).isEmpty() // leg2 cancelled
}

@Test
fun `OCO both expire when neither leg triggers and GTD elapses`() { /* … */ }

@Test
fun `OCO same-bar dual breach picks the leg with the closer trigger to candle open`() {
    // Document the tiebreak rule (spec Open Question 1) and pin it with a test
}
```

- [ ] **Step 3: Run, fix any mock-broker bugs surfaced, commit.**

---

### Task 14 — MT5 broker StandaloneOCO verification

**Files:**
- Read: `src/main/kotlin/com/qkt/broker/mt5/Mt5Broker.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/Mt5OcoTest.kt` (new)

- [ ] **Step 1: Verify the MT5 broker translates `StandaloneOCO` into two `BUY_STOP`/`SELL_STOP` MT5 order requests.**

If today's routing rejects or drops StandaloneOCO, add the translation:
- Submit leg1 as MT5 pending stop order, capture ticket.
- Submit leg2 as MT5 pending stop order, capture ticket.
- Tag both with the same `groupId` in `ManagedOrder`.

- [ ] **Step 2: Add cancel-on-fill behavior in `OrderManager` or Mt5Broker — when a fill event arrives for one ticket, look up its `groupId`, find the sibling, submit a `cancelOrder` for the sibling.**

- [ ] **Step 3: Integration test using fake MT5 client.**

```kotlin
@Test
fun `MT5 broker submits both legs and cancels survivor on fill`() {
    val mt5 = FakeMt5Client()
    val broker = Mt5Broker(mt5)
    broker.submit(OrderRequest.StandaloneOCO(/* … */))
    assertThat(mt5.orderSentCount()).isEqualTo(2)
    mt5.deliverFillFor(ticket = 1001)
    assertThat(mt5.cancelOrderCalls()).contains(1002)
}
```

- [ ] **Step 4: Run, confirm green, commit.**

---

### Task 15 — Docs: NOW page + actions.md OCO_ENTRY section

**Files:**
- Create: `docs/reference/dsl/now.md`
- Modify: `docs/reference/dsl/actions.md`

- [ ] **Step 1: Write `now.md`.**

Sections: shape, available fields (table), relative-deadline form (`NOW + duration`), determinism in backtest, common gotchas (UTC only; broker-local-time deferred).

- [ ] **Step 2: Add `OCO_ENTRY` section to `actions.md`.**

Shape, semantics (one-cancels-other), valid children (BUY/SELL only), per-leg BRACKET, TIF GTD pattern, common gotchas (same-bar dual breach, GTD expiry, capability errors on netting-only brokers).

- [ ] **Step 3: Add link from `streams.md` cross-references to `now.md`.**

- [ ] **Step 4: Build docs locally to verify mkdocs links.**

```bash
mkdocs build --strict
```

Expected: no broken links.

- [ ] **Step 5: Commit.**

```bash
git add docs/reference/dsl/now.md docs/reference/dsl/actions.md
git commit -m "docs(dsl): NOW.<field> page and OCO_ENTRY in actions.md"
```

---

### Task 16 — Worked example: hedge-straddle.qkt

**Files:**
- Create: `examples/hedge-straddle/hedge-straddle.qkt`
- Create: `examples/hedge-straddle/README.md`

- [ ] **Step 1: Author the strategy.**

```qkt
STRATEGY hedge_straddle VERSION 1

SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m

DEFAULTS
    sizing = 0.20

RULES
    -- Session-window pending placement. Place 5 minutes before the hour
    -- start so the pending orders are live when the hour-open candle prints.
    WHEN NOW.hour_utc IN [6, 7, 12, 13, 14, 15]
     AND NOW.minute_utc = 55
     AND POSITION.gold = 0
    THEN OCO_ENTRY {
        BUY  gold STOP AT gold.close + 50 * 0.1
             BRACKET { STOP_LOSS BY 1800, TAKE_PROFIT BY 1500 }
             TIF GTD UNTIL NOW + 10m,
        SELL gold STOP AT gold.close - 50 * 0.1
             BRACKET { STOP_LOSS BY 1800, TAKE_PROFIT BY 1500 }
             TIF GTD UNTIL NOW + 10m
    }

    -- Winner timeout: close after 2 hours regardless of P&L
    WHEN POSITION.gold != 0
     AND POSITION.gold.holding_duration > 7200
    THEN CLOSE gold ; LOG "winner timeout" pnl=POSITION.gold.pnl
```

(Values are illustrative; the production config lives in pa-quant's `config/strategies.ts`. The example proves expressibility.)

- [ ] **Step 2: README with backtest + Docker invocation, plus a note that stacks (Phase 27) are not yet supported and quoting the expected P&L delta.**

- [ ] **Step 3: Confirm it parses against the built `qkt`.**

```bash
./gradlew installDist --no-daemon
./build/install/qkt/bin/qkt parse examples/hedge-straddle/hedge-straddle.qkt
```

Expected: clean parse.

- [ ] **Step 4: Commit.**

```bash
git add examples/hedge-straddle/
git commit -m "docs(examples): hedge-straddle pending-mode reference strategy"
```

---

### Task 17 — Smoke test: parse hedge-straddle example

**Files:**
- Modify: `tests/smoke-install.sh`

- [ ] **Step 1: Add a step that parses the hedge-straddle example.**

```bash
say "Step 5b: parse hedge-straddle example"
"$INSTALL_QKT" parse "$REPO_ROOT/examples/hedge-straddle/hedge-straddle.qkt" >>"$LOG_FILE" 2>&1 \
    || die "hedge-straddle parse failed (log: $LOG_FILE)"
ok "hedge-straddle parses"
```

(No backtest yet — that requires Exness data, which the smoke can't provision without a fetcher run.)

- [ ] **Step 2: Run the full smoke.**

```bash
bash tests/smoke-install.sh
```

Expected: green incl. the new step.

- [ ] **Step 3: Commit.**

---

### Task 18 — Phase 26 changelog

**Files:**
- Create: `docs/phases/phase-26-pending-oco-and-clock.md`
- Modify: `docs/phases/index.md`

- [ ] **Step 1: Write the changelog per the qkt skill §6 template — Summary, What's new (OCO_ENTRY, NOW accessors, GTD-with-NOW, MT5 OCO routing verified), Migration (no breaking changes), Usage cookbook (4 examples: session-gated pending OCO; pending OCO with GTD; expressing winner timeout via holding_duration; the full hedge-straddle without stacks), Testing patterns, Known limitations (stacks ship in Phase 27 with quoted P&L cost; broker-local time deferred; same-bar dual breach tiebreak documented), References.**

- [ ] **Step 2: Link from `docs/phases/index.md`.**

- [ ] **Step 3: Commit.**

```bash
git commit -am "docs(phases): phase 26 changelog"
```

---

### Task 19 — Full pre-merge verification

- [ ] **Step 1: Full build.**

```bash
./gradlew build --no-daemon
```

Expected: BUILD SUCCESSFUL, ktlint clean.

- [ ] **Step 2: Full smoke.**

```bash
bash tests/smoke-install.sh
```

Expected: green incl. hedge-straddle parse.

- [ ] **Step 3: Read every commit message on the branch with `git log --oneline main..HEAD`. Confirm each follows §3 conventions.**

- [ ] **Step 4: Open PR with the standard template (phase, summary, changes, tests, backward-compat, out-of-scope, risk).**

---

## Self-review checklist

- [ ] Every spec requirement maps to a task.
- [ ] No placeholders ("TBD", "similar to Task N") in any step.
- [ ] Type/method names used in later tasks match earlier task signatures.
- [ ] Stacking is explicitly listed as Phase 27 work in the changelog (Task 18) and the hedge-straddle README (Task 16).
- [ ] MT5 broker OCO routing has a concrete fix path if today's broker rejects StandaloneOCO (Task 14 Step 2 is in scope, not "verify only").
