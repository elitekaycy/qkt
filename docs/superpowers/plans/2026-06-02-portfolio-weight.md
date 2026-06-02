# Portfolio WEIGHT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a `PORTFOLIO` declare a total `CAPITAL` and split it across child strategies with per-`RUN` `WEIGHT` fractions, so each child's `ACCOUNT.equity` resolves to its allocated slice of the book.

**Architecture:** Three layers, each independently testable. (1) Parse `CAPITAL` onto the portfolio header AST and `WEIGHT` onto each `RUN` rule, with all validation in `PortfolioAst.init` (the existing portfolio-validation seam — `require()` throws are already converted to `ParseResult.Failure` by `parsePortfolio`). (2) A pure `capitalAllocations(ast)` function turns `CAPITAL × weight` into a per-alias map. (3) `PortfolioDeployer` feeds each child's allocated capital into its `LiveSession` via a new `startingBalances` constructor param, which calls the existing `StrategyPnL.setStartingBalance` — the same seam `Backtest` already uses. `ACCOUNT.equity` reads `startingBalance + realized + unrealized`, so a child with no open P&L reports exactly its allocation.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, Gradle. Money math via `com.qkt.common.Money` (`BigDecimal`, `Money.SCALE`, `Money.ROUNDING`).

**Scope note — nested portfolios deferred.** The spec describes a nested portfolio receiving `CAPITAL = parent_allocated`. But `PortfolioLoader.kt:60` hard-rejects nested portfolios today (`"nested PORTFOLIO not supported in v1"`). Supporting nested WEIGHT means first building nested portfolios at all — a separate, larger effort. This plan covers **flat** weighted portfolios (CAPITAL on the header, WEIGHT on direct `RUN`s). Nested-capital propagation is a follow-up issue. Flagged here so it is a deliberate decision, not an omission.

---

## File Structure

**Modified:**
- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `CAPITAL`, `WEIGHT` enum entries (auto-registered as keywords via `KEYWORDS[lex.uppercase()]`).
- `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt` — add `capital: BigDecimal?` to `PortfolioAst`; `weight: BigDecimal?` to `WhenRun`/`AlwaysRun`; weight validation in `PortfolioAst.init`.
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — parse `CAPITAL` in the portfolio header (`parsePortfolio`); parse `WEIGHT` on `RUN` (`parsePortfolioRule`) via a shared `parseOptionalWeight()` helper.
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — add `startingBalances: Map<String, BigDecimal>` constructor param; apply it to `StrategyPnL` in `start()`.
- `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt` — compute allocations once in `deploy()`; thread each child's allocated capital into its `LiveSession`.

**Created:**
- `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioAllocation.kt` — pure `capitalAllocations(ast): Map<String, BigDecimal>`.
- `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioAllocationTest.kt` — allocation math + no-weight passthrough.
- `src/test/resources/dsl/portfolio_weighted.qkt`, `weighted_child_a.qkt`, `weighted_child_b.qkt` — deployer E2E fixtures.

**Test files extended:**
- `src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt` — `CAPITAL`/`WEIGHT` lex as keywords.
- `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt` — parse + validation cases.
- `src/test/kotlin/com/qkt/app/LiveSessionTest.kt` — `startingBalances` → equity.
- `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerE2ETest.kt` — weighted portfolio → per-child equity.

---

## Task 1: Tokens — `CAPITAL` and `WEIGHT` keywords

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt:38-42`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt` (inside the class, after the existing test):

```kotlin
    @Test
    fun `CAPITAL and WEIGHT lex as keyword tokens`() {
        val tokens = Lexer("CAPITAL WEIGHT capital weight").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.CAPITAL,
            TokenKind.WEIGHT,
            TokenKind.CAPITAL,
            TokenKind.WEIGHT,
            TokenKind.EOF,
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.dsl.parse.LexerPortfolioTest"`
Expected: COMPILE FAIL — `TokenKind.CAPITAL` and `TokenKind.WEIGHT` are unresolved references.

