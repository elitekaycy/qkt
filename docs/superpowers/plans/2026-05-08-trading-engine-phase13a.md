# Phase 13a — STACK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `STACK` keyword that turns one `BUY`/`SELL` action into N price-triggered entries (pyramiding / scaling-in), expressible from both the Kotlin DSL and the external SQL-like parser.

**Architecture:**
- New AST types (`StackAst`, `StackLayer`, `StackDirection`, `DurationAst`) live in `com.qkt.dsl.ast`.
- Lexer adds `STACK`/`SPACING`/`WITHIN` keywords and a `DURATION` literal (`1h`, `30m`, …).
- Parser extends `parseActionOpts` with a STACK clause; layer AT expressions accept a magic `entry` identifier resolved to the first-fill price.
- Compiler folds the AST form into a runtime `StackPlan` carried by a new `OrderRequest.Stack` variant.
- `OrderManager` gains a `Stack` dispatch path: layer 1 submitted immediately as a regular Bracket order; layers 2..N materialize as `Stop`/`Limit` PENDING orders once the anchor is known. Reuses existing tick-driven trigger machinery, OTO parent-fills-children pattern, and TimeExit-style deadline scan.
- Per-stack lifecycle (filled-layer tracking, flat detection, WITHIN deadline) lives in `StackTracker`, owned by `OrderManager`.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, JUnit 5, AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase13a-stack-design.md`.

---

## File map

### Created

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/qkt/dsl/ast/Stack.kt` | `StackAst`, `StackSpacing`, `StackLayers`, `StackDirection`, `StackLayer`, `DurationAst`, `StackEntryRef` |
| `src/main/kotlin/com/qkt/dsl/kotlin/Stack.kt` | Kotlin DSL builders: `stack`, `stackOf`, `layer`, `entryPrice`, `duration` |
| `src/main/kotlin/com/qkt/execution/StackPlan.kt` | Runtime IR: `StackPlan`, `LayerSpec`, `LayerTrigger` |
| `src/main/kotlin/com/qkt/dsl/compile/StackCompiler.kt` | Folds `StackAst` → `StackPlan` |
| `src/main/kotlin/com/qkt/app/StackTracker.kt` | Per-stack runtime state + flat detection + deadline tracking |
| `src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt` | Lexer tests for STACK/SPACING/WITHIN/DURATION |
| `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt` | Parser tests for STACK forms and error cases |
| `src/test/kotlin/com/qkt/dsl/compile/StackCompilerTest.kt` | Compiler tests |
| `src/test/kotlin/com/qkt/dsl/kotlin/StackBuilderTest.kt` | Kotlin DSL builder tests |
| `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt` | Dispatch / lifecycle / cancel tests |
| `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt` | Synthetic-tick end-to-end scenarios |
| `docs/phases/phase-13a-stack.md` | Phase changelog |

### Modified

| Path | Reason |
|---|---|
| `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` | Add `STACK`, `SPACING`, `WITHIN`, `DURATION` |
| `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt` | Recognize `DURATION` literal; STACK/SPACING/WITHIN are auto-keyword via the `KEYWORDS` map |
| `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` | Add STACK clause inside `parseActionOpts`; add layer/layer-list parsing; magic `entry` handling |
| `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt` | Add `stack: StackAst? = null` field |
| `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt` | Add `stack` parameter to `buy` / `sell` overloads |
| `src/main/kotlin/com/qkt/execution/OrderRequest.kt` | Add `Stack` variant + extend `withStrategyId` |
| `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` | Thread `StackAst` into compiled action |
| `src/main/kotlin/com/qkt/app/OrderManager.kt` | Dispatch `OrderRequest.Stack`; integrate `StackTracker`; deadline + flat detection in `evaluateTriggers` |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | Bump `VERSION` to `0.14.0` |
| `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt` | Add STACK round-trip fixtures |
| `README.md` | One short STACK example added to feature list |

---

## Branching and commit cadence

- Working branch: `phase13a-stack` (already cut from `main` and contains the spec commit).
- Each task ends with one commit. Subject only, Conventional Commits format. No body, no AI footer.
- Run `./gradlew ktlintCheck` before each commit; if it complains, run `./gradlew ktlintFormat` and stage the fixes into the same commit.
- Run `./gradlew test --tests <pattern>` for the task's tests; full `./gradlew build` before merging.

---

## Task 1: AST — `StackAst`, `DurationAst`, `StackEntryRef`, `ActionOpts.stack`

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/Stack.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` (add `StackEntryRef`)

- [ ] **Step 1: Create `src/main/kotlin/com/qkt/dsl/ast/Stack.kt`**

```kotlin
package com.qkt.dsl.ast

sealed interface StackAst

data class StackSpacing(
    val count: Int,
    val spacing: ExprAst,
    val direction: StackDirection,
    val within: DurationAst? = null,
) : StackAst {
    init {
        require(count >= 1) { "STACK count must be >= 1: $count" }
    }
}

data class StackLayers(
    val layers: List<StackLayer>,
    val within: DurationAst? = null,
) : StackAst {
    init {
        require(layers.isNotEmpty()) { "STACK layer list must not be empty" }
    }
}

enum class StackDirection { TRADE_DIRECTION, ABOVE, BELOW }

data class StackLayer(
    val sizing: SizingAst,
    val orderType: OrderTypeAst? = null,
    val at: ExprAst? = null,
)

data class DurationAst(val millis: Long) {
    init {
        require(millis > 0) { "DURATION must be > 0 ms: $millis" }
    }
}
```

- [ ] **Step 2: Add `StackEntryRef` to `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`**

Append at the bottom of the file (after `FuncCall`):

```kotlin
data object StackEntryRef : ExprAst
```

- [ ] **Step 3: Modify `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`**

Replace the `ActionOpts` data class declaration with:

```kotlin
data class ActionOpts(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val bracket: BracketAst? = null,
    val oco: OcoAst? = null,
    val stack: StackAst? = null,
)
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`. No new tests yet — just verifying types compile.

- [ ] **Step 5: ktlint and commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/ast/Stack.kt src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt
git commit -m "feat(dsl): add StackAst and StackEntryRef AST nodes"
```

---

## Task 2: Lexer — STACK / SPACING / WITHIN keywords

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt`

The lexer's `KEYWORDS` map auto-includes any non-symbol entry of `TokenKind`, so adding entries to the enum is sufficient for keyword tokens.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt`:

```kotlin
package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerStackTest {
    @Test
    fun `STACK SPACING WITHIN are recognized as keywords`() {
        val tokens = Lexer("STACK SPACING WITHIN").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.STACK,
            TokenKind.SPACING,
            TokenKind.WITHIN,
            TokenKind.EOF,
        )
    }
}
```

- [ ] **Step 2: Run test — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerStackTest -q`
Expected: FAIL with `Unresolved reference: STACK` (or similar) — TokenKind doesn't have these entries yet.

- [ ] **Step 3: Add token kinds**

Modify `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. Add the three new entries below `CANCEL_ALL`:

```kotlin
    BUY,
    SELL,
    CLOSE,
    CLOSE_ALL,
    CANCEL,
    CANCEL_ALL,
    LOG,

    STACK,
    SPACING,
    WITHIN,
    DURATION,
```

(`DURATION` is for the literal token added in Task 3.)

- [ ] **Step 4: Run test — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerStackTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt
git commit -m "feat(dsl): add STACK SPACING WITHIN DURATION token kinds"
```

---

## Task 3: Lexer — DURATION literal

A DURATION literal is digits immediately followed by `s`, `m`, `h`, or `d` with no whitespace. Examples: `15s`, `30m`, `1h`, `2d`. Reject decimals (`1.5h`) and uppercase suffixes (`1H`).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt`:

```kotlin
    @Test
    fun `1h lexes as DURATION token`() {
        val tokens = Lexer("1h").tokenize()
        assertThat(tokens.map { it.kind to it.lexeme }).containsExactly(
            TokenKind.DURATION to "1h",
            TokenKind.EOF to "",
        )
    }

    @Test
    fun `30m 15s 2d all lex as DURATION`() {
        for (lit in listOf("30m", "15s", "2d", "120s")) {
            val tokens = Lexer(lit).tokenize()
            assertThat(tokens[0].kind).`as`("$lit token kind").isEqualTo(TokenKind.DURATION)
            assertThat(tokens[0].lexeme).isEqualTo(lit)
        }
    }

    @Test
    fun `digits without duration suffix lex as NUMBER`() {
        val tokens = Lexer("100").tokenize()
        assertThat(tokens[0].kind).isEqualTo(TokenKind.NUMBER)
        assertThat(tokens[0].lexeme).isEqualTo("100")
    }

    @Test
    fun `decimal followed by h does not lex as DURATION`() {
        // 1.5h must lex as NUMBER 1.5 then IDENT h, not DURATION
        val tokens = Lexer("1.5h").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.NUMBER,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
        assertThat(tokens[0].lexeme).isEqualTo("1.5")
        assertThat(tokens[1].lexeme).isEqualTo("h")
    }

    @Test
    fun `uppercase suffix is not a DURATION`() {
        val tokens = Lexer("1H").tokenize()
        assertThat(tokens.map { it.kind }).containsExactly(
            TokenKind.NUMBER,
            TokenKind.IDENT,
            TokenKind.EOF,
        )
    }
```

