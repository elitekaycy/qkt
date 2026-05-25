# Phase 24 — risk-sizing DSL primitives implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four DSL surface primitives — `SIZING N PCT RISK`, `WARMUP N BARS`, `IS NULL` / `IS NOT NULL`, `FLATTEN` — to the qkt DSL parser/compiler.

**Architecture:** Three primitives are surface-only changes over already-shipped engine capabilities (`SizeRiskFrac`, `Value.Undefined`, `CloseAll`). The fourth (`WARMUP`) adds one stateful runtime component — `WarmupGate` — that counts per-stream closed candles and gates rule firing.

**Tech Stack:** Kotlin 1.9, JUnit 5, AssertJ. All source under `src/main/kotlin/com/qkt/dsl/`. All tests under `src/test/kotlin/com/qkt/dsl/`. Build with `./gradlew test`.

**Spec:** `docs/superpowers/specs/2026-05-25-phase24-risk-sizing-primitives-design.md`

---

## File structure

**Create:**
- `src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt` — per-stream closed-candle counter; one method to feed, one to query.
- `src/main/kotlin/com/qkt/dsl/compile/RuleAliasScan.kt` — AST walker that collects every stream alias referenced by a rule's condition + action.
- `src/test/kotlin/com/qkt/dsl/compile/WarmupGateTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/RuleAliasScanTest.kt`
- `src/test/kotlin/com/qkt/dsl/parse/ParserSizingPctRiskTest.kt`
- `src/test/kotlin/com/qkt/dsl/parse/ParserWarmupTest.kt`
- `src/test/kotlin/com/qkt/dsl/parse/ParserIsNullTest.kt`
- `src/test/kotlin/com/qkt/dsl/parse/ParserFlattenTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIsNullTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/WarmupEndToEndTest.kt`
- `src/test/resources/dsl/warmup_end_to_end.qkt`
- `docs/phases/phase-24-risk-sizing-primitives.md`

**Modify:**
- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL`.
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — `parseSymbols`, `parseSizing`, `parseAction`, `isActionStart`, `parseCmpExpr`.
- `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt` — extend `StreamDecl` with `warmupBars: Int? = null`.
- `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` — add `IsNull` data class.
- `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` — `IsNull` branch.
- `src/main/kotlin/com/qkt/dsl/compile/MetaRef.kt` — add `IsNull` to `walkExpr` (otherwise sealed-exhaustive `when` breaks the build).
- `src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt` — add `referencedAliases: Set<String>` field.
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` — build `WarmupGate`, compute per-rule referenced aliases, pass to `CompiledStrategy`.
- `src/main/kotlin/com/qkt/dsl/compile/DslCompiledStrategy.kt` — no changes; `CompiledStrategy` is internal.
- `editor/textmate/qkt.tmLanguage.json` — keyword scopes.
- `docs/reference/dsl/sizing.md`, `streams.md`, `expressions.md`, `actions.md`, `docs/reference/dsl-grammar.md`.

---

## Task 1: Add lexer tokens

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` (add cases to existing file)

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` (before the closing `}`):

```kotlin
@Test
fun `tokenizes phase 24 keywords`() {
    val tokens = Lexer("WARMUP BARS FLATTEN IS NULL").tokenize()
    val kinds = tokens.map { it.kind }
    assertThat(kinds).containsExactly(
        TokenKind.WARMUP,
        TokenKind.BARS,
        TokenKind.FLATTEN,
        TokenKind.IS,
        TokenKind.NULL,
        TokenKind.EOF,
    )
}

@Test
fun `phase 24 keywords are case-insensitive`() {
    val tokens = Lexer("warmup bars flatten is null").tokenize()
    assertThat(tokens.map { it.kind }).containsExactly(
        TokenKind.WARMUP,
        TokenKind.BARS,
        TokenKind.FLATTEN,
        TokenKind.IS,
        TokenKind.NULL,
        TokenKind.EOF,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.LexerTest.tokenizes phase 24 keywords'`
Expected: FAIL with unresolved reference `TokenKind.WARMUP`.

- [ ] **Step 3: Add the tokens**

Edit `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. Add to the existing enum, grouping by usage. Insert `WARMUP` and `BARS` after the existing `SYMBOLS`/`EVERY` family. Insert `FLATTEN` after `CLOSE_ALL`. Insert `IS` and `NULL` after `BETWEEN` / before the new-line-separated comparison cluster.

```kotlin
// Near line 19, after CANCEL_ALL:
CLOSE_ALL,
FLATTEN,

// Near line 79, after BELOW/BETWEEN:
BETWEEN,
IS,
NULL,

// Near line 137 (anywhere among keyword tokens; pick before NUMBER):
WARMUP,
BARS,
```

The `Lexer.KEYWORDS` map (lines 251-287) auto-derives keywords by enum name uppercase, so no further lexer change is needed. The new tokens spell out as their enum names (`WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.LexerTest'`
Expected: all LexerTest tests pass, including the two new ones.

- [ ] **Step 5: Verify the existing test suite still builds**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: every existing DSL test still compiles and passes. (Adding enum values is non-breaking — `when (kind)` blocks that didn't enumerate every token still work because they fall through to `else`.)

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): add phase 24 lexer tokens (WARMUP, BARS, FLATTEN, IS, NULL)"
```

---

## Task 2: FLATTEN parses to CloseAll

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseAction`, `isActionStart`)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserFlattenTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserFlattenTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserFlattenTest {
    @Test
    fun `FLATTEN parses to CloseAll`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 21 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isEmpty()
        val rule = result.strategy!!.rules.single() as WhenThen
        assertThat(rule.action).isEqualTo(CloseAll)
    }

    @Test
    fun `CLOSE_ALL still parses to CloseAll`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 21 THEN CLOSE_ALL
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isEmpty()
        val rule = result.strategy!!.rules.single() as WhenThen
        assertThat(rule.action).isEqualTo(CloseAll)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserFlattenTest'`
Expected: FAIL on the FLATTEN case with `expected action keyword, got 'FLATTEN'`. The CLOSE_ALL case passes already.

- [ ] **Step 3: Add FLATTEN to parseAction**

Edit `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`. In `parseAction` (around line 732), add a new branch alongside `TokenKind.CLOSE_ALL`:

```kotlin
TokenKind.CLOSE_ALL -> {
    advance()
    CloseAll
}
TokenKind.FLATTEN -> {
    advance()
    CloseAll
}
```

In `isActionStart` (around line 703), add `FLATTEN`:

```kotlin
private fun isActionStart(k: TokenKind): Boolean =
    k == TokenKind.BUY ||
        k == TokenKind.SELL ||
        k == TokenKind.CLOSE ||
        k == TokenKind.CLOSE_ALL ||
        k == TokenKind.FLATTEN ||
        k == TokenKind.CANCEL ||
        k == TokenKind.CANCEL_ALL ||
        k == TokenKind.LOG ||
        k == TokenKind.OCO_ENTRY
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserFlattenTest'`
Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserFlattenTest.kt
git commit -m "feat(dsl): accept FLATTEN as keyword alias for CLOSE_ALL"
```

---

## Task 3: SIZING N PCT RISK sugar

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseSizing`, around line 1104-1148)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserSizingPctRiskTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserSizingPctRiskTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeRiskFrac
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ParserSizingPctRiskTest {
    private fun parseSizing(input: String): com.qkt.dsl.ast.SizingAst {
        val tokens = Lexer(input).tokenize()
        val parser = Parser(tokens)
        return parser.parseSizing()
    }

    @Test
    fun `0_5 PCT RISK parses to SizeRiskFrac with 0_005`() {
        val s = parseSizing("0.5 PCT RISK")
        assertThat(s).isInstanceOf(SizeRiskFrac::class.java)
        val expr = (s as SizeRiskFrac).frac
        assertThat(expr).isInstanceOf(NumLit::class.java)
        assertThat((expr as NumLit).value).isEqualByComparingTo(BigDecimal("0.005"))
    }

    @Test
    fun `integer 1 PCT RISK parses to fraction 0_01`() {
        val s = parseSizing("1 PCT RISK") as SizeRiskFrac
        assertThat((s.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.01"))
    }

    @Test
    fun `decimal 0_25 PCT RISK parses to fraction 0_0025`() {
        val s = parseSizing("0.25 PCT RISK") as SizeRiskFrac
        assertThat((s.frac as NumLit).value).isEqualByComparingTo(BigDecimal("0.0025"))
    }

    @Test
    fun `0 PCT RISK is rejected at parse time`() {
        assertThatThrownBy { parseSizing("0 PCT RISK") }
            .hasMessageContaining("PCT RISK")
    }

    @Test
    fun `PCT without trailing RISK falls through to percent-of-equity sugar`() {
        // Existing surface: `0.5 % OF EQUITY`. We must not steal it.
        val s = parseSizing("0.5 % OF EQUITY")
        assertThat(s).isInstanceOf(com.qkt.dsl.ast.SizePctEquity::class.java)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserSizingPctRiskTest'`
Expected: FAIL — the existing `parseSizing` `else` branch doesn't recognize `PCT`.

- [ ] **Step 3: Add the PCT RISK branch**

Edit `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`. In `parseSizing` (line 1104-1148), inside the `else -> { ... }` branch around line 1122, add a `PCT` arm in the inner `when (peek().kind)` block. Insert it before the `PERCENT` arm at line 1129:

```kotlin
else -> {
    val e = parseExpr()
    when (peek().kind) {
        TokenKind.USD -> {
            advance()
            SizeNotional(e)
        }
        TokenKind.PCT -> {
            advance()
            expect(TokenKind.RISK, "expected RISK after PCT in SIZING")
            require(e is NumLit) {
                "SIZING N PCT RISK requires a numeric literal for N, got non-literal expression"
            }
            val pct = e.value
            require(pct.signum() > 0) {
                "SIZING N PCT RISK requires N > 0, got $pct"
            }
            SizeRiskFrac(NumLit(pct.divide(BigDecimal(100), com.qkt.common.Money.CONTEXT)))
        }
        TokenKind.PERCENT -> {
            advance()
            expect(TokenKind.OF, "expected OF after %")
            ...
```

(`error()` is a parser method, not Kotlin's `error()`; the `require()` here is Kotlin's stdlib `require()` which is intentional — `0 PCT RISK` is a literal-time invariant, not a parse-state error. The thrown `IllegalArgumentException` propagates to the caller; the assertion in the test matches on the message text.)

You will also need an import for `java.math.BigDecimal` if not already present. Check the import block at the top of `Parser.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserSizingPctRiskTest'`
Expected: all five tests pass.

- [ ] **Step 5: Run the broader sizing-related test suite**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserSizingTest' --tests 'com.qkt.dsl.parse.ParserActionTest' --tests 'com.qkt.dsl.compile.SizingCompilerTest'`
Expected: all existing tests still pass — no regression in the `RISK <frac>` / `RISK $ <abs>` / `% OF EQUITY|BALANCE` / `POSITION.<alias>` / bare-quantity / `USD` forms.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserSizingPctRiskTest.kt
git commit -m "feat(dsl): add SIZING N PCT RISK sugar over SizeRiskFrac"
```

---

## Task 4: Extend StreamDecl with warmupBars

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`

- [ ] **Step 1: Add the field with a safe default**

Edit `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`. Extend `StreamDecl`:

```kotlin
data class StreamDecl(
    val alias: String,
    val broker: String,
    val symbol: String,
    val timeframe: String,
    val warmupBars: Int? = null,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
        if (warmupBars != null) require(warmupBars > 0) { "StreamDecl.warmupBars must be > 0 if set: $warmupBars" }
    }

    val qktSymbol: String get() = "$broker:$symbol"
}
```

- [ ] **Step 2: Confirm no other call site breaks**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: clean compile. The default `warmupBars: Int? = null` keeps every existing constructor call valid.

- [ ] **Step 3: Run the full suite to verify no regressions**

Run: `./gradlew test`
Expected: all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt
git commit -m "feat(dsl): add optional warmupBars to StreamDecl AST"
```

---

## Task 5: Parse WARMUP N BARS on SYMBOLS line

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseSymbols`, around line 1171)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserWarmupTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserWarmupTest.kt`:

```kotlin
package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserWarmupTest {
    @Test
    fun `EVERY 5m WARMUP 50 BARS populates warmupBars`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isEmpty()
        val stream = result.strategy!!.streams.single()
        assertThat(stream.warmupBars).isEqualTo(50)
    }

    @Test
    fun `absent WARMUP leaves warmupBars null`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isEmpty()
        assertThat(result.strategy!!.streams.single().warmupBars).isNull()
    }

    @Test
    fun `WARMUP 0 BARS is rejected at parse time`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 0 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isNotEmpty()
        assertThat(result.errors.first().message).contains("WARMUP")
    }

    @Test
    fun `WARMUP with non-integer literal is rejected`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 50.5 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isNotEmpty()
    }

    @Test
    fun `per-stream WARMUP attaches independently in multi-stream`() {
        val src = """
            STRATEGY t VERSION 1
            SYMBOLS
              a = EXNESS:XAUUSD EVERY 5m WARMUP 30 BARS,
              b = EXNESS:XAGUSD EVERY 1h WARMUP 10 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
        """.trimIndent()
        val result = Dsl.parse(src)
        assertThat(result.errors).isEmpty()
        val streams = result.strategy!!.streams.associateBy { it.alias }
        assertThat(streams["a"]!!.warmupBars).isEqualTo(30)
        assertThat(streams["b"]!!.warmupBars).isEqualTo(10)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserWarmupTest'`
Expected: FAIL — the WARMUP keyword isn't consumed by `parseSymbols`, so the first test fails.

- [ ] **Step 3: Add WARMUP handling to parseSymbols**

Edit `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`. In `parseSymbols` (line 1171-1199), after the timeframe is built and before `out.add(StreamDecl(...))`, optionally consume `WARMUP <int> BARS`:

```kotlin
val timeframe =
    if (peek().kind == TokenKind.DURATION) {
        advance().lexeme
    } else {
        val tfNum = expect(TokenKind.NUMBER, "expected timeframe count").lexeme
        val tfUnit = expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
        "$tfNum$tfUnit"
    }
val warmupBars: Int? =
    if (peek().kind == TokenKind.WARMUP) {
        advance()
        val numToken = expect(TokenKind.NUMBER, "expected integer bar count after WARMUP")
        val n = numToken.lexeme.toIntOrNull()
            ?: error("WARMUP count must be a positive integer, got '${numToken.lexeme}'")
        if (n <= 0) error("WARMUP count must be > 0, got $n")
        expect(TokenKind.BARS, "expected BARS after WARMUP count")
        n
    } else {
        null
    }
out.add(
    StreamDecl(
        alias = alias,
        broker = broker,
        symbol = symbol,
        timeframe = timeframe,
        warmupBars = warmupBars,
    ),
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserWarmupTest'`
Expected: all five tests pass.

- [ ] **Step 5: Run the broader symbol/stream parser tests**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.*Stream*' --tests 'com.qkt.dsl.parse.*Symbol*'`
Expected: existing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserWarmupTest.kt
git commit -m "feat(dsl): parse WARMUP N BARS on SYMBOLS lines"
```

---

## Task 6: IsNull AST node + ExprCompiler branch

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/MetaRef.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIsNullTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIsNullTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerIsNullTest {
    private val compiler = ExprCompiler()

    private fun ec(): EvalContext = TestContexts.emptyEvalContext()  // see helper below

    @Test
    fun `IS NULL on a NumLit returns false`() {
        val ast = IsNull(NumLit(java.math.BigDecimal.ONE), negated = false)
        val compiled = compiler.compile(ast)
        assertThat(compiled.evaluate(ec())).isEqualTo(Value.Bool(false))
    }

    @Test
    fun `IS NOT NULL on a NumLit returns true`() {
        val ast = IsNull(NumLit(java.math.BigDecimal.ONE), negated = true)
        val compiled = compiler.compile(ast)
        assertThat(compiled.evaluate(ec())).isEqualTo(Value.Bool(true))
    }

    @Test
    fun `IS NULL on an unbound Ref returns true`() {
        // An unresolved Ref resolves to Value.Undefined when no snapshot/let backs it.
        val ast = IsNull(Ref("nope"), negated = false)
        val compiled = compiler.compile(ast)
        assertThat(compiled.evaluate(ec())).isEqualTo(Value.Bool(true))
    }

    @Test
    fun `IS NOT NULL on an unbound Ref returns false`() {
        val ast = IsNull(Ref("nope"), negated = true)
        val compiled = compiler.compile(ast)
        assertThat(compiled.evaluate(ec())).isEqualTo(Value.Bool(false))
    }
}
```

If `TestContexts.emptyEvalContext()` doesn't already exist in the test tree, look at how `ExprCompilerTest.kt` or `AstCompilerTest.kt` construct an `EvalContext` for unit tests and copy that pattern inline at the top of the new test file. Do not introduce a new test helper if the surrounding files inline their setup.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerIsNullTest'`
Expected: FAIL on `unresolved reference: IsNull`.

- [ ] **Step 3: Add IsNull AST node**

Edit `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`. Append after `EntryQty` (line 115):

```kotlin
/**
 * Phase 24: explicit null test against [com.qkt.dsl.compile.Value.Undefined].
 *
 * `<expr> IS NULL` evaluates to `Value.Bool(true)` iff the inner expression yields
 * `Value.Undefined`. `IS NOT NULL` is the inverse. The result is always a defined
 * `Value.Bool` — `IsNull` never propagates undefined itself.
 */
data class IsNull(
    val expr: ExprAst,
    val negated: Boolean,
) : ExprAst
```

- [ ] **Step 4: Add the ExprCompiler branch**

Edit `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`. Add an import for `IsNull` at the top (alphabetical, between `InList` and `IndicatorCall`):

```kotlin
import com.qkt.dsl.ast.IsNull
```

In the `compile` function's `when` block (around line 41-62), add a branch. Place it after the `InList` branch so it stays near the other comparison-precedence nodes:

```kotlin
is InList -> compileInList(expr, ruleAlias)
is IsNull -> compileIsNull(expr, ruleAlias)
```

Then add the helper at the end of the class:

```kotlin
private fun compileIsNull(expr: IsNull, ruleAlias: String?): CompiledExpr {
    val inner = compile(expr.expr, ruleAlias)
    return CompiledExpr { ctx ->
        val v = inner.evaluate(ctx)
        val isUndef = v is Value.Undefined
        Value.Bool(if (expr.negated) !isUndef else isUndef)
    }
}
```

- [ ] **Step 5: Patch MetaRef.walkExpr for exhaustiveness**

Edit `src/main/kotlin/com/qkt/dsl/compile/MetaRef.kt`. The `walkExpr` function (line 97-140) uses an exhaustive `when` on the sealed `ExprAst` hierarchy. Adding a new variant breaks compilation. Add the IsNull case alongside `UnaryOp`:

```kotlin
import com.qkt.dsl.ast.IsNull
// ...
is UnaryOp -> walkExpr(e.arg)
is IsNull -> walkExpr(e.expr)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.ExprCompilerIsNullTest'`
Expected: all four tests pass.

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: clean compile. Any other exhaustive `when` over `ExprAst` will show up here as a build error — patch it the same way (add `is IsNull -> ...`).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/main/kotlin/com/qkt/dsl/compile/MetaRef.kt src/test/kotlin/com/qkt/dsl/compile/ExprCompilerIsNullTest.kt
git commit -m "feat(dsl): add IS NULL / IS NOT NULL expression node and compiler"
```

---

## Task 7: Parse IS NULL / IS NOT NULL

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseCmpExpr`, around line 305-365)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserIsNullTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserIsNullTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParserIsNullTest {
    private fun parseExpr(input: String): com.qkt.dsl.ast.ExprAst {
        val tokens = Lexer(input).tokenize()
        return Parser(tokens).parseExpr()
    }

    @Test
    fun `Ref IS NULL parses to IsNull non-negated`() {
        val e = parseExpr("foo IS NULL")
        assertThat(e).isEqualTo(IsNull(Ref("foo"), negated = false))
    }

    @Test
    fun `Ref IS NOT NULL parses to IsNull negated`() {
        val e = parseExpr("foo IS NOT NULL")
        assertThat(e).isEqualTo(IsNull(Ref("foo"), negated = true))
    }

    @Test
    fun `IS NULL binds tighter than AND`() {
        // foo IS NOT NULL AND bar > 0 → (foo IS NOT NULL) AND (bar > 0)
        val e = parseExpr("foo IS NOT NULL AND bar > 0") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.AND)
        assertThat(e.lhs).isEqualTo(IsNull(Ref("foo"), negated = true))
    }

    @Test
    fun `IS without trailing NULL is a parse error`() {
        assertThatThrownBy { parseExpr("foo IS NOT BAR") }
            .hasMessageContaining("NULL")
    }

    @Test
    fun `IS NULL on an indicator call parses`() {
        val e = parseExpr("EMA(g.close, 9) IS NULL")
        assertThat(e).isInstanceOf(IsNull::class.java)
        assertThat((e as IsNull).negated).isFalse
    }

    @Test
    fun `IS NULL on a numeric literal parses`() {
        // Trivially false at runtime but should parse cleanly.
        val e = parseExpr("1 IS NULL")
        assertThat(e).isEqualTo(IsNull(NumLit(java.math.BigDecimal("1")), negated = false))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserIsNullTest'`
Expected: FAIL — `parseCmpExpr` doesn't recognize `IS`.

- [ ] **Step 3: Add the IS NULL arm to parseCmpExpr**

Edit `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`. In `parseCmpExpr` (around line 305-365), inside the `when (k)` block that handles `BETWEEN`/`IN`/`CROSSES`, add an `IS` arm. Add an import for `IsNull` at the top of the file if needed:

```kotlin
import com.qkt.dsl.ast.IsNull
```

Insert the new arm before the `else -> return lhs` line (around line 362):

```kotlin
TokenKind.CROSSES -> {
    advance()
    // ... existing block ...
    lhs = Crosses(dir, lhs, rhs)
}
TokenKind.IS -> {
    advance()
    val negated = match(TokenKind.NOT)
    expect(TokenKind.NULL, "expected NULL after IS${if (negated) " NOT" else ""}")
    lhs = IsNull(lhs, negated)
}
else -> return lhs
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserIsNullTest'`
Expected: all six tests pass.

- [ ] **Step 5: Run the broader expression-parser suite**

Run: `./gradlew test --tests 'com.qkt.dsl.parse.ParserExpr*'`
Expected: every existing expression-parser test still passes (no precedence drift).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserIsNullTest.kt
git commit -m "feat(dsl): parse IS NULL / IS NOT NULL at comparison precedence"
```

---

## Task 8: RuleAliasScan — collect referenced aliases per rule

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/RuleAliasScan.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/RuleAliasScanTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/RuleAliasScanTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleAliasScanTest {
    private fun rule(src: String) =
        Dsl.parse(src).strategy!!.rules.single() as com.qkt.dsl.ast.WhenThen

    @Test
    fun `condition referencing one stream returns that alias`() {
        val r = rule(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN g.close > 100 THEN FLATTEN
            """.trimIndent(),
        )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `condition referencing position returns that alias`() {
        val r = rule(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN POSITION.g = 0 THEN FLATTEN
            """.trimIndent(),
        )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `multi-stream condition returns all referenced aliases`() {
        val r = rule(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              a = X:Y EVERY 1m,
              b = X:Z EVERY 1m
            RULES
              WHEN a.close > b.close THEN FLATTEN
            """.trimIndent(),
        )
        assertThat(collectStreamAliases(r)).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `action's BUY target alias is included`() {
        val r = rule(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 10 THEN BUY g
            """.trimIndent(),
        )
        assertThat(collectStreamAliases(r)).containsExactly("g")
    }

    @Test
    fun `NOW-only condition with no action target returns empty`() {
        val r = rule(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = X:Y EVERY 1m
            RULES
              WHEN NOW.hour_utc = 10 THEN FLATTEN
            """.trimIndent(),
        )
        assertThat(collectStreamAliases(r)).isEmpty()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.RuleAliasScanTest'`
Expected: FAIL — `collectStreamAliases` doesn't exist.

- [ ] **Step 3: Create RuleAliasScan.kt**

Create `src/main/kotlin/com/qkt/dsl/compile/RuleAliasScan.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.EntryQty
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen

/**
 * Collect every stream alias the rule's condition or action references.
 *
 * Used by Phase 24's [WarmupGate] to decide whether a rule is allowed to fire.
 * A rule is gated by the union of aliases its condition expression, action target,
 * and any nested expressions (BRACKET, OCO, SIZING, STACK_AT) touch.
 *
 * Exhaustive over the sealed hierarchies — a new AST variant breaks the build here.
 */
fun collectStreamAliases(rule: WhenThen): Set<String> {
    val out = mutableSetOf<String>()

    fun walkExpr(e: ExprAst) {
        when (e) {
            is NumLit, is BoolLit, is StringLit -> Unit
            is Ref, is NowAccessor, is AccountRef, is StateAccessor, StackEntryRef, EntryQty -> Unit
            is PositionRef -> out.add(e.stream)
            is StreamFieldRef -> out.add(e.stream)
            is IndicatorCall -> e.args.forEach { walkExpr(it) }
            is BinaryOp -> { walkExpr(e.lhs); walkExpr(e.rhs) }
            is UnaryOp -> walkExpr(e.arg)
            is CmpOp -> { walkExpr(e.lhs); walkExpr(e.rhs) }
            is Between -> { walkExpr(e.v); walkExpr(e.lo); walkExpr(e.hi) }
            is InList -> { walkExpr(e.v); e.members.forEach { walkExpr(it) } }
            is Crosses -> { walkExpr(e.lhs); walkExpr(e.rhs) }
            is CaseWhen -> {
                e.branches.forEach { (c, v) -> walkExpr(c); walkExpr(v) }
                walkExpr(e.elseExpr)
            }
            is Aggregate -> walkExpr(e.series)
            is FuncCall -> e.args.forEach { walkExpr(it) }
            is IsNull -> walkExpr(e.expr)
        }
    }

    fun walkSizing(s: SizingAst?) {
        when (s) {
            null -> Unit
            is SizeQty -> walkExpr(s.expr)
            is SizeNotional -> walkExpr(s.usd)
            is SizePctEquity -> walkExpr(s.frac)
            is SizePctBalance -> walkExpr(s.frac)
            is SizeRiskFrac -> walkExpr(s.frac)
            is SizeRiskAbs -> walkExpr(s.usd)
            is SizePositionFull -> out.add(s.stream)
        }
    }

    fun walkOrderType(o: OrderTypeAst?) {
        when (o) {
            null, Market -> Unit
            is Limit -> walkExpr(o.price)
            is Stop -> walkExpr(o.price)
            is StopLimit -> { walkExpr(o.stopPrice); walkExpr(o.limitPrice) }
            is TrailingBy -> walkExpr(o.distance)
            is TrailingPct -> walkExpr(o.frac)
        }
    }

    fun walkChild(c: ChildPriceAst?) {
        when (c) {
            null -> Unit
            is ChildAt -> walkExpr(c.price)
            is ChildBy -> walkExpr(c.distance)
            is ChildPct -> walkExpr(c.frac)
            is ChildRr -> walkExpr(c.multiplier)
        }
    }

    fun walkBracket(b: BracketAst?) {
        if (b == null) return
        walkChild(b.stopLoss); walkChild(b.takeProfit)
    }

    fun walkOco(o: OcoAst?) {
        if (o == null) return
        walkChild(o.stop); walkChild(o.limit)
    }

    fun walkTif(t: TifAst?) {
        when (t) {
            null, Gtc, Ioc, Fok, Day -> Unit
            is Gtd -> walkExpr(t.until)
        }
    }

    fun walkStack(s: StackAst?) {
        when (s) {
            null -> Unit
            is StackSpacing -> walkExpr(s.spacing)
            is StackLayers ->
                s.layers.forEach { layer ->
                    walkSizing(layer.sizing)
                    walkOrderType(layer.orderType)
                    layer.at?.let { walkExpr(it) }
                }
        }
    }

    fun walkOpts(opts: ActionOpts) {
        walkSizing(opts.sizing)
        walkOrderType(opts.orderType)
        walkTif(opts.tif)
        walkBracket(opts.bracket)
        walkOco(opts.oco)
        walkStack(opts.stack)
        opts.stackAts.forEach { clause ->
            walkExpr(clause.mfeThreshold)
            walkSizing(clause.sizing)
            walkBracket(clause.bracket)
        }
    }

    fun walkAction(a: ActionAst) {
        when (a) {
            CloseAll, CancelAll -> Unit
            is Close -> out.add(a.stream)
            is Cancel -> out.add(a.stream)
            is Buy -> { out.add(a.stream); walkOpts(a.opts) }
            is Sell -> { out.add(a.stream); walkOpts(a.opts) }
            is Log -> a.fields.values.forEach { walkExpr(it) }
            is Block -> a.actions.forEach { walkAction(it) }
            is OcoEntry -> { walkAction(a.leg1); walkAction(a.leg2) }
        }
    }

    walkExpr(rule.cond)
    walkAction(rule.action)
    return out.toSet()
}
```

Cross-check this walker against `MetaRef.kt`'s `walkExpr`/`walkAction`/`walkOpts` — they must stay structurally aligned so that future AST changes break both walkers in the same way.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.RuleAliasScanTest'`
Expected: all five tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/RuleAliasScan.kt src/test/kotlin/com/qkt/dsl/compile/RuleAliasScanTest.kt
git commit -m "feat(dsl): add RuleAliasScan to collect referenced stream aliases per rule"
```

---

## Task 9: WarmupGate class + unit tests

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/WarmupGateTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/WarmupGateTest.kt`:

```kotlin
package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupGateTest {
    @Test
    fun `empty perStream map means everything is warm`() {
        val gate = WarmupGate(emptyMap())
        assertThat(gate.isWarm("anything")).isTrue
        assertThat(gate.isWarm(setOf("a", "b"))).isTrue
    }

    @Test
    fun `unconfigured alias is treated as warm`() {
        val gate = WarmupGate(mapOf("a" to 3))
        assertThat(gate.isWarm("b")).isTrue
    }

    @Test
    fun `configured alias starts cold and warms at the Nth closed candle`() {
        val gate = WarmupGate(mapOf("a" to 3))
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isFalse
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isTrue
        gate.onClosedCandle("a")
        assertThat(gate.isWarm("a")).isTrue
    }

    @Test
    fun `set form requires every alias warm`() {
        val gate = WarmupGate(mapOf("a" to 2, "b" to 1))
        gate.onClosedCandle("a")
        gate.onClosedCandle("a")
        // a warm, b not warm
        assertThat(gate.isWarm(setOf("a", "b"))).isFalse
        gate.onClosedCandle("b")
        assertThat(gate.isWarm(setOf("a", "b"))).isTrue
    }

    @Test
    fun `empty set is always warm`() {
        val gate = WarmupGate(mapOf("a" to 100))
        assertThat(gate.isWarm(emptySet())).isTrue
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.WarmupGateTest'`
Expected: FAIL on unresolved `WarmupGate`.

- [ ] **Step 3: Implement WarmupGate**

Create `src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt`:

```kotlin
package com.qkt.dsl.compile

/**
 * Phase 24: per-stream closed-candle counter used to gate DSL rule firing.
 *
 * A rule does not fire on a given tick until every stream alias it references has
 * been observed [perStream] closed candles. An alias not present in [perStream]
 * (i.e., a stream with no WARMUP declared) is treated as warm from tick zero.
 *
 * The counter is monotonic within a process: engine restart resets it. This matches
 * Phase 24's live-only semantics — historical prefetch lands in Phase 25.
 */
class WarmupGate(
    private val perStream: Map<String, Int>,
) {
    private val counts: MutableMap<String, Int> = mutableMapOf()

    fun onClosedCandle(alias: String) {
        counts.merge(alias, 1, Int::plus)
    }

    fun isWarm(alias: String): Boolean {
        val required = perStream[alias] ?: return true
        val seen = counts[alias] ?: 0
        return seen >= required
    }

    fun isWarm(aliases: Set<String>): Boolean = aliases.all(::isWarm)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.WarmupGateTest'`
Expected: all five tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt src/test/kotlin/com/qkt/dsl/compile/WarmupGateTest.kt
git commit -m "feat(dsl): add WarmupGate for per-stream closed-candle gating"
```

---

## Task 10: Wire WarmupGate into CompiledStrategy

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt` (add referencedAliases field)
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (build gate, compute aliases, pass through)
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (CompiledStrategy: feed gate + check before firing)
- Test: extend `WarmupEndToEndTest` (Task 11) covers integration

- [ ] **Step 1: Extend CompiledRule with referencedAliases**

Edit `src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt`. Add to the constructor:

```kotlin
class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> List<Signal>,
    val ruleAlias: String,
    private val ruleSymbol: String,
    private val isBuy: Boolean,
    private val isSell: Boolean,
    private val onBuyCaptures: List<Pair<String, CompiledExpr>>,
    private val onSellCaptures: List<Pair<String, CompiledExpr>>,
    private val onOpenCaptures: List<Pair<String, CompiledExpr>>,
    val referencedAliases: Set<String>,
) {
    // ... existing body unchanged ...
}
```

- [ ] **Step 2: Compute referencedAliases in AstCompiler**

Edit `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`. In the `rules` map (lines 59-99), compute and pass referencedAliases:

```kotlin
val rules: List<CompiledRule> =
    whenThens.zip(resolvedConditions).map { (rule, cond) ->
        // ... existing primary/streamAlias/ruleAlias/ruleSymbol/compiledCond/action setup ...
        val referencedAliases = collectStreamAliases(rule)
        CompiledRule(
            condition = compiledCond,
            action = action,
            ruleAlias = ruleAlias,
            ruleSymbol = ruleSymbol,
            isBuy = isBuy,
            isSell = isSell,
            onBuyCaptures = plan.captureOnBuy.map { it to letCompiledRhs.getValue(it) },
            onSellCaptures = plan.captureOnSell.map { it to letCompiledRhs.getValue(it) },
            onOpenCaptures = plan.captureOnOpen.map { it to letCompiledRhs.getValue(it) },
            referencedAliases = referencedAliases,
        )
    }
```

Note: `collectStreamAliases` takes a `WhenThen` (the original AST), not the resolved condition. Pass `rule`, not `cond`.

- [ ] **Step 3: Build WarmupGate and thread it into CompiledStrategy**

Edit `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`. After the `rules` list is built, before the `return CompiledStrategy(...)`, add:

```kotlin
val perStreamWarmup: Map<String, Int> =
    ast.streams.mapNotNull { s -> s.warmupBars?.let { s.alias to it } }.toMap()
val warmupGate = WarmupGate(perStreamWarmup)
```

Pass `warmupGate` to the `CompiledStrategy` constructor. Add the parameter:

```kotlin
return CompiledStrategy(
    streams = streams,
    retentionByKey = retentionByKey,
    bindings = bindings,
    aggregates = aggregates,
    snapshotStore = snapshotStore,
    plan = plan,
    letCompiledRhs = letCompiledRhs,
    transitions = PositionTransitions(),
    rules = rules,
    pendingStacks = pendingStacks,
    multiPositionPerSymbolSymbols = stackAtSymbols,
    metaRefs = metaRefs,
    warmupGate = warmupGate,
)
```

And add to `CompiledStrategy`'s primary constructor (lines 158-171):

```kotlin
private class CompiledStrategy(
    private val streams: Map<String, HubKey>,
    override val retentionByKey: Map<HubKey, Int>,
    private val bindings: IndicatorBinding.Bag,
    private val aggregates: AggregateBinding.Bag,
    private val snapshotStore: SnapshotStore,
    private val plan: SnapshotPlan,
    private val letCompiledRhs: Map<String, CompiledExpr>,
    private val transitions: PositionTransitions,
    private val rules: List<CompiledRule>,
    override val pendingStacks: PendingStacks,
    override val multiPositionPerSymbolSymbols: Set<String>,
    private val metaRefs: List<MetaRef>,
    private val warmupGate: WarmupGate,
) : DslCompiledStrategy { ... }
```

- [ ] **Step 4: Feed the gate and check it before firing rules**

In `CompiledStrategy.evaluate` (lines 207-265), feed the gate at the top and check before the rule-firing loop. Replace the existing rule loop (around lines 261-264):

```kotlin
warmupGate.onClosedCandle(alias)

// ... existing position-transition / indicator / snapshot / aggregate updates ...

// 5. Rules whose ruleAlias matches and whose referenced streams are all warm
for (rule in rules) {
    if (rule.ruleAlias != alias) continue
    if (!warmupGate.isWarm(rule.referencedAliases)) continue
    for (sig in rule.fire(ec, ctx)) emit(sig)
}
```

`onClosedCandle(alias)` must run **once per evaluate call** — placed at the very top so the candle that triggered the eval also counts. After step 5, the next call for the same alias sees `seen+1`.

The `onCandle` path (lines 274-339) is the non-hub-bound fallback used by some tests / hand-written wiring. Mirror the gate logic there too — feed each alias whose symbol matches the candle, and check the gate before firing each rule:

```kotlin
// At top of onCandle, after the symbol filter (line 280):
for ((alias, key) in streams) {
    if (key.qktSymbol == candle.symbol) warmupGate.onClosedCandle(alias)
}

// At the rule loop (line 336-338):
for (rule in rules) {
    if (!warmupGate.isWarm(rule.referencedAliases)) continue
    for (sig in rule.fire(ec, ctx)) emit(sig)
}
```

- [ ] **Step 5: Verify compile + existing tests still pass**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: clean compile.

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: every existing DSL test passes — no existing fixture declares `WARMUP`, so every existing rule is unconditionally warm.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledRule.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt
git commit -m "feat(dsl): gate rule firing on per-stream WarmupGate"
```

---

## Task 11: End-to-end coverage for WARMUP + FLATTEN + IS NULL

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/WarmupEndToEndTest.kt`

This task uses the `Backtest` pattern already established in `CloseEndToEndTest.kt` — no new harness, no `.qkt` fixture file. Strategies are built via the Kotlin DSL `strategy { ... }` builder. The assertion compares two backtests of the same strategy (one with `WARMUP`, one without) and verifies trade count differs in the expected direction.

The strategy fires `BUY` whenever flat and price is non-null, with a tight bracket so each entry closes quickly and the rule can re-fire. Without WARMUP, every closed candle fires the rule. With WARMUP N, the first N closed candles are suppressed, so the trade count is strictly smaller.

The `FLATTEN` keyword is exercised via a session-end rule. `IS NOT NULL` is exercised in the entry condition guard.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/WarmupEndToEndTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.parse.Dsl
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupEndToEndTest {
    private fun ticks(count: Int): List<Tick> =
        // strictly increasing price so each bracket TP can fire predictably.
        (1..count).map { i ->
            Tick(
                symbol = "BACKTEST:BTCUSDT",
                price = Money.of((100 + i).toString()),
                timestamp = i * 60_000L,
            )
        }

    private fun compile(src: String) = AstCompiler().compile(Dsl.parse(src).strategy!!)

    @Test
    fun `WARMUP suppresses rule firings until the configured bar count`() {
        val withWarmup =
            compile(
                """
                STRATEGY warmup_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m WARMUP 5 BARS
                RULES
                  WHEN btc.close IS NOT NULL AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 1 }
                """.trimIndent(),
            )

        val withoutWarmup =
            compile(
                """
                STRATEGY no_warmup VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close IS NOT NULL AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 1 }
                """.trimIndent(),
            )

        val sample = ticks(20)

        val resultWithWarmup =
            Backtest(
                strategies = listOf("warmup_e2e" to withWarmup),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val resultWithoutWarmup =
            Backtest(
                strategies = listOf("no_warmup" to withoutWarmup),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        // The WARMUP-gated strategy must produce strictly fewer trades than the same
        // strategy without WARMUP, because the first 5 closed candles can't fire its
        // BUY rule.
        assertThat(resultWithWarmup.trades.size)
            .isLessThan(resultWithoutWarmup.trades.size)
            .isGreaterThan(0)
    }

    @Test
    fun `FLATTEN action closes open positions`() {
        // Tick 1 enters (close>=110, then later closes via TP). Force a FLATTEN at
        // minute 5 to exercise the keyword end-to-end. After FLATTEN, the position
        // recorded in finalPositions should be zero or absent.
        val strat =
            compile(
                """
                STRATEGY flatten_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 100 AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS BY 1000, TAKE PROFIT BY 1000 }

                  WHEN POSITION.btc != 0
                  THEN FLATTEN
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("flatten_e2e" to strat),
                ticks = ticks(10),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        // At least one BUY entry and one corresponding FLATTEN close.
        assertThat(result.trades).hasSizeGreaterThanOrEqualTo(2)
        val finalQty =
            result.finalPositions["BACKTEST:BTCUSDT"]?.quantity
                ?: java.math.BigDecimal.ZERO
        assertThat(finalQty.signum()).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests 'com.qkt.dsl.compile.WarmupEndToEndTest'`
Expected: both tests pass. If the WARMUP test's assertion fails because trade counts are equal, double-check Task 10 — the gate must be consulted before `rule.fire` and the counter must be incremented per closed candle.

- [ ] **Step 3: Run the full DSL suite one more time**

Run: `./gradlew test --tests 'com.qkt.dsl.*'`
Expected: every DSL test passes.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/WarmupEndToEndTest.kt
git commit -m "test(dsl): end-to-end coverage for WARMUP + FLATTEN + IS NULL"
```

---

## Task 12: TextMate grammar additions

**Files:**
- Modify: `editor/textmate/qkt.tmLanguage.json`

- [ ] **Step 1: Inspect the existing keyword scopes**

Read `editor/textmate/qkt.tmLanguage.json`. Find the keyword pattern(s) where existing DSL keywords like `STRATEGY`, `SYMBOLS`, `RULES`, `CLOSE_ALL`, `BUY`, `SELL`, `BETWEEN`, `CROSSES` live. There will likely be a `keyword.control.qkt` or `keyword.other.qkt` block with a regex listing the words.

- [ ] **Step 2: Add the new keywords to the appropriate scope(s)**

Append `WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL` to the keyword list. Match the casing pattern the existing grammar uses (typically a case-insensitive alternation).

If the grammar groups keywords by semantic role (e.g., flow keywords vs. action keywords), put them in the role that matches:
- `WARMUP`, `BARS` → same group as `EVERY`, `STRATEGY`, `SYMBOLS` (declaration keywords)
- `FLATTEN` → same group as `CLOSE_ALL`, `BUY`, `SELL` (action keywords)
- `IS`, `NULL` → same group as `BETWEEN`, `IN`, `AND`, `OR` (operator/comparison keywords)

- [ ] **Step 3: Visual confirmation**

There's no test harness for the grammar in this repo. Open a `.qkt` file containing the new keywords in any editor that loads the grammar (VS Code with the qkt extension, or by syntax-highlighting one of the new test fixtures on github.com) and confirm the words highlight as keywords.

- [ ] **Step 4: Commit**

```bash
git add editor/textmate/qkt.tmLanguage.json
git commit -m "feat(editor): add phase 24 keywords to textmate grammar"
```

---

## Task 13: Reference docs

**Files:**
- Modify: `docs/reference/dsl/sizing.md`
- Modify: `docs/reference/dsl/streams.md`
- Modify: `docs/reference/dsl/expressions.md`
- Modify: `docs/reference/dsl/actions.md`
- Modify: `docs/reference/dsl-grammar.md`

- [ ] **Step 1: Update sizing.md**

In `docs/reference/dsl/sizing.md`, find the section that documents `SIZING RISK <frac>` and `SIZING RISK $ <abs>`. Add a third bullet right after it for the PCT form:

```markdown
- `SIZING <N> PCT RISK` — equivalent to `SIZING RISK (N / 100)`. Both forms compile to the same engine path. Use the PCT form to avoid decimal-shift bugs when expressing small risk fractions: `SIZING 0.5 PCT RISK` is unambiguous; `SIZING RISK 0.005` invites typos.
```

- [ ] **Step 2: Update streams.md**

In `docs/reference/dsl/streams.md`, after the section that documents `<alias> = <broker>:<symbol> EVERY <tf>`, add a subsection:

```markdown
### Per-stream warmup

`WARMUP N BARS` after the timeframe declares that any rule referencing this stream
must wait for N closed candles before firing.

\`\`\`
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS
\`\`\`

In multi-stream strategies, each stream gets its own warmup; a rule that
references both streams waits until **all** referenced streams are warm.

Limitations:
- Counts live closed candles only. Historical prefetch lands in Phase 25 with
  the `qkt fetch` CLI.
- Engine restart resets the counter.
- `N` must be a positive integer.
```

- [ ] **Step 3: Update expressions.md**

In `docs/reference/dsl/expressions.md`, add a section near the comparison operators:

```markdown
### IS NULL / IS NOT NULL

\`\`\`
<expr> IS NULL
<expr> IS NOT NULL
\`\`\`

Tests whether the inner expression evaluates to "missing" (the internal
`Value.Undefined` sentinel). Common producers of undefined:

- Indicators that haven't received enough updates yet (`EMA(gold.close, 50) IS NULL`)
- Snapshots that haven't been captured yet
- Cross-type expressions where one side can't coerce to a number

`IS NULL` always returns a boolean — it never propagates undefined itself, so
it composes safely with `AND` / `OR`. Binds tighter than `AND`, so
`fast IS NOT NULL AND slow IS NOT NULL AND CROSSES(fast, slow) ABOVE` parses
without parentheses.
```

- [ ] **Step 4: Update actions.md**

In `docs/reference/dsl/actions.md`, find the `CLOSE_ALL` section and add an alias note:

```markdown
- `CLOSE_ALL` — close every open position attributed to this strategy. Pending
  orders are not affected.
- `FLATTEN` — alias for `CLOSE_ALL`. Identical semantics; provided to match
  industry vocabulary.
```

- [ ] **Step 5: Update dsl-grammar.md**

In `docs/reference/dsl-grammar.md`, add the new productions:

```
sizing      ::= ... | NUMBER 'PCT' 'RISK'
stream_decl ::= alias '=' broker ':' symbol 'EVERY' tf ('WARMUP' INTEGER 'BARS')?
expr        ::= ... | expr ('IS' 'NOT'? 'NULL')
action      ::= ... | 'FLATTEN'
```

- [ ] **Step 6: Commit**

```bash
git add docs/reference/dsl/sizing.md docs/reference/dsl/streams.md docs/reference/dsl/expressions.md docs/reference/dsl/actions.md docs/reference/dsl-grammar.md
git commit -m "docs(dsl): document phase 24 primitives in dsl reference"
```

---

## Task 14: Phase changelog

**Files:**
- Create: `docs/phases/phase-24-risk-sizing-primitives.md`

- [ ] **Step 1: Write the changelog**

Create `docs/phases/phase-24-risk-sizing-primitives.md`. Follow the 7-section structure mandated by qkt skill §6 (summary / what's new / migration / cookbook / testing / limitations / refs):

```markdown
# Phase 24 — risk-sizing DSL primitives

## Summary

Phase 24 ships four small DSL surface primitives — `SIZING N PCT RISK`,
`WARMUP N BARS`, `IS NULL` / `IS NOT NULL`, and `FLATTEN` — that strategy
authors had been hand-rolling around. Three are sugar over engine paths shipped
in earlier phases; the fourth (`WARMUP`) adds a per-stream rule-firing gate.

## What's new

- `SIZING N PCT RISK` — percent sugar over the existing `SIZING RISK <frac>`.
- `WARMUP N BARS` on the SYMBOLS line — gates rule firing per stream.
- `<expr> IS NULL` / `<expr> IS NOT NULL` — explicit test against the internal
  `Value.Undefined` sentinel.
- `FLATTEN` action — keyword alias for `CLOSE_ALL`.
- `IsNull` AST node and `compileIsNull` branch in `ExprCompiler`.
- `WarmupGate` runtime class and `RuleAliasScan` AST walker.
- New `StreamDecl.warmupBars: Int?` AST field.

## Migration from Phase 23

Nothing removed. Nothing renamed. Strategies written before Phase 24 parse and
compile unchanged.

If you've been writing `WHEN fast > 0 AND slow > 0 AND CROSSES(fast, slow) ABOVE`
as a workaround for indicator-not-ready, you can now write
`WHEN fast IS NOT NULL AND slow IS NOT NULL AND CROSSES(fast, slow) ABOVE`. Both
work; the second reads as the intent.

## Cookbook

### Risk-sized entry with explicit indicator readiness check

\`\`\`
STRATEGY ema_cross VERSION 1

DEFAULTS {
    SIZING = 0.5 PCT RISK
    TIF = GTC
}

SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS

LET
    fast = EMA(gold.close, 9),
    slow = EMA(gold.close, 21)

RULES
    WHEN fast IS NOT NULL AND slow IS NOT NULL
     AND CROSSES(fast, slow) ABOVE
     AND POSITION.gold = 0
    THEN BUY gold
         BRACKET { STOP LOSS BY 8, TAKE PROFIT BY 16 }
\`\`\`

### Session-end flatten

\`\`\`
RULES
    WHEN NOW.hour_utc = 21 AND NOW.minute_utc = 0
    THEN FLATTEN
\`\`\`

### Multi-stream warmup

\`\`\`
SYMBOLS
    fast_tf = EXNESS:XAUUSD EVERY 1m WARMUP 200 BARS,
    slow_tf = EXNESS:XAUUSD EVERY 1h WARMUP 24  BARS

RULES
    WHEN fast_tf.close > slow_tf.close
    THEN BUY fast_tf
\`\`\`

The rule waits until both streams have observed their declared bar counts.

### Composing PCT RISK with bracket sizing

\`\`\`
DEFAULTS {
    SIZING = 0.25 PCT RISK    -- risk 0.25% of equity per trade
}

RULES
    WHEN <condition>
    THEN BUY gold
         BRACKET { STOP LOSS BY 12, TAKE PROFIT BY 24 }
\`\`\`

The risk fraction (0.0025), the stop distance (12), and the instrument's contract
size combine to determine the lot count at fill time.

## Testing patterns

Drive closed candles through a compiled strategy via the same hub-binding pattern
the existing parity tests use (`CrossStreamConditionTest`, `CloseEndToEndTest`).
For warmup-specific tests, count emitted `Signal`s and assert that the count
matches `candles_pumped - warmup_bars`.

Unit-testing `IS NULL`: construct an `ExprCompiler`, compile an `IsNull(Ref("nope"))`,
and evaluate against a context with no matching let or snapshot. The result is
`Value.Bool(true)`.

## Known limitations

- `WARMUP` counts live candles only. Historical prefetch ships with Phase 25
  (`qkt fetch`).
- Engine restart resets the warmup counter. Long-running strategies should set
  `WARMUP` large enough to tolerate occasional restarts, or rely on the Phase 25
  prefetch.
- A stream with `WARMUP` declared but no rule referencing it is silently
  accepted. There's no warning surface yet.
- `IS NULL` does not introduce three-valued logic. `Value.Undefined` still
  propagates through arithmetic and comparison operators as `Value.Undefined`;
  `IS NULL` is the only place where it materializes as a boolean.

## References

- Issue: [#52](https://github.com/elitekaycy/qkt/issues/52)
- Spec: `docs/superpowers/specs/2026-05-25-phase24-risk-sizing-primitives-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-phase24-risk-sizing-primitives.md`
- Predecessor: `docs/phases/phase-23.md`
```

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-24-risk-sizing-primitives.md
git commit -m "docs(phase): add phase 24 risk-sizing primitives changelog"
```

---

## Final checks

- [ ] **Full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **ktlint**

Run: `./gradlew ktlintCheck`
Expected: no formatting errors.

- [ ] **Pre-push checklist (per qkt skill §4)**

```bash
./gradlew build           # full build with all tests
git status                # working tree clean
git log --oneline dev..HEAD   # every commit message follows §3 conventions
grep -rEn 'TODO|FIXME|XXX' src/main/kotlin/com/qkt/dsl/  # any new TODOs need an issue link
```

- [ ] **Push**

```bash
git push -u origin phase24-risk-sizing-primitives
```

- [ ] **Open PR**

Per qkt skill §5, PR title format: `[phase 24] feat(dsl): add risk-sizing primitives`. Body uses the template from skill §5. `Closes #52` in the body.