- [ ] **Step 3: Add the enum entries**

In `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`, change the portfolio token group (lines 38-42):

```kotlin
    PORTFOLIO,
    IMPORT,
    AS,
    RUN,
    HOLD,
    CAPITAL,
    WEIGHT,
```

No other change is needed: the `Lexer` builds its keyword table from `TokenKind.values()` and looks up `KEYWORDS[lex.uppercase()]` (`Lexer.kt:251-287`), so both names become case-insensitive keywords automatically.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.dsl.parse.LexerPortfolioTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerPortfolioTest.kt
git commit -m "feat(dsl): add CAPITAL and WEIGHT keyword tokens"
```

---

## Task 2: AST fields — `capital` on header, `weight` on RUN

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt:3-9,49-56`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`

Fields are nullable with `null` defaults so every existing positional construction (`PortfolioAst(name, version, streams, imports, rules)`, `AlwaysRun("a")`, `WhenRun(cond, "a")`) keeps compiling and existing equality assertions stay green.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`:

```kotlin
    @Test
    fun `PORTFOLIO with CAPITAL and per-RUN WEIGHT parses`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO book VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b WEIGHT 0.4
                """.trimIndent(),
            )
        assertThat(ast.capital).isEqualByComparingTo(java.math.BigDecimal("100000"))
        assertThat((ast.rules[0] as AlwaysRun).alias).isEqualTo("a")
        assertThat((ast.rules[0] as AlwaysRun).weight).isEqualByComparingTo(java.math.BigDecimal("0.6"))
        assertThat((ast.rules[1] as AlwaysRun).weight).isEqualByComparingTo(java.math.BigDecimal("0.4"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`
Expected: COMPILE FAIL — `PortfolioAst.capital` and `AlwaysRun.weight` are unresolved (parser does not yet populate them either, but this step fails at compile first).

- [ ] **Step 3: Add the AST fields**

In `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt`, add the import at the top of the file (after the `package` line):

```kotlin
package com.qkt.dsl.ast

import java.math.BigDecimal
```

Change `PortfolioAst`'s constructor (add `capital` as the last parameter):

```kotlin
data class PortfolioAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
    val capital: BigDecimal? = null,
) {
```

Change `WhenRun` and `AlwaysRun` (add `weight` as the last parameter):

```kotlin
data class WhenRun(
    val cond: ExprAst,
    val alias: String,
    val weight: BigDecimal? = null,
) : PortfolioRule

data class AlwaysRun(
    val alias: String,
    val weight: BigDecimal? = null,
) : PortfolioRule
```

Leave the `init` block unchanged in this task — validation lands in Task 4.

- [ ] **Step 4: Run test to verify it still fails (now for the right reason)**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`
Expected: FAIL — the test now compiles but `parsePortfolioText` errors with `"parse failed: ..."` because the parser does not yet consume `CAPITAL`/`WEIGHT` (the `RUN a` parse stops before `WEIGHT 0.6`, leaving stray tokens). This confirms the fields exist and the parser is the next gap.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt
git commit -m "feat(dsl): add capital and weight fields to portfolio AST"
```

---

## Task 3: Parser — consume `CAPITAL` and `WEIGHT`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt:133-185,203-220`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt` (the test from Task 2)

- [ ] **Step 1: Confirm the Task 2 test is the failing test**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest.PORTFOLIO with CAPITAL and per-RUN WEIGHT parses"`
Expected: FAIL — parse failure / stray tokens (from Task 2 Step 4).

- [ ] **Step 2: Parse `CAPITAL` in the header**

In `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`, in `parsePortfolio()`, add a `capital` var beside the existing header vars (currently lines 134-135):