- [ ] **Step 2: Run tests — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerStackTest -q`
Expected: FAIL — DURATION lexing not yet implemented.

- [ ] **Step 3: Modify `readNumber()` in `Lexer.kt`**

Replace the existing `readNumber()` method body in `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt` with:

```kotlin
    private fun readNumber(): Token {
        val startLine = line
        val startCol = col
        val start = pos
        while (pos < src.length && src[pos].isDigit()) advance()
        val intOnly = pos
        if (pos < src.length && src[pos] == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) {
            advance()
            while (pos < src.length && src[pos].isDigit()) advance()
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            advance()
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) advance()
            while (pos < src.length && src[pos].isDigit()) advance()
        }
        // Duration literal: only on integer numbers (no decimal/exponent), suffix s|m|h|d.
        if (pos == intOnly && pos < src.length && src[pos] in DURATION_SUFFIXES) {
            advance()
            return Token(TokenKind.DURATION, src.substring(start, pos), startLine, startCol)
        }
        return Token(TokenKind.NUMBER, src.substring(start, pos), startLine, startCol)
    }
```

And add the suffix set inside the `companion object` block at the bottom of the file:

```kotlin
    companion object {
        private val DURATION_SUFFIXES = setOf('s', 'm', 'h', 'd')

        private val KEYWORDS: Map<String, TokenKind> =
            // ... (existing body unchanged)
```

Make sure `DURATION` is added to the excluded set in the `KEYWORDS` filter so the lexer does not try to match a `"DURATION"` keyword:

```kotlin
        private val KEYWORDS: Map<String, TokenKind> =
            TokenKind
                .values()
                .filter {
                    it.name !in
                        setOf(
                            "NUMBER",
                            "STRING",
                            "IDENT",
                            "EOF",
                            "DURATION",
                            "PLUS",
                            // ... rest unchanged
                        )
                }.associateBy { it.name }
```

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.LexerStackTest -q`
Expected: PASS (all five tests).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Lexer.kt src/test/kotlin/com/qkt/dsl/parse/LexerStackTest.kt
git commit -m "feat(dsl): lex DURATION literal with s|m|h|d suffix"
```

---

## Task 4: Parser — STACK SPACING form

Extend `parseActionOpts` to recognize a `STACK` clause; implement `parseStackClause` for the SPACING form (count + spacing + optional ABOVE/BELOW + optional WITHIN).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt`

- [ ] **Step 1: Write failing tests**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.WhenThen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserStackTest {
    private fun parseRule(src: String): WhenThen {
        val full =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                $src
            """.trimIndent()
        val tokens = Lexer(full).tokenize()
        val strat = Parser(tokens).parse()
        return strat.rules.single() as WhenThen
    }

    @Test
    fun `STACK count SPACING parses with default direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100")
        val buy = rule.action as Buy
        val stack = buy.opts.stack as StackSpacing
        assertThat(stack.count).isEqualTo(3)
        assertThat(stack.spacing).isEqualTo(NumLit(BigDecimal("100")))
        assertThat(stack.direction).isEqualTo(StackDirection.TRADE_DIRECTION)
        assertThat(stack.within).isNull()
    }

    @Test
    fun `STACK 3 SPACING 100 ABOVE parses with ABOVE direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.direction).isEqualTo(StackDirection.ABOVE)
    }

    @Test
    fun `STACK 3 SPACING 100 BELOW parses with BELOW direction`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 BELOW")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.direction).isEqualTo(StackDirection.BELOW)
    }
}
```

- [ ] **Step 2: Run tests — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: FAIL — parser does not yet handle STACK clause; test setup will throw.

- [ ] **Step 3: Add parser methods**

In `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`:

(a) Add imports near the top with the other AST imports:

```kotlin
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
```

(b) Inside `parseActionOpts`, add a `STACK` branch (place it after the `OCO` branch, before the `else -> break@loop` line):

```kotlin
                TokenKind.OCO -> {
                    advance()
                    oco = parseOco()
                }
                TokenKind.STACK -> {
                    advance()
                    stack = parseStackClause()
                }
                else -> break@loop
            }
        }
```

(c) Declare the `stack` local at the top of `parseActionOpts` alongside the other vars:

```kotlin
    private fun parseActionOpts(): ActionOpts {
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var bracket: BracketAst? = null
        var oco: OcoAst? = null
        var stack: StackAst? = null
        loop@ while (true) {
            // ... existing body
        }
        return ActionOpts(sizing, orderType ?: com.qkt.dsl.ast.Market, tif, bracket, oco, stack)
    }
```

(d) Add the `parseStackClause`, `parseStackSpacing`, `parseDirection`, and `parseDuration` methods anywhere after `parseActionOpts`:

```kotlin
    internal fun parseStackClause(): StackAst {
        // STACK <count> SPACING <expr> [ABOVE|BELOW] [WITHIN <duration>]
        // STACK [ <layers> ] [WITHIN <duration>]   (added in Task 5)
        return if (peek().kind == TokenKind.LBRACKET) {
            parseStackLayers()
        } else {
            parseStackSpacing()
        }
    }

    internal fun parseStackSpacing(): StackSpacing {
        val countTok = expect(TokenKind.NUMBER, "expected count after STACK")
        val count =
            countTok.lexeme.toIntOrNull()
                ?: error("STACK count must be a positive integer, got '${countTok.lexeme}'")
        if (count < 1) error("STACK count must be >= 1, got $count")
        expect(TokenKind.SPACING, "expected SPACING after STACK count")
        val spacing = parseExpr()
        val direction =
            when (peek().kind) {
                TokenKind.ABOVE -> {
                    advance()
                    StackDirection.ABOVE
                }
                TokenKind.BELOW -> {
                    advance()
                    StackDirection.BELOW
                }
                else -> StackDirection.TRADE_DIRECTION
            }
        val within = if (peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackSpacing(count, spacing, direction, within)
    }

    internal fun parseStackLayers(): StackLayers {
        // implemented in Task 5
        error("layer-list form not yet implemented")
    }

    internal fun parseWithin(): DurationAst {
        expect(TokenKind.WITHIN, "expected WITHIN")
        return parseDuration()
    }

    internal fun parseDuration(): DurationAst {
        val tok = expect(TokenKind.DURATION, "expected duration literal (e.g., 1h, 30m)")
        val lex = tok.lexeme
        val n =
            lex.dropLast(1).toLongOrNull()
                ?: error("invalid duration literal '$lex'")
        val unit = lex.last()
        val millis =
            when (unit) {
                's' -> n * 1_000L
                'm' -> n * 60_000L
                'h' -> n * 3_600_000L
                'd' -> n * 86_400_000L
                else -> error("unknown duration unit '$unit' in '$lex'")
            }
        return DurationAst(millis)
    }
```

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: PASS (three tests).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt
git commit -m "feat(dsl): parse STACK SPACING form"
```

---

## Task 5: Parser — STACK layer-list form + magic `entry`

Implement `parseStackLayers` for `STACK [ <layer>, ... ]`. Each layer is `<sizing> [<order-type>] [AT <expr>]`. Inside a layer's AT expression, the bare identifier `entry` resolves to `StackEntryRef`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt`:

```kotlin
    @Test
    fun `layer-list form parses with three layers`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc " +
                    "STACK [ 0.1, 0.2 AT entry + 100, 0.3 LIMIT AT entry + 200 ]",
            )
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        assertThat(stack.layers).hasSize(3)
        assertThat(stack.layers[0].at).isNull()
        assertThat(stack.layers[0].orderType).isNull()
        assertThat(stack.layers[1].at).isNotNull
        assertThat(stack.layers[2].orderType).isInstanceOf(com.qkt.dsl.ast.Limit::class.java)
    }

    @Test
    fun `entry inside layer AT becomes StackEntryRef`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100 ]",
            )
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        val expr = stack.layers[1].at as com.qkt.dsl.ast.BinaryOp
        assertThat(expr.lhs).isEqualTo(com.qkt.dsl.ast.StackEntryRef)
    }

    @Test
    fun `trailing comma in layer-list is allowed`() {
        val rule =
            parseRule(
                "WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100, ]",
            )
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        assertThat(stack.layers).hasSize(2)
    }
```

- [ ] **Step 2: Run tests — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: FAIL on the new three tests — `parseStackLayers` is a stub.

- [ ] **Step 3: Implement `parseStackLayers` and `parseLayer`**

Replace the stub `parseStackLayers` in `Parser.kt` with:

```kotlin
    internal fun parseStackLayers(): StackLayers {
        expect(TokenKind.LBRACKET, "expected '[' to open layer list")
        val layers = mutableListOf<StackLayer>()
        if (peek().kind == TokenKind.RBRACKET) {
            error("STACK layer list must not be empty")
        }
        layers.add(parseLayer(isFirst = true))
        while (peek().kind == TokenKind.COMMA) {
            advance()
            if (peek().kind == TokenKind.RBRACKET) break  // trailing comma allowed
            layers.add(parseLayer(isFirst = false))
        }
        expect(TokenKind.RBRACKET, "expected ']' to close layer list")
        val within = if (peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackLayers(layers, within)
    }

    internal fun parseLayer(isFirst: Boolean): StackLayer {
        val sizing = parseSizing()
        val orderType: OrderTypeAst? =
            when (peek().kind) {
                TokenKind.MARKET, TokenKind.LIMIT, TokenKind.STOP -> parseOrderType()
                else -> null
            }
        val at: ExprAst? =
            if (peek().kind == TokenKind.AT) {
                advance()
                inStackLayerAt = true
                try {
                    parseExpr()
                } finally {
                    inStackLayerAt = false
                }
            } else {
                null
            }
        if (!isFirst && at == null) {
            error("STACK layers after the first must have an AT clause")
        }
        return StackLayer(sizing, orderType, at)
    }
```

(b) Add the `inStackLayerAt` flag. Near the top of the `Parser` class, add a private property:

```kotlin
    private var inStackLayerAt: Boolean = false
```

(c) Wire `entry` resolution into the expression parser. Find the leaf expression handler that consumes `IDENT` (search for `TokenKind.IDENT`). In the path where it recognises a bare identifier (not followed by `.` or `(`), add a special case:

```kotlin
            TokenKind.IDENT -> {
                if (inStackLayerAt && peek().lexeme == "entry") {
                    advance()
                    com.qkt.dsl.ast.StackEntryRef
                } else {
                    // existing IDENT handling
                    ...
                }
            }
```

(Note: the exact location depends on where the existing parser handles bare identifiers. The branch that returns a `Ref` for a plain IDENT is the right spot. The `entry`-special-case must come before that branch.)

Add an import: `import com.qkt.dsl.ast.ExprAst`.

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: PASS (all six tests now).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt
git commit -m "feat(dsl): parse STACK layer-list with magic entry identifier"
```

---

## Task 6: Parser — WITHIN, error cases, and conflict rules

Round out the parser with:
- `WITHIN <duration>` after both forms.
- Parse errors: `WITHIN` without trigger, empty list, layer-2 missing AT, outer `SIZING` + per-layer sizing, SPACING literal `0`.
- `entry` outside a layer AT context is a regular IDENT (which downstream resolution will fail on).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `ParserStackTest.kt`:

```kotlin
    @Test
    fun `WITHIN attaches to SPACING form`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 WITHIN 1h")
        val stack = (rule.action as Buy).opts.stack as StackSpacing
        assertThat(stack.within?.millis).isEqualTo(3_600_000L)
    }

    @Test
    fun `WITHIN attaches to layer-list form`() {
        val rule = parseRule("WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100 ] WITHIN 30m")
        val stack = (rule.action as Buy).opts.stack as com.qkt.dsl.ast.StackLayers
        assertThat(stack.within?.millis).isEqualTo(1_800_000L)
    }

    @Test
    fun `outer SIZING with layer-list sizing is parse error`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK [ 0.1, 0.2 AT entry + 100 ]")
            }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("outer SIZING")
    }

    @Test
    fun `STACK count zero is parse error`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                parseRule("WHEN btc.close > 100 THEN BUY btc SIZING 0.1 STACK 0 SPACING 100")
            }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("STACK count")
    }

    @Test
    fun `empty layer list is parse error`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                parseRule("WHEN btc.close > 100 THEN BUY btc STACK [ ]")
            }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("layer list")
    }

    @Test
    fun `layer two without AT is parse error`() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                parseRule("WHEN btc.close > 100 THEN BUY btc STACK [ 0.1, 0.2 ]")
            }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("AT clause")
    }
```

- [ ] **Step 2: Run tests — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: FAIL — outer-SIZING-with-layer-list rule not yet enforced; the count-zero rule already partly handled but message may differ.

- [ ] **Step 3: Add the conflict-rule check after `parseActionOpts`**

In `Parser.kt`, replace the final `return` of `parseActionOpts` with a validation step:

```kotlin
        val finalStack = stack
        if (sizing != null && finalStack is StackLayers) {
            error("STACK layer-list cannot be combined with outer SIZING; specify size on each layer or remove the layer list")
        }
        return ActionOpts(sizing, orderType ?: com.qkt.dsl.ast.Market, tif, bracket, oco, finalStack)
```

(For the `StackSpacing` form the outer SIZING is the per-layer size; that's intentional.)

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.ParserStackTest -q`
Expected: PASS (all twelve tests).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserStackTest.kt
git commit -m "feat(dsl): enforce STACK parser conflict rules and WITHIN clause"
```

---

## Task 7: Kotlin DSL builders

Mirror the parser surface in the internal Kotlin DSL so round-trip equivalence works.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/Stack.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`
- Create: `src/test/kotlin/com/qkt/dsl/kotlin/StackBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/kotlin/StackBuilderTest.kt`:

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.SizeQty
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackBuilderTest {
    @Test
    fun `stack count and spacing builds StackSpacing`() {
        val s = stack(count = 3, spacing = NumLit(BigDecimal("100")))
        assertThat(s).isInstanceOf(StackSpacing::class.java)
        s as StackSpacing
        assertThat(s.count).isEqualTo(3)
        assertThat(s.direction).isEqualTo(StackDirection.TRADE_DIRECTION)
    }

    @Test
    fun `stackOf builds StackLayers and rejects empty list`() {
        val l1 = layer(qty = NumLit(BigDecimal("0.1")))
        val l2 = layer(qty = NumLit(BigDecimal("0.2")), at = entryPrice + NumLit(BigDecimal("100")))
        val sl = stackOf(l1, l2)
        assertThat(sl).isInstanceOf(StackLayers::class.java)
        assertThatThrownBy { stackOf() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `entryPrice resolves to StackEntryRef`() {
        assertThat(entryPrice).isEqualTo(StackEntryRef)
    }

    @Test
    fun `duration parses suffix-style strings`() {
        assertThat(duration("1h").millis).isEqualTo(3_600_000L)
        assertThat(duration("30m").millis).isEqualTo(1_800_000L)
        assertThat(duration("15s").millis).isEqualTo(15_000L)
        assertThat(duration("2d").millis).isEqualTo(172_800_000L)
    }

    @Test
    fun `layer 2 without AT is rejected by stackOf`() {
        val l1 = layer(qty = NumLit(BigDecimal("0.1")))
        val l2 = layer(qty = NumLit(BigDecimal("0.2"))) // no at
        assertThatThrownBy { stackOf(l1, l2) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("AT")
    }
}
```

- [ ] **Step 2: Run test — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.kotlin.StackBuilderTest -q`
Expected: FAIL — builders don't exist.

- [ ] **Step 3: Create `src/main/kotlin/com/qkt/dsl/kotlin/Stack.kt`**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing

val entryPrice: ExprAst = StackEntryRef

fun stack(
    count: Int,
    spacing: ExprAst,
    direction: StackDirection = StackDirection.TRADE_DIRECTION,
    within: DurationAst? = null,
): StackAst = StackSpacing(count, spacing, direction, within)

fun stackOf(
    vararg layers: StackLayer,
    within: DurationAst? = null,
): StackAst {
    require(layers.isNotEmpty()) { "stackOf must have at least one layer" }
    layers.forEachIndexed { i, l ->
        require(i == 0 || l.at != null) { "layer $i must have an AT clause" }
    }
    return StackLayers(layers.toList(), within)
}

fun layer(
    qty: ExprAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer = StackLayer(SizeQty(qty), orderType, at)

fun layer(
    sizing: SizingAst,
    orderType: OrderTypeAst? = null,
    at: ExprAst? = null,
): StackLayer = StackLayer(sizing, orderType, at)

fun duration(text: String): DurationAst {
    require(text.length >= 2) { "invalid duration '$text'" }
    val unit = text.last()
    val n = text.dropLast(1).toLongOrNull() ?: error("invalid duration '$text'")
    val millis =
        when (unit) {
            's' -> n * 1_000L
            'm' -> n * 60_000L
            'h' -> n * 3_600_000L
            'd' -> n * 86_400_000L
            else -> error("unknown duration unit '$unit' in '$text'")
        }
    return DurationAst(millis)
}
```

- [ ] **Step 4: Add `stack` parameter to `ActionScope.buy` / `sell`**

In `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`, add `stack: StackAst? = null` to every overload of `buy` and `sell`, and pass it into `ActionOpts(...)`. Example for one overload:

```kotlin
    fun buy(
        stream: StreamRef,
        qty: ExprAst,
        orderType: OrderTypeAst = Market,
        tif: TifAst? = null,
        bracket: BracketAst? = null,
        oco: OcoAst? = null,
        stack: StackAst? = null,
    ): ActionAst =
        Buy(
            stream.alias,
            ActionOpts(
                sizing = SizeQty(qty),
                orderType = orderType,
                tif = tif,
                bracket = bracket,
                oco = oco,
                stack = stack,
            ),
        )
```

Apply the same pattern to all five `buy` and three `sell` overloads. Add `import com.qkt.dsl.ast.StackAst` at the top.

- [ ] **Step 5: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.kotlin.StackBuilderTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/kotlin/Stack.kt src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt src/test/kotlin/com/qkt/dsl/kotlin/StackBuilderTest.kt
git commit -m "feat(dsl): add Kotlin DSL builders for STACK"
```

---

## Task 8: Round-trip equivalence — STACK fixtures

Add STACK forms to the existing round-trip equivalence test (parser ↔ Kotlin DSL).

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`

- [ ] **Step 1: Inspect the existing round-trip test to learn its style**

Read `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt` to see how prior fixtures are named and structured. Each fixture is typically a Kotlin-built strategy and an equivalent textual strategy; the test asserts the parsed AST equals the Kotlin-built AST.

- [ ] **Step 2: Add STACK SPACING round-trip case**

Append a test method modelled after the existing ones. Example:

```kotlin
    @Test
    fun `STACK SPACING round trips`() {
        val text =
            """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 100
                THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE WITHIN 1h
            """.trimIndent()
        val parsed = Parser(Lexer(text).tokenize()).parse()

        val kotlinBuilt =
            com.qkt.dsl.kotlin.strategyOf("t") {
                symbols { stream("btc", "BACKTEST", "BTCUSDT", "1m") }
                rules {
                    whenThen(
                        cond = com.qkt.dsl.kotlin.streamFieldRef("btc", "close") gt num("100"),
                        action =
                            com.qkt.dsl.kotlin.ActionScope.buy(
                                stream = com.qkt.dsl.kotlin.StreamRef("btc"),
                                qty = num("0.1"),
                                stack =
                                    com.qkt.dsl.kotlin.stack(
                                        count = 3,
                                        spacing = num("100"),
                                        direction = com.qkt.dsl.ast.StackDirection.ABOVE,
                                        within = com.qkt.dsl.kotlin.duration("1h"),
                                    ),
                            ),
                    )
                }
            }

        assertThat(parsed).isEqualTo(kotlinBuilt)
    }
```

The exact helper names (`strategyOf`, `streamFieldRef`, `num`, etc.) vary; mirror what existing tests in this file use.

- [ ] **Step 3: Add STACK layer-list round-trip case**

Add a second test for the layer-list form, including a `StackEntryRef` reference inside layer 2 / 3 AT expressions. Use `com.qkt.dsl.kotlin.entryPrice` on the Kotlin side and `entry` on the parser side.

- [ ] **Step 4: Run the round-trip tests**

Run: `./gradlew test --tests com.qkt.dsl.parse.RoundTripEquivalenceTest -q`
Expected: PASS, including the two new methods.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt
git commit -m "test(dsl): round-trip equivalence for STACK forms"
```

---

## Task 9: Runtime IR — `StackPlan`, `LayerSpec`, `LayerTrigger`

Define the runtime types the compiler emits and that `OrderRequest.Stack` (Task 11) carries.

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/StackPlan.kt`

- [ ] **Step 1: Create `src/main/kotlin/com/qkt/execution/StackPlan.kt`**

```kotlin
package com.qkt.execution

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackDirection

data class StackPlan(
    val layers: List<LayerSpec>,
    val outerBracket: BracketAst? = null,
    val withinMillis: Long? = null,
) {
    init {
        require(layers.isNotEmpty()) { "StackPlan must have at least one layer" }
    }
}

data class LayerSpec(
    val index: Int,
    val sizing: SizingAst,
    val orderType: OrderTypeAst,
    val trigger: LayerTrigger,
)

sealed interface LayerTrigger

data object Immediate : LayerTrigger

data class At(
    val price: ExprAst,
    val direction: StackDirection,
) : LayerTrigger
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/execution/StackPlan.kt
git commit -m "feat(execution): add StackPlan runtime IR"
```

---

## Task 10: Compiler — fold `StackAst` → `StackPlan`

Compile the AST forms into the runtime `StackPlan`.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/StackCompiler.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/StackCompilerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/qkt/dsl/compile/StackCompilerTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.At
import com.qkt.execution.Immediate
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackCompilerTest {
    @Test
    fun `SPACING form folds into N layers`() {
        val ast = StackSpacing(3, NumLit(BigDecimal("100")), StackDirection.TRADE_DIRECTION)
        val plan = StackCompiler.compile(ast, outerSizing = SizeQty(NumLit(BigDecimal("0.1"))), outerBracket = null)
        assertThat(plan.layers).hasSize(3)
        assertThat(plan.layers[0].trigger).isEqualTo(Immediate)
        assertThat(plan.layers[1].trigger).isInstanceOf(At::class.java)
        val at1 = plan.layers[1].trigger as At
        // entry + 100 * 1
        val expected1 = BinaryOp(BinOp.PLUS, StackEntryRef, NumLit(BigDecimal("100")))
        assertThat(at1.price).isEqualTo(expected1)
        val at2 = plan.layers[2].trigger as At
        val expected2 = BinaryOp(BinOp.PLUS, StackEntryRef, NumLit(BigDecimal("200")))
        assertThat(at2.price).isEqualTo(expected2)
    }

    @Test
    fun `layer-list form preserves explicit triggers and order types`() {
        val l1 = StackLayer(SizeQty(NumLit(BigDecimal("0.1"))))
        val l2 =
            StackLayer(
                SizeQty(NumLit(BigDecimal("0.2"))),
                at =
                    BinaryOp(
                        BinOp.PLUS,
                        StackEntryRef,
                        NumLit(BigDecimal("100")),
                    ),
            )
        val l3 =
            StackLayer(
                SizeQty(NumLit(BigDecimal("0.3"))),
                orderType = Limit(NumLit(BigDecimal("50100"))),
                at = NumLit(BigDecimal("50100")),
            )
        val plan = StackCompiler.compile(StackLayers(listOf(l1, l2, l3)), outerSizing = null, outerBracket = null)
        assertThat(plan.layers[0].trigger).isEqualTo(Immediate)
        assertThat(plan.layers[0].orderType).isEqualTo(Market)
        assertThat(plan.layers[2].orderType).isInstanceOf(Limit::class.java)
    }
}
```

- [ ] **Step 2: Run test — expect failure**

Run: `./gradlew test --tests com.qkt.dsl.compile.StackCompilerTest -q`
Expected: FAIL — `StackCompiler` does not exist.

- [ ] **Step 3: Create `src/main/kotlin/com/qkt/dsl/compile/StackCompiler.kt`**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.StackPlan
import java.math.BigDecimal

object StackCompiler {
    fun compile(
        ast: StackAst,
        outerSizing: SizingAst?,
        outerBracket: BracketAst?,
    ): StackPlan =
        when (ast) {
            is StackSpacing -> compileSpacing(ast, outerSizing, outerBracket)
            is StackLayers -> compileLayers(ast, outerBracket)
        }

    private fun compileSpacing(
        ast: StackSpacing,
        outerSizing: SizingAst?,
        outerBracket: BracketAst?,
    ): StackPlan {
        val sizing =
            outerSizing
                ?: error("STACK SPACING form requires outer SIZING on the BUY/SELL action")
        val layers =
            (1..ast.count).map { i ->
                val trigger =
                    if (i == 1) {
                        Immediate
                    } else {
                        val multiplier = NumLit(BigDecimal((i - 1).toString()))
                        val offset =
                            if ((i - 1) == 1) {
                                ast.spacing
                            } else {
                                BinaryOp(BinOp.STAR, ast.spacing, multiplier)
                            }
                        At(BinaryOp(BinOp.PLUS, StackEntryRef, offset), ast.direction)
                    }
                LayerSpec(
                    index = i,
                    sizing = sizing,
                    orderType = Market,
                    trigger = trigger,
                )
            }
        return StackPlan(layers, outerBracket, ast.within?.millis)
    }

    private fun compileLayers(
        ast: StackLayers,
        outerBracket: BracketAst?,
    ): StackPlan {
        val layers =
            ast.layers.mapIndexed { idx, l ->
                val trigger: com.qkt.execution.LayerTrigger =
                    if (l.at == null) {
                        require(idx == 0) { "layer ${idx + 1} must have AT" }
                        Immediate
                    } else {
                        At(l.at, com.qkt.dsl.ast.StackDirection.TRADE_DIRECTION)
                    }
                LayerSpec(
                    index = idx + 1,
                    sizing = l.sizing,
                    orderType = l.orderType ?: Market,
                    trigger = trigger,
                )
            }
        return StackPlan(layers, outerBracket, ast.within?.millis)
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.dsl.compile.StackCompilerTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/compile/StackCompiler.kt src/test/kotlin/com/qkt/dsl/compile/StackCompilerTest.kt
git commit -m "feat(dsl): compile StackAst into runtime StackPlan"
```

---

## Task 11: `OrderRequest.Stack` variant

Add the broker-facing OrderRequest variant. Brokers won't actually receive it (OrderManager intercepts), but it is the request shape that flows through the pipeline.

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`

- [ ] **Step 1: Add the `Stack` data class**

In `src/main/kotlin/com/qkt/execution/OrderRequest.kt`, inside the `OrderRequest` sealed interface block, add a new variant after `TimeExit`:

```kotlin
    data class Stack(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val plan: StackPlan,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
        override val strategyId: String = "",
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }
```

- [ ] **Step 2: Extend `withStrategyId`**

Add the case to the bottom-of-file extension function:

```kotlin
fun OrderRequest.withStrategyId(strategyId: String): OrderRequest =
    when (this) {
        // ... existing cases
        is OrderRequest.TimeExit -> copy(strategyId = strategyId)
        is OrderRequest.Stack -> copy(strategyId = strategyId)
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt
git commit -m "feat(execution): add OrderRequest.Stack variant"
```

---

## Task 12: `StackTracker` — per-stack runtime state

Encapsulate the per-stack lifecycle so `OrderManager` stays readable.

**Files:**
- Create: `src/main/kotlin/com/qkt/app/StackTracker.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.qkt.app

import com.qkt.dsl.ast.BracketAst
import com.qkt.execution.LayerSpec
import com.qkt.execution.StackPlan
import java.math.BigDecimal

internal class StackTracker {
    private val active: MutableMap<String, ActiveStack> = mutableMapOf()

    fun register(
        stackId: String,
        plan: StackPlan,
        outerBracket: BracketAst?,
    ) {
        active[stackId] =
            ActiveStack(
                id = stackId,
                plan = plan,
                outerBracket = outerBracket,
                withinMillis = plan.withinMillis,
            )
    }

    fun setAnchor(
        stackId: String,
        anchor: BigDecimal,
        firstFillEpochMs: Long,
    ) {
        val s = active[stackId] ?: return
        active[stackId] =
            s.copy(
                anchor = anchor,
                deadlineEpochMs = s.withinMillis?.let { firstFillEpochMs + it },
            )
    }

    fun get(stackId: String): ActiveStack? = active[stackId]

    fun all(): Collection<ActiveStack> = active.values.toList()

    fun addPending(
        stackId: String,
        layerOrderId: String,
    ) {
        active[stackId]?.pendingLayerIds?.add(layerOrderId)
    }

    fun markFilled(layerOrderId: String): String? {
        val entry = active.entries.firstOrNull { layerOrderId in it.value.pendingLayerIds }
        if (entry != null) {
            entry.value.pendingLayerIds.remove(layerOrderId)
            entry.value.filledLayerIds.add(layerOrderId)
            return entry.key
        }
        // Layer 1 fills do not pass through pending — caller registers them separately.
        val layerOneOwner =
            active.entries.firstOrNull { it.value.layerOneOrderId == layerOrderId }
        if (layerOneOwner != null) {
            layerOneOwner.value.filledLayerIds.add(layerOrderId)
            return layerOneOwner.key
        }
        return null
    }

    fun setLayerOneOrderId(
        stackId: String,
        orderId: String,
    ) {
        active[stackId]?.let { active[stackId] = it.copy(layerOneOrderId = orderId) }
    }

    fun terminate(stackId: String): ActiveStack? = active.remove(stackId)

    fun stackOwning(orderId: String): String? =
        active.entries.firstOrNull {
            orderId in it.value.pendingLayerIds ||
                orderId in it.value.filledLayerIds ||
                orderId == it.value.layerOneOrderId
        }?.key

    internal data class ActiveStack(
        val id: String,
        val plan: StackPlan,
        val outerBracket: BracketAst?,
        val withinMillis: Long?,
        val anchor: BigDecimal? = null,
        val deadlineEpochMs: Long? = null,
        val layerOneOrderId: String? = null,
        val pendingLayerIds: MutableSet<String> = mutableSetOf(),
        val filledLayerIds: MutableSet<String> = mutableSetOf(),
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/StackTracker.kt
git commit -m "feat(app): add StackTracker for per-stack lifecycle"
```

---

## Task 13: `OrderManager` — Stack dispatch (layer 1 + materialize on fill)

Wire `OrderRequest.Stack` into `OrderManager.dispatch`. Layer 1 submits as a regular order (Bracket if `outerBracket` present, otherwise plain Market/Limit). On layer 1 fill, evaluate each remaining layer's trigger expression (resolving `StackEntryRef` to the actual fill price) and submit as `Stop` (for above-direction triggers) or `Limit` (for below-direction) PENDING orders, also wrapped in Brackets where appropriate.

This is the largest task; it stays one task because the steps are tightly coupled.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

- [ ] **Step 1: Write failing test (3-layer happy path)**

Create `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`. Use the same harness style as `OrderManagerOtoTest.kt` (an in-memory `MockBroker`, `EventBus`, `FixedClock`, `MutableMarketPrices`). At least the first scenario:

```kotlin
package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.Market as AstMarket
import com.qkt.events.BrokerEvent
import com.qkt.execution.At
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MutableMarketPrices
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStackTest {
    private val clock = FixedClock(1_000)
    private val bus = EventBus()
    private val broker = RecordingBroker()
    private val prices = MutableMarketPrices()
    private val manager = OrderManager(broker, bus, prices, clock)

    private fun layer(
        i: Int,
        qty: String,
        triggerOffset: BigDecimal? = null,
    ): LayerSpec {
        val trigger =
            if (triggerOffset == null) {
                com.qkt.execution.Immediate
            } else {
                At(
                    BinaryOp(BinOp.PLUS, StackEntryRef, NumLit(triggerOffset)),
                    StackDirection.TRADE_DIRECTION,
                )
            }
        return LayerSpec(i, SizeQty(NumLit(BigDecimal(qty))), AstMarket, trigger)
    }

    @Test
    fun `layer 1 fires immediately, layers 2 and 3 pend until trigger`() {
        val plan =
            StackPlan(
                listOf(
                    layer(1, "0.1"),
                    layer(2, "0.1", BigDecimal("100")),
                    layer(3, "0.1", BigDecimal("200")),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)

        // Layer 1 should be a Market submitted to broker.
        assertThat(broker.submitted).hasSize(1)
        assertThat(broker.submitted[0]).isInstanceOf(OrderRequest.Market::class.java)

        // Simulate layer-1 fill at 50000.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = broker.submitted[0].id,
                brokerOrderId = "b1",
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )

        // Layers 2 and 3 should now be PENDING with trigger prices 50100 and 50200.
        val pending = manager.pendingOrders()
        assertThat(pending).hasSize(2)
        val triggers = pending.map { (it.request as OrderRequest.Stop).stopPrice }
        assertThat(triggers).containsExactlyInAnyOrder(
            BigDecimal("50100"),
            BigDecimal("50200"),
        )
    }
}

private class RecordingBroker : Broker {
    val submitted = mutableListOf<OrderRequest>()
    override fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> =
        setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)
    override fun submit(request: OrderRequest): SubmitAck {
        submitted.add(request)
        return SubmitAck(request.id, request.id, accepted = true)
    }
    override fun cancel(clientOrderId: String) {}
}
```

(If your `Broker`/`MutableMarketPrices`/`FixedClock` API differs slightly, mirror what existing OrderManager tests use.)

- [ ] **Step 2: Run test — expect failure**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: FAIL — `OrderManager` doesn't recognise `Stack` yet (hits the `else -> error` in the dispatch `when`).

- [ ] **Step 3: Add `Stack` dispatch to `OrderManager.dispatch`**

In `src/main/kotlin/com/qkt/app/OrderManager.kt`, add a case in the `dispatch` `when`:

```kotlin
            is OrderRequest.Stack -> submitStack(request)
```

Add a `StackTracker` field and constructor wiring (just below the existing internal maps near the top of the class):

```kotlin
    private val stacks: StackTracker = StackTracker()
```

Subscribe to fill events for layer-1 anchor capture (extend the existing init block):

```kotlin
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onStackLayerFilled(e) }
```

Be careful: `onFilled` already subscribes; add a separate handler that runs alongside.

Then implement the methods:

```kotlin
    private fun submitStack(req: OrderRequest.Stack): SubmitAck {
        val firstLayer =
            req.plan.layers.firstOrNull()
                ?: error("StackPlan must have at least one layer")
        require(firstLayer.trigger == com.qkt.execution.Immediate) {
            "first layer must have Immediate trigger"
        }
        stacks.register(req.id, req.plan, req.plan.outerBracket)
        val now = clock.now()
        val firstOrderId = "${req.id}-l1"
        stacks.setLayerOneOrderId(req.id, firstOrderId)

        val firstQty = resolveLayerQuantity(firstLayer, req.symbol, req.side, anchor = null)
        val firstReq = buildLayerOrder(firstOrderId, req, firstLayer, firstQty, triggerPrice = null)
        track(
            ManagedOrder(
                id = firstOrderId,
                request = firstReq,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        track(
            ManagedOrder(
                id = req.id,
                request = req,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(firstOrderId),
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        dispatch(firstReq)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    private fun onStackLayerFilled(e: BrokerEvent.OrderFilled) {
        val owner = stacks.markFilled(e.clientOrderId) ?: return
        val state = stacks.get(owner) ?: return
        // Anchor capture happens only on layer 1.
        if (state.layerOneOrderId == e.clientOrderId && state.anchor == null) {
            stacks.setAnchor(owner, e.price, clock.now())
            materializePendingLayers(owner, anchor = e.price)
        }
    }

    private fun materializePendingLayers(
        stackId: String,
        anchor: BigDecimal,
    ) {
        val state = stacks.get(stackId) ?: return
        val parent =
            (orders[stackId]?.request as? OrderRequest.Stack)
                ?: error("Stack request not tracked for $stackId")
        for (layer in state.plan.layers.drop(1)) {
            val triggerPrice =
                resolveTriggerPrice(layer.trigger, anchor)
            val layerOrderId = "$stackId-l${layer.index}"
            val qty =
                resolveLayerQuantity(layer, parent.symbol, parent.side, anchor)
            val pending =
                buildLayerOrder(layerOrderId, parent, layer, qty, triggerPrice)
            track(
                ManagedOrder(
                    id = layerOrderId,
                    request = pending,
                    state = OrderState.CREATED,
                    parentClientOrderId = stackId,
                    createdAt = clock.now(),
                    lastUpdatedAt = clock.now(),
                ),
            )
            stacks.addPending(stackId, layerOrderId)
            dispatch(pending)
        }
    }

    private fun resolveTriggerPrice(
        trigger: com.qkt.execution.LayerTrigger,
        anchor: BigDecimal,
    ): BigDecimal {
        val at =
            (trigger as? com.qkt.execution.At)
                ?: error("non-Immediate triggers must be At")
        return evaluateAt(at.price, anchor)
    }

    private fun evaluateAt(
        expr: com.qkt.dsl.ast.ExprAst,
        anchor: BigDecimal,
    ): BigDecimal =
        when (expr) {
            com.qkt.dsl.ast.StackEntryRef -> anchor
            is com.qkt.dsl.ast.NumLit -> expr.value
            is com.qkt.dsl.ast.BinaryOp -> {
                val l = evaluateAt(expr.lhs, anchor)
                val r = evaluateAt(expr.rhs, anchor)
                when (expr.op) {
                    com.qkt.dsl.ast.BinOp.PLUS -> l + r
                    com.qkt.dsl.ast.BinOp.MINUS -> l - r
                    com.qkt.dsl.ast.BinOp.STAR -> l * r
                    com.qkt.dsl.ast.BinOp.SLASH -> l.divide(r, com.qkt.common.Money.CONTEXT)
                    else -> error("unsupported op in stack trigger: ${expr.op}")
                }
            }
            else -> error("unsupported trigger expression: $expr")
        }

    private fun resolveLayerQuantity(
        layer: LayerSpec,
        symbol: String,
        side: Side,
        anchor: BigDecimal?,
    ): BigDecimal {
        val sizing = layer.sizing
        if (sizing is com.qkt.dsl.ast.SizeQty) {
            val n = sizing.expr as? com.qkt.dsl.ast.NumLit
                ?: error("STACK layer qty must be a literal in v1")
            return n.value
        }
        // Other sizing forms (RISK, NOTIONAL, %) are wired in Task 17.
        error("non-quantity sizing per layer is wired in a later task")
    }

    private fun buildLayerOrder(
        layerId: String,
        parent: OrderRequest.Stack,
        layer: LayerSpec,
        qty: BigDecimal,
        triggerPrice: BigDecimal?,
    ): OrderRequest =
        when {
            triggerPrice == null -> {
                OrderRequest.Market(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                )
            }
            layer.orderType is com.qkt.dsl.ast.Limit -> {
                OrderRequest.Limit(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    limitPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                )
            }
            else -> {
                OrderRequest.Stop(
                    id = layerId,
                    symbol = parent.symbol,
                    side = parent.side,
                    quantity = qty,
                    stopPrice = triggerPrice,
                    timeInForce = parent.timeInForce,
                    timestamp = clock.now(),
                    strategyId = parent.strategyId,
                )
            }
        }
```

- [ ] **Step 4: Run test — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): OrderManager dispatches Stack and materializes pending layers"
```

---

## Task 14: OrderManager — pending-layer trigger fires + per-stack flat detection

Add a test where a tick crosses layer 2's trigger and the Stop order fires. Then a test where layer 1's bracket SL fires and pending layers are auto-cancelled.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

- [ ] **Step 1: Add tick-cross test**

Append to `OrderManagerStackTest.kt`:

```kotlin
    @Test
    fun `tick crossing layer 2 trigger fires the Stop`() {
        // Set up like before
        val plan =
            StackPlan(
                listOf(
                    layer(1, "0.1"),
                    layer(2, "0.1", BigDecimal("100")),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-2",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        broker.submitted.clear()
        // Tick at 50100 should fire layer 2 Stop.
        bus.publish(
            com.qkt.events.TickEvent(
                tick =
                    com.qkt.marketdata.Tick(
                        symbol = "BTCUSDT",
                        price = BigDecimal("50100"),
                        timestamp = clock.now(),
                        volume = BigDecimal.ONE,
                    ),
            ),
        )
        // Layer 2 should now be SUBMITTED as a Market.
        assertThat(broker.submitted).anyMatch { it.id == "${req.id}-l2" }
    }
```

- [ ] **Step 2: Run — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS — uses the existing `evaluateTriggers` flow which already converts a triggered Stop into a Market.

- [ ] **Step 3: Add flat-detection test**

```kotlin
    @Test
    fun `layer 1 SL fires; pending layers auto-cancel`() {
        val plan =
            StackPlan(
                layers =
                    listOf(
                        layer(1, "0.1"),
                        layer(2, "0.1", BigDecimal("100")),
                        layer(3, "0.1", BigDecimal("200")),
                    ),
                outerBracket =
                    com.qkt.dsl.ast.BracketAst(
                        stopLoss = com.qkt.dsl.ast.ChildBy(NumLit(BigDecimal("50"))),
                    ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-3",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        // Now there are 2 pending layers and 1 filled. SL at 49950 fires.
        bus.publish(
            com.qkt.events.TickEvent(
                com.qkt.marketdata.Tick(
                    "BTCUSDT",
                    BigDecimal("49950"),
                    clock.now(),
                    BigDecimal.ONE,
                ),
            ),
        )
        // Position should be flat. Pending layers cancelled.
        val cancelled = manager.activeOrders().none { it.parentClientOrderId == req.id && it.state == OrderState.PENDING }
        assertThat(cancelled).isTrue
    }
```

- [ ] **Step 4: Wire flat-detection in `OrderManager`**

The existing `onFilled` for layer 1 emits a `BracketEvent.OrderFilled` for its bracket; when a layer's bracket SL or TP fires (terminal close), `OrderManager` already publishes a fill on the SL/TP order. Hook into that:

Add to the bus subscriptions in `init`:

```kotlin
        bus.subscribe<BrokerEvent.OrderFilled> { e -> evaluateStackFlat(e) }
```

(This is in addition to the existing `onFilled` and `onStackLayerFilled`.)

Implement:

```kotlin
    private fun evaluateStackFlat(e: BrokerEvent.OrderFilled) {
        // The SL/TP of a layer order that closes a position will publish a fill.
        // We track per-stack: when the count of open layers (filled minus closed-by-SL/TP) reaches zero, cancel pending and terminate.
        val managed = orders[e.clientOrderId] ?: return
        val parentId = managed.parentClientOrderId ?: return
        // The stack id is the grandparent: parent of layer is stack. But the layer's own SL/TP is parented to the layer's bracket id.
        // For simplicity in v1: walk up parents until we find a stack id, or stop.
        val stackId = walkToStackOwner(parentId) ?: return
        val state = stacks.get(stackId) ?: return
        val openLayers =
            state.filledLayerIds.count { lid ->
                val m = orders[lid] ?: return@count false
                !m.state.isTerminal
            }
        if (openLayers == 0 && state.filledLayerIds.isNotEmpty()) {
            cancelStackPending(stackId)
            stacks.terminate(stackId)
        }
    }

    private fun walkToStackOwner(orderId: String): String? {
        var cursor: String? = orderId
        var depth = 0
        while (cursor != null && depth < 5) {
            val m = orders[cursor] ?: return null
            if (m.request is OrderRequest.Stack) return m.id
            cursor = m.parentClientOrderId
            depth++
        }
        return null
    }

    private fun cancelStackPending(stackId: String) {
        val state = stacks.get(stackId) ?: return
        for (pid in state.pendingLayerIds.toList()) cancel(pid)
    }
```

- [ ] **Step 5: Run tests — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): OrderManager auto-cancels pending stack layers on flat"
```

---

## Task 15: OrderManager — WITHIN deadline

Add deadline expiry: pending layers cancel when `withinMillis` from anchor time elapses.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

- [ ] **Step 1: Add failing test**

```kotlin
    @Test
    fun `WITHIN deadline cancels pending layers`() {
        val plan =
            StackPlan(
                layers =
                    listOf(
                        layer(1, "0.1"),
                        layer(2, "0.1", BigDecimal("100")),
                    ),
                withinMillis = 60_000L,
            )
        val req =
            OrderRequest.Stack(
                id = "stk-w",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.2"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        assertThat(manager.pendingOrders()).hasSize(1)

        // Advance simulated clock past deadline and tick.
        clock.advance(60_001L)
        bus.publish(
            com.qkt.events.TickEvent(
                com.qkt.marketdata.Tick(
                    "BTCUSDT",
                    BigDecimal("50050"),
                    clock.now(),
                    BigDecimal.ONE,
                ),
            ),
        )
        assertThat(manager.pendingOrders()).isEmpty()
    }
```

(`FixedClock.advance(delta)` mutates `time`; if your local FixedClock has a different mutator, mirror it.)

- [ ] **Step 2: Wire deadline check in `evaluateTriggers`**

In `OrderManager.evaluateTriggers(tick: Tick)`, after the existing TimeExit deadline scan, add:

```kotlin
        val nowEpoch = clock.now()
        for (state in stacks.all()) {
            val deadline = state.deadlineEpochMs ?: continue
            if (nowEpoch < deadline) continue
            cancelStackPending(state.id)
            stacks.terminate(state.id)
        }
```

- [ ] **Step 3: Run — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): OrderManager honors STACK WITHIN deadline"
```

---

## Task 16: OrderManager — CANCEL / CLOSE interactions

Verify external `cancel()` of a stack id cancels its pending layers; external `cancel()` of the stream-equivalent action propagates.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (small change to `cancel` or none if the existing `childClientOrderIds` path already handles it)
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

- [ ] **Step 1: Add failing test**

```kotlin
    @Test
    fun `external cancel of stack id cancels its pending layers`() {
        val plan =
            StackPlan(
                listOf(
                    layer(1, "0.1"),
                    layer(2, "0.1", BigDecimal("100")),
                    layer(3, "0.1", BigDecimal("200")),
                ),
            )
        val req =
            OrderRequest.Stack(
                id = "stk-c",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = BigDecimal("0.3"),
                plan = plan,
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            )
        manager.submit(req)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "${req.id}-l1",
                brokerOrderId = "b1",
                price = BigDecimal("50000"),
                quantity = BigDecimal("0.1"),
                timestamp = clock.now(),
            ),
        )
        assertThat(manager.pendingOrders()).hasSize(2)
        manager.cancel("stk-c")
        assertThat(manager.pendingOrders()).isEmpty()
    }
```

- [ ] **Step 2: Confirm or extend cancellation behaviour**

In `OrderManager.cancel`, the existing implementation cancels children when `childClientOrderIds.isNotEmpty()`. Since the Stack's tracked `ManagedOrder.childClientOrderIds` includes layer 1, but layers 2..N are tracked under `parentClientOrderId = stackId` and not added to `childClientOrderIds`, extend `cancel` to also cancel orders whose `parentClientOrderId == clientOrderId`:

```kotlin
    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.request is OrderRequest.Stack) {
            // Cancel pending layers + terminate stack tracker
            stacks.get(clientOrderId)?.let { state ->
                for (pid in state.pendingLayerIds.toList()) cancel(pid)
            }
            stacks.terminate(clientOrderId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            return
        }
        if (managed.childClientOrderIds.isNotEmpty()) {
            for (childId in managed.childClientOrderIds) cancel(childId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING ->
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            else -> broker.cancel(clientOrderId)
        }
    }
```

- [ ] **Step 3: Run — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): cancel stack id cascades to pending layers"
```

---

## Task 17: Per-layer non-quantity sizing at fire time

Wire RISK / NOTIONAL / EQUITY% sizing for stacked layers. Each layer evaluates its sizing at fire time using the engine's current state and the layer's expected entry (= trigger price for triggered layers, current tick price for layer 1).

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`

- [ ] **Step 1: Add failing test (RISK fractional)**

```kotlin
    @Test
    fun `RISK fractional sizing computes layer qty at fire time`() {
        val plan =
            StackPlan(
                layers =
                    listOf(
                        LayerSpec(
                            1,
                            com.qkt.dsl.ast.SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                            AstMarket,
                            com.qkt.execution.Immediate,
                        ),
                    ),
                outerBracket =
                    com.qkt.dsl.ast.BracketAst(
                        stopLoss = com.qkt.dsl.ast.ChildBy(NumLit(BigDecimal("100"))),
                    ),
            )
        // ... rest follows the prior test scaffolding; use a SizingResolver mock or
        // wire a simple test-only equity provider to inject a $10_000 equity value.
        // Expected: with 1% risk and 100-pt SL distance, qty = 0.01 * 10_000 / 100 = 1.0.
    }
```

(Implementation detail: `OrderManager` needs access to a `SizingResolver` or similar. Look at how non-stack RISK sizing is wired today via `SizingCompiler` and follow the same plumbing.)

- [ ] **Step 2: Replace `resolveLayerQuantity` with a real sizing path**

Update `resolveLayerQuantity` to delegate to whatever component computes RISK / NOTIONAL / EQUITY%. The exact wiring depends on existing infrastructure (`SizingCompiler`, account state, etc.). Pseudocode:

```kotlin
    private fun resolveLayerQuantity(
        layer: LayerSpec,
        symbol: String,
        side: Side,
        anchor: BigDecimal?,
    ): BigDecimal {
        val expectedEntry: BigDecimal =
            when (layer.trigger) {
                com.qkt.execution.Immediate -> anchor ?: priceProvider.lastPrice(symbol)
                    ?: error("no current price to size layer ${layer.index} on $symbol")
                is com.qkt.execution.At -> evaluateAt(layer.trigger.price, anchor!!)
            }
        return SizingResolver.resolve(
            sizing = layer.sizing,
            symbol = symbol,
            side = side,
            expectedEntry = expectedEntry,
            outerBracket = stacks.stackOwning(orderIdForLayer(layer))?.let { stacks.get(it)?.outerBracket },
            account = accountState,
        )
    }
```

If the project doesn't yet have a unified sizing resolver, this task may need a small `SizingResolver` object that knows how to compute each `SizingAst` form. Reuse whatever logic already exists in `SizingCompiler`.

- [ ] **Step 3: Run — expect pass**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(app): per-layer sizing resolved at fire time"
```

---

## Task 18: Logging + status snapshot integration

Each layer fire emits a structured log line; the `/status` endpoint (added in 12b) lists pending stack layers per strategy.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (logging)
- Modify: `src/main/kotlin/com/qkt/cli/observe/StatusSnapshot.kt` (add field + populate)
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt`
- Modify: `src/test/kotlin/com/qkt/cli/observe/StatusSnapshotTest.kt` (or create a focused test)

- [ ] **Step 1: Add log on layer fire**

In `OrderManager`, in the place where a Stack layer becomes SUBMITTED (or in `materializePendingLayers`/`fireFallbackTrigger` when the order is a stack-owned layer), add:

```kotlin
        log.info(
            "stack stack_id={} strat_id={} layer={} qty={} trigger={}",
            stackId,
            parent.strategyId,
            layer.index,
            qty,
            triggerPrice ?: "market",
        )
```

- [ ] **Step 2: Extend `StatusSnapshot` with pending stack layers**

In `src/main/kotlin/com/qkt/cli/observe/StatusSnapshot.kt`, add (or extend) a `pendingStackLayers: List<PendingLayerInfo>` field and populate it from `OrderManager.activeOrders()`.

```kotlin
data class PendingLayerInfo(
    val stackId: String,
    val layer: Int,
    val triggerPrice: BigDecimal,
    val side: String,
    val quantity: BigDecimal,
)
```

Source: walk `OrderManager.pendingOrders()`, filter to those whose `parentClientOrderId` resolves (via `walkToStackOwner`) to a Stack id.

- [ ] **Step 3: Test**

Add a small assertion in the existing status snapshot test or in `OrderManagerStackTest` that after submitting a 3-layer stack and filling layer 1, the status snapshot lists 2 pending layers.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.qkt.app.OrderManagerStackTest --tests "com.qkt.cli.observe.*" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/main/kotlin/com/qkt/cli/observe/StatusSnapshot.kt src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "feat(observe): log stack layer fires and expose pending layers in status"
```

---

## Task 19: ActionCompiler — thread `StackAst` into compiled action

So that strategies parsed from text actually get `OrderRequest.Stack` emitted at the right place.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: relevant ActionCompiler test (add a STACK case)

- [ ] **Step 1: Read `ActionCompiler` to find where Buy/Sell get translated to OrderRequest**

Open `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` and locate the function that maps a `Buy(stream, opts)` action into an `OrderRequest`. Where today it returns `OrderRequest.Market`/`OrderRequest.Bracket`/etc., add a branch: if `opts.stack != null`, return `OrderRequest.Stack` with a compiled `StackPlan`.

- [ ] **Step 2: Add the branch**

```kotlin
        if (opts.stack != null) {
            val plan = StackCompiler.compile(opts.stack, opts.sizing, opts.bracket)
            return OrderRequest.Stack(
                id = nextOrderId(),
                symbol = streamToSymbol(action.stream),
                side = if (action is Buy) Side.BUY else Side.SELL,
                quantity = estimateAggregateQty(plan),
                plan = plan,
                timeInForce = opts.tif?.toTif() ?: TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = strategyId,
            )
        }
```

(Helpers `nextOrderId`, `streamToSymbol`, `toTif`, `estimateAggregateQty` should follow whatever exists in the file. `estimateAggregateQty` can sum literal qty layers and fall back to the first-layer's qty when sizing is non-literal.)

- [ ] **Step 3: Add an ActionCompiler test for STACK**

Mirror existing tests in `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt`. Verify that compiling a Buy with a `StackSpacing` opts yields an `OrderRequest.Stack` whose plan has the expected number of layers and correct trigger expressions.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests com.qkt.dsl.compile.ActionCompilerTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ActionCompilerTest.kt
git commit -m "feat(compile): ActionCompiler emits OrderRequest.Stack for STACK opts"
```

---

## Task 20: End-to-end backtest tests

Three synthetic-tick scenarios driving a full strategy through the engine.

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt`

- [ ] **Step 1: Read the existing backtest infrastructure**

Open `src/main/kotlin/com/qkt/backtest/Backtest.kt` and one existing backtest test (e.g., `src/test/kotlin/com/qkt/backtest/BacktestSmokeTest.kt` if it exists, otherwise the closest thing) to learn the harness style: how ticks are fed, how a strategy is compiled and registered, how trades are inspected.

- [ ] **Step 2: Write three scenario tests**

Each is a separate `@Test` method:

(a) **Pyramid happy path.** A strategy of `BUY btc SIZING 0.1 STACK 3 SPACING 100`. Tick stream: 50000 → 50100 → 50200. Assert: three layer fills at exactly those prices, total position 0.3 BTC.

(b) **Early stop-out cancels pending layers.** Same strategy with `BRACKET { STOP LOSS BY 50 }`. Tick stream: 50000 → 49950. Assert: only layer 1 fills, then SL closes layer 1; layers 2 and 3 are cancelled (no fill events, OrderManager has zero pending after the SL fill).

(c) **WITHIN expiry.** `BUY btc SIZING 0.1 STACK 3 SPACING 100 WITHIN 1h`. Tick stream: layer 1 fills at 50000 at t=0. No further price progress. Advance the simulated clock past 1h and feed a tick. Assert: pending layers are cancelled.

Pseudocode for one of them:

```kotlin
    @Test
    fun `pyramid happy path fills all three layers`() {
        val strategyText =
            """
            STRATEGY pyr VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 49000
                THEN BUY btc SIZING 0.1 STACK 3 SPACING 100
            """.trimIndent()
        val strategy = compileFromText(strategyText)
        val backtest = newBacktest(strategy)
        val ticks =
            listOf(
                tick("BTCUSDT", 49500, 0L),
                tick("BTCUSDT", 50000, 60_000L),
                tick("BTCUSDT", 50100, 120_000L),
                tick("BTCUSDT", 50200, 180_000L),
            )
        for (t in ticks) backtest.feed(t)
        backtest.flush()
        val fills = backtest.tradeLog.filter { it.symbol == "BTCUSDT" && it.side == Side.BUY }
        assertThat(fills.map { it.price }).containsExactly(
            BigDecimal("50000"),
            BigDecimal("50100"),
            BigDecimal("50200"),
        )
    }
```

- [ ] **Step 3: Run**

Run: `./gradlew test --tests com.qkt.backtest.StackBacktestTest -q`
Expected: PASS — all three scenarios.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat -q
git add src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt
git commit -m "test(backtest): end-to-end STACK scenarios"
```

---

## Task 21: Version bump, README, phase changelog

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt`
- Modify: `README.md`
- Create: `docs/phases/phase-13a-stack.md`

- [ ] **Step 1: Bump version**

In `src/main/kotlin/com/qkt/cli/BuildInfo.kt`, change `VERSION` from `"0.13.0"` to `"0.14.0"`.

- [ ] **Step 2: Add a STACK example to README**

Append a short section under the existing DSL features section, showing a 3-layer pyramid strategy as one copy-paste block.

- [ ] **Step 3: Write `docs/phases/phase-13a-stack.md`**

Per the qkt skill's phase-changelog requirements (§6 of the qkt SKILL.md), include:

- Summary (2–4 sentences)
- What's new (one line per public type / DSL keyword / function)
- Migration from previous phase (none — STACK is additive)
- Usage cookbook (5+ worked examples covering: simple pyramid, average-down, layer-list, mixed sizing, WITHIN, SELL, FOR/EACH composition)
- Testing patterns (how a strategy author tests a stack: synthetic tick stream, expected fill list)
- Known limitations (per-layer bracket override deferred, WHEN-trigger deferred, slicing deferred, PIPS not yet a unit)
- References (spec, plan, merge SHA — fill in SHA after merge)

Aim for 250–400 lines.

- [ ] **Step 4: Run full build**

Run: `./gradlew build -q`
Expected: `BUILD SUCCESSFUL`. All tests green.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat -q
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md docs/phases/phase-13a-stack.md
git commit -m "chore(cli): bump version to 0.14.0"
git add docs/phases/phase-13a-stack.md
git commit -m "docs: phase 13a changelog"
```

(Two commits if the version bump and changelog are separable; one commit is fine if simpler.)

---

## After all tasks complete

Run the full pre-push checklist (qkt skill §4):

```bash
./gradlew build
git status
git log --oneline main..HEAD
grep -rEn 'TODO|FIXME|XXX' src/
```

Then proceed to merge per the qkt skill (no-ff merge into `main`, branch deletion, push, and the changelog SHA fill-in).

---

## Self-review check

This plan was reviewed against the spec on 2026-05-08:

- §2 Goals — ✓ covered by Tasks 1–20.
- §3 Worked examples — ✓ syntax used in Tasks 4–6 matches §3.1–§3.8.
- §4.1 AST — Task 1.
- §4.2 Lexer — Tasks 2, 3.
- §4.3 Grammar — Tasks 4, 5, 6.
- §4.4 OrderRequest.Stack + OrderManager dispatch — Tasks 11, 13.
- §4.5 OrderManager `Stack` arm — Task 13.
- §4.6 Per-stack flat detection — Task 14.
- §4.7 Determinism — exercised by Task 20 backtest scenarios.
- §4.8 Logging / observability — Task 18.
- §5 Kotlin DSL surface — Task 7.
- §6 Tests — Tasks 4–8 (parser/lexer/builders/round-trip), 10 (compiler), 13–18 (OrderManager), 20 (backtest).
- §7 Risk — covered by tests in Tasks 13–18, 20.
- §8 Phase decomposition — matches this plan's structure.
- §9 Out of scope — none of the deferred items are in any task.
