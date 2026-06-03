# Portfolio Import-Time Parameter Overrides (#223) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a portfolio retune a child strategy's named scalar parameters at import — `RUN child WEIGHT 0.6 OVERRIDE { riskPct = 0.005 }` — without copying the child file.

**Architecture:** A new `PARAM name = <literal>` declaration in the strategy DSL marks overridable scalars. `OVERRIDE { key = literal }` rides on a portfolio `RUN` (after `WEIGHT`). Params are resolved by pure compile-time AST substitution inside `AstCompiler.compile` (covers standalone + portfolio children); the portfolio loader replaces a param's default with the override value before compile. No runtime behavior, no determinism impact.

**Tech Stack:** Kotlin, the qkt hand-written DSL lexer/parser/compiler (`com.qkt.dsl`), JUnit 5 + AssertJ.

Spec: `docs/superpowers/specs/2026-06-03-issue223-portfolio-param-overrides-design.md`

---

## File Structure

- `dsl/parse/TokenKind.kt` — add `PARAM`, `OVERRIDE` enum members (lexer auto-registers keywords from the enum).
- `dsl/ast/StrategyAst.kt` — new `ParamDecl(name, value)`; add `params: List<ParamDecl>` to `StrategyAst`; param/let name-collision validation.
- `dsl/ast/Portfolio.kt` — add `overrides: Map<String, ExprAst>` to `WhenRun`/`AlwaysRun`; relax the unique-import-paths guard.
- `dsl/parse/Parser.kt` — `parseLiteral()` helper; `parseParams()`; collect params in `parseStrategy`; `parseOptionalOverrides()`; thread overrides into `parsePortfolioRule`.
- `dsl/compile/ParamSubstitution.kt` (new) — substitute `Ref(param) → literal` across the whole strategy AST (conditions, actions, lets, schedules).
- `dsl/compile/AstCompiler.kt` — call `ParamSubstitution.apply(ast)` at the top of `compile`.
- `dsl/portfolio/PortfolioLoader.kt` — apply per-alias overrides to child AST + validate (unknown key, type mismatch) before compile.
- Tests: `dsl/parse/ParserStrategyParamTest.kt`, `dsl/parse/ParserPortfolioTest.kt` (extend), `dsl/compile/ParamSubstitutionTest.kt`, `dsl/compile/AstCompilerParamTest.kt`, `dsl/portfolio/PortfolioLoaderOverrideTest.kt`.

Run all tests with: `./gradlew --offline test --tests "<fully.qualified.TestClass>"`. ktlint: `./gradlew --offline ktlintFormat ktlintCheck`. Do NOT run the full build.

---