```kotlin
    internal fun parsePortfolio(): ParseResult<com.qkt.dsl.ast.PortfolioAst> {
        var name = "_unparsed"
        var version = 0
        var capital: java.math.BigDecimal? = null
        try {
            expect(TokenKind.PORTFOLIO, "expected PORTFOLIO")
            name = expect(TokenKind.IDENT, "expected portfolio name").lexeme
            expect(TokenKind.VERSION, "expected VERSION")
            val v = expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: error("VERSION must be an integer, got '${v.lexeme}'")
            if (peek().kind == TokenKind.CAPITAL) {
                advance()
                val capTok = expect(TokenKind.NUMBER, "expected number after CAPITAL")
                capital = capTok.lexeme.toBigDecimalOrNull()
                    ?: error("CAPITAL must be a number, got '${capTok.lexeme}'")
            }
        } catch (_: ParseException) {
            synchronize()
        }
```

Then pass `capital` into the `PortfolioAst` construction (currently lines 170-173):

```kotlin
        return try {
            ParseResult.Success(
                com.qkt.dsl.ast
                    .PortfolioAst(name, version, streams, imports, rules, capital),
            )
        } catch (e: IllegalArgumentException) {
```

(The `catch (IllegalArgumentException)` that wraps construction is already present at lines 174-184 and will surface Task 4's validation as `ParseResult.Failure`.)

- [ ] **Step 3: Parse `WEIGHT` on each RUN**

In `parsePortfolioRule()` (currently lines 203-220), add a shared helper and use it in both branches:

```kotlin
    internal fun parsePortfolioRule(): com.qkt.dsl.ast.PortfolioRule =
        when (peek().kind) {
            TokenKind.WHEN -> {
                advance()
                val cond = parseExpr()
                expect(TokenKind.RUN, "expected RUN after WHEN expression")
                val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                com.qkt.dsl.ast
                    .WhenRun(cond, alias, parseOptionalWeight())
            }
            TokenKind.RUN -> {
                advance()
                val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                com.qkt.dsl.ast
                    .AlwaysRun(alias, parseOptionalWeight())
            }
            else -> error("expected WHEN or RUN, got '${peek().lexeme}'")
        }

    private fun parseOptionalWeight(): java.math.BigDecimal? =
        if (peek().kind == TokenKind.WEIGHT) {
            advance()
            val tok = expect(TokenKind.NUMBER, "expected number after WEIGHT")
            tok.lexeme.toBigDecimalOrNull() ?: error("WEIGHT must be a number, got '${tok.lexeme}'")
        } else {
            null
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`
Expected: PASS (all tests in the class, including the existing no-weight ones — `AlwaysRun("a")` still equals the parsed `AlwaysRun("a", null)`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt
git commit -m "feat(dsl): parse CAPITAL header and per-RUN WEIGHT"
```

---

## Task 4: Validation — sum, all-or-none, range, CAPITAL pairing

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt:10-33` (the `init` block)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`

Validation lives in `PortfolioAst.init` because that is the existing portfolio-validation seam — `parsePortfolio` already catches the `IllegalArgumentException` that `require()` throws and returns `ParseResult.Failure` (Parser.kt:174-184), exactly like the current alias/import checks.

Rules enforced:
- **All-or-none:** if any `RUN` has `WEIGHT`, every `RUN` must.
- **CAPITAL required when weighted:** weights present ⇒ `CAPITAL` present.
- **No stray CAPITAL:** `CAPITAL` present ⇒ at least one weight (else it is a silent no-op — rejected, per the no-silent-failures rule).
- **Range:** each weight in `(0, 1]`.
- **Sum:** weights sum to `≤ 1.0` (no implicit leverage; a sum `< 1.0` is allowed — the remainder is unallocated reserve).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt`:

```kotlin
    @Test
    fun `PORTFOLIO weights summing over one rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.7
                    RUN b WEIGHT 0.5
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("must sum to <= 1.0")
    }

    @Test
    fun `PORTFOLIO weights summing under one allowed`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO reserve VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.5
                    RUN b WEIGHT 0.3
                """.trimIndent(),
            )
        assertThat(ast.capital).isEqualByComparingTo(java.math.BigDecimal("100000"))
    }

    @Test
    fun `PORTFOLIO partial WEIGHT rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("all-or-none")
    }

    @Test
    fun `PORTFOLIO WEIGHT without CAPITAL rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a WEIGHT 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("CAPITAL is required")
    }

    @Test
    fun `PORTFOLIO CAPITAL without any WEIGHT rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("no RUN carries WEIGHT")
    }

    @Test
    fun `PORTFOLIO WEIGHT out of range rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a WEIGHT 1.5
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("must be in (0, 1]")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`
Expected: the six new tests FAIL — the over-sum / partial / no-capital / stray-capital / out-of-range cases currently parse successfully (no validation yet), so `parsePortfolioFailure` casts a `ParseResult.Success` to `ParseResult.Failure` and throws `ClassCastException`.

- [ ] **Step 3: Add validation to `PortfolioAst.init`**

In `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt`, append to the end of the existing `init` block (after the alias-loop `require`, before the closing brace at line 33):

```kotlin
        val weights =
            rules.map { rule ->
                when (rule) {
                    is WhenRun -> rule.weight
                    is AlwaysRun -> rule.weight
                }
            }
        if (weights.any { it != null }) {
            require(weights.all { it != null }) {
                "PORTFOLIO: WEIGHT is all-or-none — every RUN must carry WEIGHT or none may"
            }
            require(capital != null) {
                "PORTFOLIO: CAPITAL is required on the header when any RUN carries WEIGHT"
            }
            for (w in weights.filterNotNull()) {
                require(w > BigDecimal.ZERO && w <= BigDecimal.ONE) {
                    "PORTFOLIO: each WEIGHT must be in (0, 1], got $w"
                }
            }
            val sum = weights.filterNotNull().fold(BigDecimal.ZERO) { acc, w -> acc.add(w) }
            require(sum <= BigDecimal.ONE) {
                "PORTFOLIO: total WEIGHT must sum to <= 1.0 (no implicit leverage), got $sum"
            }
        } else {
            require(capital == null) {
                "PORTFOLIO: CAPITAL declared but no RUN carries WEIGHT — nothing to allocate"
            }
        }
```

(`>`, `<=` on `BigDecimal` compile to `compareTo`, so `1.0` and `1` compare equal — no scale pitfalls.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`
Expected: PASS (all tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt
git commit -m "feat(dsl): validate portfolio WEIGHT sum, range, and CAPITAL pairing"
```

---

## Task 5: Allocation function — `CAPITAL × weight` per alias

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioAllocation.kt`
- Test: `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioAllocationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioAllocationTest.kt`:

```kotlin
package com.qkt.dsl.portfolio

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioAllocationTest {
    private fun portfolioAst(src: String) =
        ((Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.PortfolioFile).ast

    @Test
    fun `weighted portfolio allocates capital times weight per alias`() {
        val ast =
            portfolioAst(
                """
                PORTFOLIO book VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b WEIGHT 0.4
                """.trimIndent(),
            )
        val alloc = capitalAllocations(ast)
        assertThat(alloc["a"]).isEqualByComparingTo(java.math.BigDecimal("60000"))
        assertThat(alloc["b"]).isEqualByComparingTo(java.math.BigDecimal("40000"))
    }

    @Test
    fun `portfolio without weights allocates nothing`() {
        val ast =
            portfolioAst(
                """
                PORTFOLIO plain VERSION 1
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a
                """.trimIndent(),
            )
        assertThat(capitalAllocations(ast)).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.dsl.portfolio.PortfolioAllocationTest"`
Expected: COMPILE FAIL — `capitalAllocations` is unresolved.

- [ ] **Step 3: Write the allocation function**

Create `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioAllocation.kt`:

```kotlin
package com.qkt.dsl.portfolio

import com.qkt.common.Money
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import java.math.BigDecimal

/**
 * Capital each child receives under a weighted portfolio, keyed by import alias.
 *
 * A weighted portfolio declares a total `CAPITAL` on its header and a `WEIGHT`
 * fraction on every `RUN`; a child's allocation is `CAPITAL * weight`.
 * e.g. `CAPITAL 100000` with `RUN hs WEIGHT 0.6` -> `{"hs": 60000}`.
 *
 * A portfolio with no `CAPITAL`/`WEIGHT` returns an empty map: each child
 * self-sizes off its own basis, unchanged. The AST is already validated
 * (all-or-none, sum <= 1.0) by the time it reaches here, so this only does math.
 */
fun capitalAllocations(ast: PortfolioAst): Map<String, BigDecimal> {
    val capital = ast.capital ?: return emptyMap()
    val out = LinkedHashMap<String, BigDecimal>()
    for (rule in ast.rules) {
        val alias =
            when (rule) {
                is WhenRun -> rule.alias
                is AlwaysRun -> rule.alias
            }
        val weight =
            when (rule) {
                is WhenRun -> rule.weight
                is AlwaysRun -> rule.weight
            } ?: continue
        out[alias] = capital.multiply(weight).setScale(Money.SCALE, Money.ROUNDING)
    }
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.dsl.portfolio.PortfolioAllocationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/portfolio/PortfolioAllocation.kt src/test/kotlin/com/qkt/dsl/portfolio/PortfolioAllocationTest.kt
git commit -m "feat(dsl): add capitalAllocations for weighted portfolios"
```

---

## Task 6: `LiveSession` seam — `startingBalances` → `ACCOUNT.equity`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt:120-121,424`
- Test: `src/test/kotlin/com/qkt/app/LiveSessionTest.kt`

`ACCOUNT.equity` resolves through `StrategyPnLViewImpl.equity()` → `StrategyPnL.equityFor(id)` = `startingBalance + realized + unrealized` (`StrategyPnL.kt:67`). `LiveSession` never sets a starting balance today, so live equity starts at zero. This adds a `startingBalances` param applied right after `StrategyPnL` is built — the same `setStartingBalance` seam `Backtest.kt:102` already uses. With no open P&L, equity equals the starting balance, observable via `LiveSessionHandle.dailySummaryRows().equity`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/com/qkt/app/LiveSessionTest.kt`:

```kotlin
    @Test
    fun `startingBalances set the strategy equity basis`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val strategy = CapturingStrategy()
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                startingBalances = mapOf("test" to java.math.BigDecimal("60000")),
            ).start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()

        val equity = handle.dailySummaryRows().first { it.strategyId == "test" }.equity
        assertThat(equity).isEqualByComparingTo(java.math.BigDecimal("60000"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionTest.startingBalances set the strategy equity basis"`
Expected: COMPILE FAIL — `LiveSession` has no `startingBalances` parameter.

- [ ] **Step 3: Add the constructor param**

In `src/main/kotlin/com/qkt/app/LiveSession.kt`, add the parameter at the end of the constructor (after `scheduleHeartbeatIntervalMs` at line 120):

```kotlin
    private val scheduleHeartbeatIntervalMs: Long = 1000L,
    /**
     * Starting balance per strategy id, the basis for `ACCOUNT.equity`
     * (equity = starting balance + realized + unrealized). The portfolio deployer
     * supplies a child's allocated capital here (CAPITAL x WEIGHT) so the child sizes
     * off its slice of the book; standalone sessions leave it empty and equity starts
     * at zero. e.g. {"book:hs" -> 60000} -> the hs child's ACCOUNT.equity reads 60000.
     */
    private val startingBalances: Map<String, java.math.BigDecimal> = emptyMap(),
```

- [ ] **Step 4: Apply it in `start()`**

In `start()`, immediately after the `strategyPnL` is constructed (line 424):

```kotlin
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        startingBalances.forEach { (id, balance) -> strategyPnL.setStartingBalance(id, balance) }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionTest.startingBalances set the strategy equity basis"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LiveSession.kt src/test/kotlin/com/qkt/app/LiveSessionTest.kt
git commit -m "feat(engine): let LiveSession seed per-strategy equity basis"
```

---

## Task 7: `PortfolioDeployer` wiring + weighted E2E

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt:54-99,129-156`
- Create: `src/test/resources/dsl/portfolio_weighted.qkt`, `weighted_child_a.qkt`, `weighted_child_b.qkt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerE2ETest.kt`

- [ ] **Step 1: Create the fixtures**

Create `src/test/resources/dsl/portfolio_weighted.qkt`:

```
PORTFOLIO weighted_book VERSION 1 CAPITAL 100000
IMPORT 'weighted_child_a.qkt' AS a
IMPORT 'weighted_child_b.qkt' AS b
RULES
    RUN a WEIGHT 0.6
    RUN b WEIGHT 0.4
```

Create `src/test/resources/dsl/weighted_child_a.qkt` (the `WHEN btc.close > 1000000` guard never fires against the FakeSource's ~100 prices, so the child never opens a position and its equity stays at the allocated capital):

```
STRATEGY wa VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 1000000
    THEN BUY btc SIZING 0.1
```

Create `src/test/resources/dsl/weighted_child_b.qkt`:

```
STRATEGY wb VERSION 1
SYMBOLS
    eth = BACKTEST:ETHUSDT EVERY 1m
RULES
    WHEN eth.close > 1000000
    THEN BUY eth SIZING 0.1
```

- [ ] **Step 2: Write the failing test**

Add to `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerE2ETest.kt` (inside the class):

```kotlin
    @Test
    fun `weighted portfolio allocates capital times weight to each child's equity`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols -> FakeSource(ticksFor(symbols.first())) },
            )

        val portfolioPath = Path.of("src/test/resources/dsl/portfolio_weighted.qkt")
        val compiled = PortfolioLoader.load(portfolioPath)
        val record = deployer.deploy("weighted_book", compiled)

        try {
            val childrenByAlias = record.children.associateBy { it.childMeta?.alias }
            val childA = childrenByAlias["a"] ?: error("child 'a' missing")
            val childB = childrenByAlias["b"] ?: error("child 'b' missing")

            // ACCOUNT.equity resolves through the same equityFor() that dailySummaryRows
            // reads; with no open position, each child's equity is exactly its allocation.
            assertThat(childA.live.dailySummaryRows().first().equity)
                .isEqualByComparingTo(BigDecimal("60000"))
            assertThat(childB.live.dailySummaryRows().first().equity)
                .isEqualByComparingTo(BigDecimal("40000"))
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.daemon.portfolio.PortfolioDeployerE2ETest.weighted portfolio allocates capital times weight to each child's equity"`
Expected: FAIL — each child's equity reads `0.00` (allocations are not yet wired into the child sessions), not `60000`/`40000`.

- [ ] **Step 4: Wire allocations through the deployer**

In `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt`, add the import (with the other `com.qkt.dsl.portfolio` imports near line 14-15):

```kotlin
import com.qkt.dsl.portfolio.capitalAllocations
```

In `deploy()`, compute the allocations once and pass each child's slice into `createChild` (replace the loop at lines 60-65):

```kotlin
        try {
            val allocations = capitalAllocations(compiled.ast)
            for (compiledChild in compiled.children) {
                val (handle, wrapper) =
                    createChild(portfolioName, compiledChild, allocations[compiledChild.alias])
                children.add(handle)
                childWrappers.add(wrapper)
            }
```

Change the `createChild` signature (lines 96-99) to accept the allocated capital:

```kotlin
    private fun createChild(
        portfolioName: String,
        compiledChild: CompiledChild,
        allocatedCapital: java.math.BigDecimal? = null,
    ): Pair<StrategyHandle, ChildHandle> {
```

Pass it to the child `LiveSession` (add to the `LiveSession(...)` argument list, after `notifyEvents = notifyEvents,` at line 155):

```kotlin
                notifyEvents = notifyEvents,
                startingBalances =
                    allocatedCapital?.let { mapOf(compiledChild.strategyId to it) } ?: emptyMap(),
            ).start()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.daemon.portfolio.PortfolioDeployerE2ETest"`
Expected: PASS (both the new weighted test and the existing two-children test — the latter passes `null` allocation, so `startingBalances` stays empty and equity is unchanged).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt \
        src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerE2ETest.kt \
        src/test/resources/dsl/portfolio_weighted.qkt \
        src/test/resources/dsl/weighted_child_a.qkt \
        src/test/resources/dsl/weighted_child_b.qkt
git commit -m "feat(daemon): allocate portfolio CAPITAL to child equity by WEIGHT"
```

---

## Task 8: Full suite + ktlint

**Files:** none (verification only)

- [ ] **Step 1: Run ktlint**

Run: `./gradlew ktlintCheck`
Expected: PASS. If it fails on the edited files, run `./gradlew ktlintFormat`, re-inspect the diff, and re-commit the formatting into the most recent relevant commit. (Per project memory: run ktlint locally before push to avoid CI round-trips.)

- [ ] **Step 2: Run the affected suites**

Run: `./gradlew test --tests "com.qkt.dsl.parse.*Portfolio*" --tests "com.qkt.dsl.portfolio.*" --tests "com.qkt.app.LiveSessionTest" --tests "com.qkt.cli.daemon.portfolio.*"`
Expected: PASS.

- [ ] **Step 3: Push and let CI verify the full build**

Per project convention (push and let CI run the full build rather than blocking locally):

```bash
git push -u origin issue69-portfolio-weight
```

Then open the PR targeting `dev` with `Closes #69` in the body.

---

## Follow-ups (out of scope for #69)

- **Migrate `hedge-straddle.qkt` to `ACCOUNT.equity` sizing.** The spec's motivating example replaces `SIZING RISK $ (50000 * 0.007 * ...)` with `SIZING RISK $ (ACCOUNT.equity * 0.007 * ...)`. This is a **live-strategy** change: it requires running the same config in a backtest and confirming the result is within tolerance (quant standards), and deploying only in a no-OCO window. Do it as a separate change once WEIGHT ships, not in this engine PR.
- **Nested portfolios** (#223-adjacent). A child that is itself a `PORTFOLIO` should receive `CAPITAL = parent_allocated` and split recursively. Blocked today by `PortfolioLoader.kt:60` rejecting nested portfolios outright. File a separate issue; it depends on building nested-portfolio loading first.
- **Dynamic equity** (track realized P&L instead of static allocation). The spec fixes v1 to static allocated capital for determinism; dynamic tracking is a later refinement.

---

## Self-Review

**Spec coverage:**
- Syntax `CAPITAL <n>` on header → Task 1 (token) + Task 3 (parse). ✓
- Syntax `WEIGHT <fraction>` on RUN → Task 1 + Task 3. ✓
- `CAPITAL × weight` allocation → Task 5. ✓
- `ACCOUNT.equity` resolves to allocated capital → Task 6 (seam) + Task 7 (deployer). ✓
- Validation: sum ≤ 1.0, partial rejected, CAPITAL-required-if-weighted, range (0,1] → Task 4. ✓
- Backward compat (no WEIGHT/CAPITAL unchanged) → Task 5 (empty map) + Task 7 (existing two-children test stays green). ✓
- Nested portfolios → explicitly deferred (scope note + follow-up), with the blocking reason cited. ✓ (gap surfaced, not silently dropped)

**Placeholder scan:** No TBD/TODO/"add validation"/"handle edge cases". Every code step shows full code. ✓

**Type consistency:** `capital: BigDecimal?` / `weight: BigDecimal?` (AST) ↔ `capitalAllocations(ast): Map<String, BigDecimal>` (Task 5) ↔ `startingBalances: Map<String, BigDecimal>` (Task 6) ↔ `allocatedCapital: BigDecimal?` keyed by `compiledChild.strategyId` (Task 7). `equityFor`/`setStartingBalance`/`dailySummaryRows().equity` names match the source read in investigation. ✓