## Task 1: Tokens + `ParamDecl` AST

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`
- Test: `src/test/kotlin/com/qkt/dsl/ast/ParamDeclTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/dsl/ast/ParamDeclTest.kt
package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamDeclTest {
    @Test
    fun `holds a literal value`() {
        val p = ParamDecl("riskPct", NumLit(BigDecimal("0.01")))
        assertThat(p.name).isEqualTo("riskPct")
        assertThat(p.value).isEqualTo(NumLit(BigDecimal("0.01")))
    }

    @Test
    fun `rejects a non-literal value`() {
        assertThatThrownBy { ParamDecl("x", IndicatorCall("ema", emptyList())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PARAM")
    }

    @Test
    fun `rejects a blank name`() {
        assertThatThrownBy { ParamDecl("", NumLit(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StrategyAst rejects a param colliding with a let`() {
        assertThatThrownBy {
            StrategyAst(
                name = "s", version = 1, streams = emptyList(), constants = emptyList(),
                lets = listOf(LetDecl("x", NumLit(BigDecimal.ONE))),
                params = listOf(ParamDecl("x", NumLit(BigDecimal.TEN))),
                defaults = null, rules = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("x")
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.dsl.ast.ParamDeclTest"`. Expected: compile error (`ParamDecl` / `params` param don't exist).

- [ ] **Step 3: Add the tokens.** In `TokenKind.kt`, add to the enum (near `LET`, line ~10): `PARAM,` and (near `WEIGHT`, line ~44) `OVERRIDE,`.

- [ ] **Step 4: Add `ParamDecl` + `params`.** In `StrategyAst.kt`, after `LetDecl` (line ~56):

```kotlin
data class ParamDecl(
    val name: String,
    val value: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "ParamDecl.name must not be blank" }
        require(value is NumLit || value is BoolLit || value is StringLit) {
            "PARAM '$name' must be a literal value (number, true/false, or string)"
        }
    }
}
```

Add `val params: List<ParamDecl> = emptyList(),` to the `StrategyAst` data class (after `lets`), and add to its `init`:

```kotlin
val paramNames = params.map { it.name }
require(paramNames.distinct().size == paramNames.size) { "duplicate PARAM name in: $paramNames" }
val letNames = lets.map { it.name }.toSet()
for (n in paramNames) require(n !in letNames) { "PARAM '$n' collides with a LET of the same name" }
```

Add the imports `NumLit`, `BoolLit`, `StringLit` are already in package `com.qkt.dsl.ast` (same package — no import needed).

- [ ] **Step 5: Run to verify pass** — same command. Expected: 4 tests PASS. Then `./gradlew --offline ktlintFormat ktlintCheck`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt src/test/kotlin/com/qkt/dsl/ast/ParamDeclTest.kt
git commit -m "feat(dsl): PARAM/OVERRIDE tokens and ParamDecl AST"
```

---

## Task 2: Parse `PARAM` declarations in a strategy

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserStrategyParamTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/dsl/parse/ParserStrategyParamTest.kt
package com.qkt.dsl.parse

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.ParsedFile
import com.qkt.dsl.ast.StringLit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStrategyParamTest {
    private fun parse(src: String) =
        (Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.StrategyFile

    @Test
    fun `parses PARAM declarations of each scalar type`() {
        val s = parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS gold = mt5:XAUUSD @ 5m
            PARAM riskPct = 0.01
            PARAM enabled = TRUE
            PARAM mode = "fast"
            RULES
              WHEN gold.close > 0 THEN LOG "x"
            """.trimIndent(),
        ).ast
        assertThat(s.params.map { it.name }).containsExactly("riskPct", "enabled", "mode")
        assertThat(s.params[0].value).isEqualTo(NumLit(BigDecimal("0.01")))
        assertThat(s.params[1].value).isEqualTo(BoolLit(true))
        assertThat(s.params[2].value).isEqualTo(StringLit("fast"))
    }

    @Test
    fun `rejects a non-literal PARAM default`() {
        val r = Parser(Lexer(
            "STRATEGY s VERSION 1\nSYMBOLS gold = mt5:XAUUSD @ 5m\nPARAM x = ema(gold.close, 9)\nRULES\nWHEN gold.close > 0 THEN LOG \"x\"",
        ).tokenize()).parseFile()
        assertThat(r).isInstanceOf(ParseResult.Failure::class.java)
    }
}
```

(Verify the exact `SYMBOLS`/stream syntax against an existing passing test such as `editor/nvim/test/example.qkt` or `src/test/resources/stress/portfolio-child-b.qkt`, and match it — adjust the fixture lines if the stream syntax differs.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.dsl.parse.ParserStrategyParamTest"`. Expected: FAIL (`params` always empty).

- [ ] **Step 3: Add a literal parser + a PARAM parser.** In `Parser.kt`, add:

```kotlin
private fun parseLiteral(): ExprAst {
    val negate = match(TokenKind.MINUS)
    return when (peek().kind) {
        TokenKind.NUMBER -> {
            val t = advance()
            val n = t.lexeme.toBigDecimalOrNull() ?: error("expected a number literal, got '${t.lexeme}'")
            NumLit(if (negate) n.negate() else n)
        }
        TokenKind.TRUE -> { advance(); if (negate) error("cannot negate a boolean"); BoolLit(true) }
        TokenKind.FALSE -> { advance(); if (negate) error("cannot negate a boolean"); BoolLit(false) }
        TokenKind.STRING -> { advance(); if (negate) error("cannot negate a string"); StringLit(prev().lexeme) }
        else -> error("expected a literal value (number, TRUE/FALSE, or string), got '${peek().lexeme}'")
    }
}

private fun parseParams(): List<ParamDecl> {
    val out = mutableListOf<ParamDecl>()
    expect(TokenKind.PARAM, "expected PARAM")
    do {
        val name = expect(TokenKind.IDENT, "expected param name").lexeme
        expect(TokenKind.EQ, "expected '=' after param name")
        out.add(ParamDecl(name, parseLiteral()))
    } while (match(TokenKind.COMMA))
    return out
}
```

(Confirm helper names against the file: it already uses `match`, `expect`, `advance`, `peek`, `error`. If a `prev()` helper does not exist, capture the string token in a local instead: `val t = advance(); StringLit(t.lexeme)`.)

- [ ] **Step 4: Collect params in `parseStrategy`.** In `parseStrategy` (Parser.kt ~258-301), parse params right before the `lets` block (multiple PARAM lines allowed):

```kotlin
val params = run {
    val acc = mutableListOf<ParamDecl>()
    while (peek().kind == TokenKind.PARAM) {
        tryParse { parseParams() }?.let { acc.addAll(it) }
    }
    acc
}
```

Then pass `params = params,` into the `StrategyAst(...)` constructor (after `lets = lets,`). Add imports for `ParamDecl` and the literal AST types if not present.

- [ ] **Step 5: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserStrategyParamTest.kt
git commit -m "feat(dsl): parse PARAM declarations"
```

---

## Task 3: Parse `OVERRIDE` on RUN + allow duplicate import paths

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt` (extend)

- [ ] **Step 1: Write the failing test** (add to `ParserPortfolioTest`; reuse its existing `parsePortfolioText` helper):

```kotlin
@Test
fun `parses OVERRIDE block after WEIGHT`() {
    val p = parsePortfolioText(
        """
        PORTFOLIO book VERSION 1 CAPITAL 100000
        IMPORT 'child.qkt' AS a
        IMPORT 'child.qkt' AS b
        RULES
          RUN a WEIGHT 0.6 OVERRIDE { riskPct = 0.008, threshold = 30 }
          RUN b WEIGHT 0.4
        """.trimIndent(),
    )
    val a = p.rules[0] as com.qkt.dsl.ast.AlwaysRun
    assertThat(a.overrides.keys).containsExactlyInAnyOrder("riskPct", "threshold")
    assertThat(a.overrides["riskPct"]).isEqualTo(com.qkt.dsl.ast.NumLit(java.math.BigDecimal("0.008")))
    val b = p.rules[1] as com.qkt.dsl.ast.AlwaysRun
    assertThat(b.overrides).isEmpty()
}
```

(The two `IMPORT`s of `child.qkt` also exercise the duplicate-path relaxation — this test fails today on that guard before `OVERRIDE` even parses.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.dsl.parse.ParserPortfolioTest"`. Expected: FAIL (duplicate-path guard + no `overrides`).

- [ ] **Step 3: AST changes.** In `Portfolio.kt`:
  - Add `val overrides: Map<String, ExprAst> = emptyMap(),` to both `WhenRun` and `AlwaysRun`.
  - Delete the duplicate-path `require` (lines ~21-24): remove
    ```kotlin
    val paths = imports.map { it.path }
    require(paths.distinct().size == paths.size) { "PORTFOLIO import paths must be unique (no overrides in v1): $paths" }
    ```
    Keep the unique-*aliases* require above it.

- [ ] **Step 4: Parse OVERRIDE.** In `Parser.kt`, add a helper and thread it through `parsePortfolioRule`:

```kotlin
private fun parseOptionalOverrides(): Map<String, ExprAst> {
    if (peek().kind != TokenKind.OVERRIDE) return emptyMap()
    advance()
    expect(TokenKind.LBRACE, "expected '{' after OVERRIDE")
    val out = LinkedHashMap<String, ExprAst>()
    if (peek().kind != TokenKind.RBRACE) {
        do {
            val key = expect(TokenKind.IDENT, "expected override key").lexeme
            if (out.containsKey(key)) error("duplicate OVERRIDE key '$key'")
            expect(TokenKind.EQ, "expected '=' after override key")
            out[key] = parseLiteral()
        } while (match(TokenKind.COMMA))
    }
    expect(TokenKind.RBRACE, "expected '}' to close OVERRIDE")
    return out
}
```

In `parsePortfolioRule` (Parser.kt ~210-227), capture weight then overrides and pass both:

```kotlin
TokenKind.WHEN -> {
    advance()
    val cond = parseExpr()
    expect(TokenKind.RUN, "expected RUN after WHEN expression")
    val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
    val weight = parseOptionalWeight()
    com.qkt.dsl.ast.WhenRun(cond, alias, weight, parseOptionalOverrides())
}
TokenKind.RUN -> {
    advance()
    val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
    val weight = parseOptionalWeight()
    com.qkt.dsl.ast.AlwaysRun(alias, weight, parseOptionalOverrides())
}
```

- [ ] **Step 5: Run to verify pass** — same command (run the whole `ParserPortfolioTest` to confirm no regression). ktlintFormat + ktlintCheck.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/Portfolio.kt src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserPortfolioTest.kt
git commit -m "feat(dsl): parse RUN OVERRIDE and allow duplicate import paths"
```

---

## Task 4: Param substitution in the compiler

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ParamSubstitution.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/AstCompilerParamTest.kt`

**Approach:** `ParamSubstitution.apply(ast)` returns a copy of `ast` with every `Ref(name)` whose name is a `PARAM` replaced by that param's literal value, across ALL expression positions — rule conditions, `LET` right-hand sides, schedule actions, AND rule actions (the action AST embeds `ExprAst` in `SIZING`, brackets, `LOG` args, etc.). Then `params = emptyList()`. Expression substitution mirrors `LetResolver`'s `when (expr)` walk (`dsl/compile/LetResolver.kt`); action substitution requires walking the action AST (`com.qkt.dsl.ast.ActionAst` and its subtypes — read them) and substituting each embedded expression. The compiler is otherwise unchanged: it now sees a param-free AST with literals in place. This is what makes a PARAM usable inside a `SIZING` action even though `LetResolver` does not run on actions.

- [ ] **Step 1: Write the failing test** — drives both condition AND action coverage:

```kotlin
// src/test/kotlin/com/qkt/dsl/compile/AstCompilerParamTest.kt
package com.qkt.dsl.compile

import com.qkt.dsl.ast.ParsedFile
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerParamTest {
    private fun ast(src: String) =
        ((Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.StrategyFile).ast

    @Test
    fun `a PARAM used in a condition and a SIZING action compiles to its default`() {
        // Uses riskPct in a SIZING action (where LetResolver does NOT run) and rsiPeriod in a condition.
        val a = ast(
            """
            STRATEGY s VERSION 1
            SYMBOLS gold = mt5:XAUUSD @ 5m
            PARAM riskPct   = 0.01
            PARAM rsiPeriod = 14
            RULES
              WHEN rsi(gold.close, rsiPeriod) < 35
                THEN BUY gold SIZING RISK $ (ACCOUNT.equity * riskPct)
            """.trimIndent(),
        )
        // Compiles without "bare Ref should have been substituted" — params are substituted to literals.
        val strategy = AstCompiler().compile(a)
        assertThat(strategy).isNotNull()
    }
}
```

(Confirm the exact `BUY ... SIZING RISK $ (...)` action syntax against an existing passing strategy fixture/test and match it; the point of the test is a param referenced *inside the action*.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.dsl.compile.AstCompilerParamTest"`. Expected: FAIL — either a parse/compile error, or `ExprCompiler` "bare Ref riskPct should have been substituted" (proving actions aren't substituted yet).

- [ ] **Step 3: Implement `ParamSubstitution`.** Create `ParamSubstitution.kt` with `fun apply(ast: StrategyAst): StrategyAst`. Build `val values: Map<String, ExprAst> = ast.params.associate { it.name to it.value }`; if empty, return `ast` unchanged. Provide `private fun subst(e: ExprAst): ExprAst` mirroring `LetResolver.resolve` but replacing `Ref(name)` with `values[name]` when present (else return the `Ref` unchanged — it may be a `LET` or stream ref). Provide `private fun subst(a: ActionAst): ActionAst` that copies each action subtype substituting its embedded `ExprAst` fields (read `com.qkt.dsl.ast` action types — `Buy`, `Sell`, `Close`, `Block`, `OcoEntry`, `Log`, sizing/bracket option holders — and `.copy(...)` with substituted exprs). Return `ast.copy(params = emptyList(), lets = ast.lets.map { it.copy(expr = subst(it.expr)) }, rules = ast.rules.map { substRule(it) }, schedules = ast.schedules.map { substSchedule(it) })`.

- [ ] **Step 4: Wire into `AstCompiler`.** At the top of `compile` (AstCompiler.kt:27), replace the parameter use with a substituted AST:

```kotlin
fun compile(rawAst: StrategyAst): Strategy {
    val ast = ParamSubstitution.apply(rawAst)
    // ... existing body unchanged, using `ast`
```

- [ ] **Step 5: Run to verify pass** — same command. Expected: PASS. Also run `./gradlew --offline test --tests "com.qkt.dsl.compile.*"` to confirm no compiler regression. ktlintFormat + ktlintCheck.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ParamSubstitution.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/AstCompilerParamTest.kt
git commit -m "feat(dsl): substitute PARAMs to literals before compile"
```

---

## Task 5: Apply overrides + validate in the portfolio loader

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt`
- Test: `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioLoaderOverrideTest.kt`

**Approach:** Before compiling each child (`AstCompiler().compile(childAst)`, PortfolioLoader.kt:66), look up that alias's overrides from `ast.rules`, validate, and replace the child's `ParamDecl` defaults with the override literals. Build `aliasOverrides: Map<String, Map<String, ExprAst>>` from rules (`WhenRun`/`AlwaysRun` `alias`/`overrides`); if one alias appears in multiple rules with differing non-empty overrides, error. Apply per import.

- [ ] **Step 1: Write the failing test** — write child + portfolio `.qkt` files to a JUnit `@TempDir` (mirror `PortfolioLoaderTest`), then load:

```kotlin
// src/test/kotlin/com/qkt/dsl/portfolio/PortfolioLoaderOverrideTest.kt
package com.qkt.dsl.portfolio

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class PortfolioLoaderOverrideTest {
    private val child =
        """
        STRATEGY meanrev VERSION 1
        SYMBOLS gold = mt5:XAUUSD @ 5m
        PARAM riskPct = 0.01
        RULES
          WHEN gold.close > 0 THEN BUY gold SIZING RISK $ (ACCOUNT.equity * riskPct)
        """.trimIndent()

    private fun write(dir: Path, name: String, text: String): Path =
        dir.resolve(name).also { java.nio.file.Files.writeString(it, text) }

    @Test
    fun `the same child under two aliases gets different param values`(@TempDir dir: Path) {
        write(dir, "meanrev.qkt", child)
        val pf = write(
            dir, "book.qkt",
            """
            PORTFOLIO book VERSION 1 CAPITAL 100000
            IMPORT 'meanrev.qkt' AS aggressive
            IMPORT 'meanrev.qkt' AS conservative
            RULES
              RUN aggressive   WEIGHT 0.6 OVERRIDE { riskPct = 0.008 }
              RUN conservative WEIGHT 0.4 OVERRIDE { riskPct = 0.003 }
            """.trimIndent(),
        )
        val compiled = PortfolioLoader.load(pf)
        // The child AST retained on each CompiledChild reflects the override (params replaced or substituted).
        val agg = compiled.children.first { it.alias == "aggressive" }
        val con = compiled.children.first { it.alias == "conservative" }
        assertThat(effectiveRiskPct(agg)).isEqualByComparingTo("0.008")
        assertThat(effectiveRiskPct(con)).isEqualByComparingTo("0.003")
    }

    @Test
    fun `unknown override key is an error`(@TempDir dir: Path) {
        write(dir, "meanrev.qkt", child)
        val pf = write(dir, "book.qkt",
            "PORTFOLIO book VERSION 1\nIMPORT 'meanrev.qkt' AS a\nRULES\nRUN a OVERRIDE { nope = 1 }")
        assertThatThrownBy { PortfolioLoader.load(pf) }
            .hasMessageContaining("nope")
    }

    @Test
    fun `type-mismatched override is an error`(@TempDir dir: Path) {
        write(dir, "meanrev.qkt", child)
        val pf = write(dir, "book.qkt",
            "PORTFOLIO book VERSION 1\nIMPORT 'meanrev.qkt' AS a\nRULES\nRUN a OVERRIDE { riskPct = \"hi\" }")
        assertThatThrownBy { PortfolioLoader.load(pf) }
            .hasMessageContaining("riskPct")
    }

    // Reads the effective riskPct off the retained child AST (the ParamDecl value after override,
    // or — if you substitute eagerly in the loader — the literal in the action). Implement against
    // whatever CompiledChild.ast exposes; assert the BigDecimal value.
    private fun effectiveRiskPct(c: CompiledChild): java.math.BigDecimal =
        (c.ast.params.firstOrNull { it.name == "riskPct" }?.value as com.qkt.dsl.ast.NumLit).value
}
```

(Adjust `effectiveRiskPct` to match the chosen representation: if the loader replaces `ParamDecl` values before compile and retains that AST on `CompiledChild`, the `params` value is the override; confirm `CompiledChild.ast` is the post-override AST.)

- [ ] **Step 2: Run to verify it fails** — `./gradlew --offline test --tests "com.qkt.dsl.portfolio.PortfolioLoaderOverrideTest"`. Expected: FAIL.

- [ ] **Step 3: Implement override application + validation** in `PortfolioLoader.loadPortfolio`. After parsing `ast` and before the `ast.imports.map { ... }` loop, build:

```kotlin
val overridesByAlias: Map<String, Map<String, com.qkt.dsl.ast.ExprAst>> =
    ast.rules
        .mapNotNull { rule ->
            val (alias, ov) = when (rule) {
                is com.qkt.dsl.ast.WhenRun -> rule.alias to rule.overrides
                is com.qkt.dsl.ast.AlwaysRun -> rule.alias to rule.overrides
            }
            if (ov.isEmpty()) null else alias to ov
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (alias, list) ->
            val distinct = list.distinct()
            if (distinct.size > 1) error("conflicting OVERRIDE for alias '$alias'")
            distinct.first()
        }
```

Then inside the import loop, after `childAst` is parsed and before `AstCompiler().compile(childAst)`, transform:

```kotlin
val overrides = overridesByAlias[imp.alias].orEmpty()
val declared = childAst.params.associateBy { it.name }
for ((key, value) in overrides) {
    val decl = declared[key] ?: error("OVERRIDE: child '${imp.alias}' has no PARAM '$key'")
    require(value::class == decl.value::class) {
        "OVERRIDE: PARAM '$key' of '${imp.alias}' is ${decl.value::class.simpleName}, got ${value::class.simpleName}"
    }
}
val effectiveAst =
    if (overrides.isEmpty()) childAst
    else childAst.copy(params = childAst.params.map { p -> overrides[p.name]?.let { p.copy(value = it) } ?: p })
```

Then compile `effectiveAst` (not `childAst`) and store `ast = effectiveAst` on the `CompiledChild`.

- [ ] **Step 4: Run to verify pass** — same command, plus `./gradlew --offline test --tests "com.qkt.dsl.portfolio.PortfolioLoaderTest"` (no regression). ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt src/test/kotlin/com/qkt/dsl/portfolio/PortfolioLoaderOverrideTest.kt
git commit -m "feat(dsl): apply and validate portfolio param overrides"
```

---

## Task 6: Determinism guard + grammar docs

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/portfolio/PortfolioOverrideDeterminismTest.kt`
- Modify: any DSL grammar/reference doc that lists declarations (e.g. `docs/` DSL reference, if present — grep for where `LET`/`WEIGHT` are documented).

- [ ] **Step 1: Write the determinism test** — same portfolio file compiled twice yields identical compiled children, and an override produces the same AST as hand-editing the child literal:

```kotlin
// src/test/kotlin/com/qkt/dsl/portfolio/PortfolioOverrideDeterminismTest.kt
package com.qkt.dsl.portfolio

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class PortfolioOverrideDeterminismTest {
    @Test
    fun `override equals hand-edited child, and is stable across loads`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("child.qkt"),
            "STRATEGY meanrev VERSION 1\nSYMBOLS gold = mt5:XAUUSD @ 5m\nPARAM riskPct = 0.01\nRULES\nWHEN gold.close > 0 THEN BUY gold SIZING RISK $ (ACCOUNT.equity * riskPct)")
        Files.writeString(dir.resolve("hand.qkt"),
            "STRATEGY meanrev VERSION 1\nSYMBOLS gold = mt5:XAUUSD @ 5m\nPARAM riskPct = 0.008\nRULES\nWHEN gold.close > 0 THEN BUY gold SIZING RISK $ (ACCOUNT.equity * riskPct)")
        Files.writeString(dir.resolve("book.qkt"),
            "PORTFOLIO book VERSION 1 CAPITAL 100000\nIMPORT 'child.qkt' AS a\nRULES\nRUN a WEIGHT 1.0 OVERRIDE { riskPct = 0.008 }")
        Files.writeString(dir.resolve("hand-book.qkt"),
            "PORTFOLIO book VERSION 1 CAPITAL 100000\nIMPORT 'hand.qkt' AS a\nRULES\nRUN a WEIGHT 1.0")

        val viaOverride = PortfolioLoader.load(dir.resolve("book.qkt")).children.single()
        val viaHand = PortfolioLoader.load(dir.resolve("hand-book.qkt")).children.single()
        val reload = PortfolioLoader.load(dir.resolve("book.qkt")).children.single()

        assertThat(viaOverride.ast.params).isEqualTo(viaHand.ast.params)
        assertThat(viaOverride.ast.params).isEqualTo(reload.ast.params)
    }
}
```

- [ ] **Step 2: Run** — `./gradlew --offline test --tests "com.qkt.dsl.portfolio.PortfolioOverrideDeterminismTest"`. Expected: PASS (no production change needed if Tasks 4-5 are correct; if it fails, fix the substitution/override to be a pure function of the input files).

- [ ] **Step 3: Document.** Grep `grep -rln "WEIGHT" docs/` for a DSL reference; if one exists, add a short `PARAM` + `OVERRIDE` section mirroring the `WEIGHT` docs, with the worked example from the spec. If no DSL doc exists, skip (no placeholder doc).

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/portfolio/PortfolioOverrideDeterminismTest.kt docs/
git commit -m "test(dsl): determinism guard for portfolio param overrides"
```

---

## Final verification

- [ ] `./gradlew --offline test --tests "com.qkt.dsl.*"` — all DSL tests green (catches parser/compiler regressions).
- [ ] `./gradlew --offline ktlintCheck` — clean.
- [ ] Open a PR to `dev` (`Refs #223`). The change is daemon-and-backtest-shared compile-path only; CI runs the full suite.

## Notes for the implementer

- **Read before coding:** `dsl/compile/LetResolver.kt` (the expr-walk pattern to mirror for `ParamSubstitution`), the action AST types in `dsl/ast/` (for action substitution), and an existing passing strategy fixture (for exact `SYMBOLS`/`BUY ... SIZING` syntax — match it in test fixtures rather than inventing).
- **The riskiest task is Task 4** (action substitution). The Task 4 test is designed to fail loudly with `ExprCompiler`'s "bare Ref should have been substituted" if action coverage is missed — let that drive completeness.
- **Determinism is the invariant.** Substitution/override must be a pure function of the `.qkt` files: no clock, no map iteration order leaking into output (use `LinkedHashMap`/stable ordering), no environment.
