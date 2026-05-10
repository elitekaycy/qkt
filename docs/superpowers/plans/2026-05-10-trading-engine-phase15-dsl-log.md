# Phase 15 — DSL `LOG` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the DSL `LOG` action expressive — levels (INFO/WARN/ERROR/DEBUG), `{name}` format placeholders, trailing `name=expr` structured fields. Add strategy/child prefix to stdout. Fix Phase 14 latent slash-name file path bug.

**Architecture:** Replace the literal-only `Log("msg")` AST with `Log(level, messageFormat, fields)`. Extend the expression grammar with `Value.Str` for string-literal kvs (no string operators). Compiler renders placeholders, sets `log.*` MDC keys for the duration of the SLF4J call, dispatches to the chosen level. Logback gets a custom `Discriminator` to substitute `/`→`__` in child filenames; stdout pattern adds `[%X{strategy:-main}]`.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, JUnit 5, AssertJ, SLF4J, logback (existing).

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/qkt/cli/daemon/logging/StrategyFilenameDiscriminator.kt` | Logback `ContextBasedDiscriminator` substituting `/`→`__` for SiftingAppender filenames |
| `src/test/kotlin/com/qkt/dsl/parse/LogParserTest.kt` | LOG grammar coverage: levels, placeholders, kvs, malformed inputs |
| `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerLogTest.kt` | Compile-time placeholder validation, MDC set/clear, level dispatch |
| `src/test/kotlin/com/qkt/cli/daemon/logging/LogbackPatternTest.kt` | Stdout `[strategy]` prefix + slash filename substitution |
| `docs/phases/phase-15.md` | Phase changelog |

### Modified files

| Path | Change |
|---|---|
| `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt` | `Log` becomes `(level, messageFormat, fields)`; introduce `LogLevel` enum |
| `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` | Add `StringLit(value: String)` |
| `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt` | Add `Value.Str(v: String)` |
| `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` | Compile `StringLit` to `Value.Str` |
| `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` | `compileLog` rewrite: validate, render, MDC, dispatch by level |
| `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` | Add `WARN`, `ERROR`, `DEBUG` |
| `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` | `parseLog` reads optional level, STRING, then `(IDENT '=' expr)*`; permits STRING in expression position |
| `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt` | Add `log/warn/error/debug` builders |
| `src/main/resources/logback.xml` | Stdout pattern adds `[%X{strategy:-main}]`; SiftingAppender uses the new discriminator |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | `VERSION = "0.17.0"` |
| `README.md` | Phase 15 line |
| `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt` | Update existing `Log("msg")` constructions to the new shape |
| `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt` | LOG round-trip case |

---

## Task 1: Add `Value.Str` to expression value type

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt`

- [ ] **Step 1: Add `Str` variant**

```kotlin
sealed interface Value {
    data class Num(val v: BigDecimal) : Value
    data class Bool(val v: Boolean) : Value
    data class Str(val v: String) : Value
    data object Undefined : Value
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt
git commit -m "feat(dsl): add Value.Str variant for string literals"
```

---

## Task 2: Add `StringLit` AST node + ExprCompiler support

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStringLitTest.kt` (new, 1 test)

- [ ] **Step 1: Inspect `ExprAst` to find where to add the variant**

Run: `grep -n "data class\|sealed " src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt | head -20`

Add `StringLit` alongside `NumLit`:

```kotlin
data class StringLit(val value: String) : ExprAst
```

- [ ] **Step 2: Write failing compiler test**

```kotlin
// src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStringLitTest.kt
package com.qkt.dsl.compile

import com.qkt.dsl.ast.StringLit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerStringLitTest {
    @Test
    fun `StringLit compiles to Value Str`() {
        val compiled = ExprCompiler().compile(StringLit("hello"))
        // Build a minimal EvalContext — copy the cheapest pattern from ActionCompilerExtensionsTest.
        val ctx =
            EvalContext(
                candle =
                    com.qkt.marketdata.Candle(
                        "BTCUSDT",
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ZERO,
                        0L,
                        1L,
                    ),
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = com.qkt.strategy.testStrategyContext(),
            )
        val result = compiled.evaluate(ctx)
        assertThat(result).isEqualTo(Value.Str("hello"))
    }
}
```

- [ ] **Step 3: Run test, verify failure**

Run: `./gradlew test --tests com.qkt.dsl.compile.ExprCompilerStringLitTest`
Expected: FAIL — `StringLit` not handled in `ExprCompiler.compile`.

- [ ] **Step 4: Add `StringLit` handling in ExprCompiler**

In `ExprCompiler.kt`, locate the `compile(expr: ExprAst): CompiledExpr` dispatch. Add a `StringLit` branch:

```kotlin
is StringLit -> CompiledExpr { _ -> Value.Str(expr.value) }
```

- [ ] **Step 5: Run test, verify pass**

Run: `./gradlew test --tests com.qkt.dsl.compile.ExprCompilerStringLitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt \
        src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ExprCompilerStringLitTest.kt
git commit -m "feat(dsl): StringLit AST node compiles to Value.Str"
```

---

## Task 3: Introduce `LogLevel` enum + new `Log` AST shape

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt`

This breaks every caller that constructs `Log("msg")`. Update only the AST in this task; callers fix in subsequent tasks.

- [ ] **Step 1: Replace `Log` definition**

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class Log(
    val level: LogLevel,
    val messageFormat: String,
    val fields: Map<String, ExprAst>,
) : ActionAst
```

- [ ] **Step 2: Run compile, capture every break site**

Run: `./gradlew compileKotlin compileTestKotlin 2>&1 | grep "error:"`

Expected sites:
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt:690` — old call `Log(msg)`
- `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt:101` — old field `log.message`
- `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt:30` — old call `Log("entered long")`

- [ ] **Step 3: Patch each site to the new shape**

Parser:

```kotlin
// In Parser.kt, the LOG branch:
TokenKind.LOG -> {
    advance()
    val msg = expect(TokenKind.STRING, "expected string literal after LOG").lexeme
    Log(LogLevel.INFO, msg, emptyMap())
}
```

(This is a temporary minimal patch — Task 5 rewrites the LOG parser fully.)

ActionCompiler:

```kotlin
// In compileLog, replace `log.message` with `log.messageFormat` and ignore fields/level for now:
private fun compileLog(log: Log): (EvalContext) -> List<Signal> {
    val msg = log.messageFormat
    return { _ ->
        strategyLogger.info(msg)
        emptyList()
    }
}
```

(This is also temporary — Task 6 rewrites it fully.)

Test:

```kotlin
// In ActionCompilerExtensionsTest.kt:
@Test
fun `Log emits no signals`() {
    val sigs =
        ActionCompiler(ExprCompiler(), logger)
            .compile(Log(LogLevel.INFO, "entered long", emptyMap()))
            .invoke(ctx)
    assertThat(sigs).isEmpty()
}
```

- [ ] **Step 4: Add the import for LogLevel where needed**

In `ActionCompilerExtensionsTest.kt` add `import com.qkt.dsl.ast.LogLevel`.

- [ ] **Step 5: Run all tests, verify green**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt \
        src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ActionCompilerExtensionsTest.kt
git commit -m "feat(dsl): introduce LogLevel and structured Log AST shape"
```

---

## Task 4: Add `WARN`, `ERROR`, `DEBUG` tokens

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`

The lexer auto-includes new TokenKind values. No lexer change needed.

- [ ] **Step 1: Add tokens**

In `TokenKind.kt` enum, add:

```kotlin
WARN,
ERROR,
DEBUG,
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt
git commit -m "feat(dsl): add WARN ERROR DEBUG token kinds"
```

---

## Task 5: Parser support for LOG levels + placeholder kvs

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LogParserTest.kt` (new)

- [ ] **Step 1: Write failing parser tests**

```kotlin
// src/test/kotlin/com/qkt/dsl/parse/LogParserTest.kt
package com.qkt.dsl.parse

import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogParserTest {
    private fun parseAction(src: String): com.qkt.dsl.ast.ActionAst {
        val full = """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0
                THEN $src
        """.trimIndent()
        val parsed = (Dsl.parse(full) as ParseResult.Success).value
        return parsed.rules.first().action
    }

    @Test
    fun `LOG literal parses with INFO and no fields`() {
        val action = parseAction("LOG \"hello\"")
        assertThat(action).isEqualTo(Log(LogLevel.INFO, "hello", emptyMap()))
    }

    @Test
    fun `LOG WARN parses with WARN level`() {
        val action = parseAction("LOG WARN \"high\"")
        assertThat(action).isEqualTo(Log(LogLevel.WARN, "high", emptyMap()))
    }

    @Test
    fun `LOG ERROR parses with ERROR level`() {
        val action = parseAction("LOG ERROR \"down\"")
        assertThat(action).isEqualTo(Log(LogLevel.ERROR, "down", emptyMap()))
    }

    @Test
    fun `LOG DEBUG parses with DEBUG level`() {
        val action = parseAction("LOG DEBUG \"tick\"")
        assertThat(action).isEqualTo(Log(LogLevel.DEBUG, "tick", emptyMap()))
    }

    @Test
    fun `LOG with single field parses`() {
        val action = parseAction("LOG \"buy\" qty=1")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "buy", mapOf("qty" to NumLit(java.math.BigDecimal.ONE))),
        )
    }

    @Test
    fun `LOG with multiple fields parses in order`() {
        val action = parseAction("LOG \"trade\" qty=1 price=2")
        val expected = Log(
            LogLevel.INFO,
            "trade",
            linkedMapOf(
                "qty" to NumLit(java.math.BigDecimal.ONE),
                "price" to NumLit(java.math.BigDecimal("2")),
            ),
        )
        assertThat(action).isEqualTo(expected)
    }

    @Test
    fun `LOG with placeholder + matching field parses`() {
        val action = parseAction("LOG \"buy at {price}\" price=1")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "buy at {price}", mapOf("price" to NumLit(java.math.BigDecimal.ONE))),
        )
    }

    @Test
    fun `LOG with placeholder but no matching field is rejected`() {
        val full = """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0
                THEN LOG "buy at {price}"
        """.trimIndent()
        val result = Dsl.parse(full)
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        val errs = (result as ParseResult.Failure).errors
        assertThat(errs.joinToString { it.message }).contains("placeholder")
    }

    @Test
    fun `LOG with duplicate field names is rejected`() {
        val full = """
            STRATEGY t VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0
                THEN LOG "x" qty=1 qty=2
        """.trimIndent()
        val result = Dsl.parse(full)
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        assertThat((result as ParseResult.Failure).errors.joinToString { it.message })
            .contains("duplicate")
    }

    @Test
    fun `LOG with string literal field parses`() {
        val action = parseAction("LOG \"trade\" side=\"BUY\"")
        assertThat(action).isEqualTo(
            Log(LogLevel.INFO, "trade", mapOf("side" to StringLit("BUY"))),
        )
    }
}
```

- [ ] **Step 2: Run tests, verify failures**

Run: `./gradlew test --tests com.qkt.dsl.parse.LogParserTest`
Expected: FAIL across all tests.

- [ ] **Step 3: Rewrite LOG parser**

In `Parser.kt`, replace the existing `TokenKind.LOG` branch:

```kotlin
TokenKind.LOG -> parseLog()
```

Add a private function:

```kotlin
private fun parseLog(): Log {
    expect(TokenKind.LOG, "expected LOG")
    val level =
        when (peek().kind) {
            TokenKind.WARN -> { advance(); LogLevel.WARN }
            TokenKind.ERROR -> { advance(); LogLevel.ERROR }
            TokenKind.DEBUG -> { advance(); LogLevel.DEBUG }
            else -> LogLevel.INFO
        }
    val messageTok = expect(TokenKind.STRING, "expected string literal after LOG")
    val message = messageTok.lexeme
    val fields = linkedMapOf<String, ExprAst>()
    while (peek().kind == TokenKind.IDENT && peekAhead(1).kind == TokenKind.EQ) {
        val name = expect(TokenKind.IDENT, "expected field name").lexeme
        expect(TokenKind.EQ, "expected '='")
        val expr = parseExpr()
        if (fields.containsKey(name)) {
            error(messageTok, "duplicate LOG field '$name'")
        }
        fields[name] = expr
    }
    val placeholders = extractPlaceholders(message)
    val unmatched = placeholders - fields.keys
    if (unmatched.isNotEmpty()) {
        error(messageTok, "LOG placeholder(s) without matching field: ${unmatched.joinToString()}")
    }
    return Log(level, message, fields)
}

private fun extractPlaceholders(s: String): Set<String> {
    val r = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
    return r.findAll(s).map { it.groupValues[1] }.toSet()
}
```

> Adapt `peekAhead`, `error`, `expect`, `parseExpr` to the actual helper names in Parser.kt. The names above are illustrative — check existing usages and copy.

The parser needs `parseExpr` to accept `STRING` tokens at the literal level. Search Parser.kt for the existing literal-handling branch (likely `parsePrimary` or similar):

```kotlin
// Add to the primary expression dispatch:
TokenKind.STRING -> {
    val tok = advance()
    StringLit(tok.lexeme)
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.dsl.parse.LogParserTest`
Expected: all PASS.

- [ ] **Step 5: Run all parser tests for regression**

Run: `./gradlew test --tests "com.qkt.dsl.parse.*"`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/test/kotlin/com/qkt/dsl/parse/LogParserTest.kt
git commit -m "feat(dsl): parser support for LOG levels placeholders and kvs"
```

---

## Task 6: ActionCompiler.compileLog rewrite

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerLogTest.kt` (new)

Compiler validates placeholders (defense-in-depth — parser already rejects), evaluates field expressions, sets `log.*` MDC, dispatches to SLF4J at the chosen level.

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/dsl/compile/ActionCompilerLogTest.kt
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class ActionCompilerLogTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val capturing =
        ch.qos.logback.classic.Logger::class.java
            .let { /* configure later */ null }
    private val logger = LoggerFactory.getLogger("test.log.action") as ch.qos.logback.classic.Logger
    private val captured = mutableListOf<ch.qos.logback.classic.spi.ILoggingEvent>()
    private val appender =
        object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
                captured.add(eventObject)
            }
        }.apply {
            context = (LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext)
            start()
        }

    init {
        logger.addAppender(appender)
        logger.level = ch.qos.logback.classic.Level.DEBUG
    }

    @AfterEach
    fun cleanup() {
        captured.clear()
        MDC.clear()
    }

    @Test
    fun `INFO LOG renders message and fires at INFO`() {
        val action = Log(LogLevel.INFO, "buy at {price}", mapOf("price" to NumLit(BigDecimal("50125"))))
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).isEmpty()
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(ch.qos.logback.classic.Level.INFO)
        assertThat(captured[0].formattedMessage).isEqualTo("buy at 50125")
    }

    @Test
    fun `WARN LOG fires at WARN`() {
        val action = Log(LogLevel.WARN, "drawdown high", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(ch.qos.logback.classic.Level.WARN)
    }

    @Test
    fun `ERROR LOG fires at ERROR`() {
        val action = Log(LogLevel.ERROR, "broker down", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(ch.qos.logback.classic.Level.ERROR)
    }

    @Test
    fun `DEBUG LOG fires at DEBUG`() {
        val action = Log(LogLevel.DEBUG, "tick", emptyMap())
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].level).isEqualTo(ch.qos.logback.classic.Level.DEBUG)
    }

    @Test
    fun `LOG sets log MDC keys for the call`() {
        val action = Log(
            LogLevel.INFO,
            "trade",
            mapOf("qty" to NumLit(BigDecimal("0.5")), "side" to StringLit("BUY")),
        )
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].mdcPropertyMap).containsEntry("log.qty", "0.5")
        assertThat(captured[0].mdcPropertyMap).containsEntry("log.side", "BUY")
    }

    @Test
    fun `LOG clears log MDC keys after the call`() {
        val action = Log(LogLevel.INFO, "x", mapOf("k" to NumLit(BigDecimal.ONE)))
        ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(MDC.get("log.k")).isNull()
    }

    @Test
    fun `compile rejects unmatched placeholder`() {
        val action = Log(LogLevel.INFO, "buy at {price}", emptyMap())
        org.assertj.core.api.Assertions
            .assertThatThrownBy { ActionCompiler(ExprCompiler(), logger).compile(action) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("placeholder")
    }
}
```

- [ ] **Step 2: Run tests, verify failures**

Run: `./gradlew test --tests com.qkt.dsl.compile.ActionCompilerLogTest`
Expected: all FAIL — current `compileLog` ignores fields/level.

- [ ] **Step 3: Rewrite `compileLog`**

In `ActionCompiler.kt`, replace the existing `compileLog`:

```kotlin
private fun compileLog(log: Log): (EvalContext) -> List<Signal> {
    val placeholders = extractPlaceholders(log.messageFormat)
    val unmatched = placeholders - log.fields.keys
    check(unmatched.isEmpty()) {
        "LOG placeholder(s) without matching field: ${unmatched.joinToString()}"
    }
    val compiledFields = log.fields.mapValues { (_, expr) -> exprCompiler.compile(expr) }
    return { ctx ->
        val resolved = compiledFields.mapValues { (_, ce) -> ce.evaluate(ctx) }
        val rendered = renderMessage(log.messageFormat, resolved)
        try {
            for ((k, v) in resolved) {
                org.slf4j.MDC.put("log.$k", stringify(v))
            }
            when (log.level) {
                LogLevel.DEBUG -> strategyLogger.debug(rendered)
                LogLevel.INFO -> strategyLogger.info(rendered)
                LogLevel.WARN -> strategyLogger.warn(rendered)
                LogLevel.ERROR -> strategyLogger.error(rendered)
            }
        } finally {
            for (k in resolved.keys) org.slf4j.MDC.remove("log.$k")
        }
        emptyList()
    }
}

private fun extractPlaceholders(s: String): Set<String> {
    val r = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
    return r.findAll(s).map { it.groupValues[1] }.toSet()
}

private fun renderMessage(format: String, resolved: Map<String, Value>): String {
    var out = format
    for ((k, v) in resolved) {
        out = out.replace("{$k}", stringify(v))
    }
    return out
}

private fun stringify(v: Value): String =
    when (v) {
        is Value.Num -> v.v.toPlainString()
        is Value.Bool -> v.v.toString()
        is Value.Str -> v.v
        is Value.Undefined -> "undefined"
    }
```

Imports needed (add to top of file):

```kotlin
import com.qkt.dsl.ast.LogLevel
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.dsl.compile.ActionCompilerLogTest`
Expected: all PASS.

- [ ] **Step 5: Run all action-compiler tests**

Run: `./gradlew test --tests "com.qkt.dsl.compile.*"`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/ActionCompilerLogTest.kt
git commit -m "feat(dsl): compileLog renders placeholders sets MDC dispatches by level"
```

---

## Task 7: Kotlin DSL builders for log/warn/error/debug

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`

- [ ] **Step 1: Locate the existing action builders**

Run: `grep -n "fun buy\|fun sell\|fun cancel\|fun close" src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt`

- [ ] **Step 2: Add log/warn/error/debug**

```kotlin
fun log(message: String, vararg fields: Pair<String, ExprAst>) {
    actions.add(Log(LogLevel.INFO, message, linkedMapOf(*fields)))
}

fun warn(message: String, vararg fields: Pair<String, ExprAst>) {
    actions.add(Log(LogLevel.WARN, message, linkedMapOf(*fields)))
}

fun error(message: String, vararg fields: Pair<String, ExprAst>) {
    actions.add(Log(LogLevel.ERROR, message, linkedMapOf(*fields)))
}

fun debug(message: String, vararg fields: Pair<String, ExprAst>) {
    actions.add(Log(LogLevel.DEBUG, message, linkedMapOf(*fields)))
}
```

(Adjust `actions` field name to match the actual receiver used in ActionScope.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/kotlin/ActionScope.kt
git commit -m "feat(dsl): Kotlin DSL log warn error debug builders"
```

---

## Task 8: Round-trip equivalence test for LOG

**Files:**
- Modify: `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`

- [ ] **Step 1: Add test case**

```kotlin
@Test
fun `LOG with level placeholder field round trips`() {
    val parsed =
        (
            Dsl.parse(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                    WHEN btc.close > 100
                    THEN LOG WARN "buy at {price}" price=btc.close
                """.trimIndent(),
            ) as ParseResult.Success
        ).value
    val handwritten =
        strategy("t", 1) {
            val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
            rule {
                whenever(btc.close gt 100.bd)
                then { warn("buy at {price}", "price" to btc.close) }
            }
        }
    assertThat(parsed).isEqualTo(handwritten)
}
```

> The `btc.close` in the kvs Pair returns an ExprAst from the existing builders. Verify the existing infix `gt` and the column accessor exposes `ExprAst` directly so `"price" to btc.close` typechecks.

- [ ] **Step 2: Run test**

Run: `./gradlew test --tests com.qkt.dsl.parse.RoundTripEquivalenceTest`
Expected: PASS (green for the new case + all existing).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt
git commit -m "test(dsl): round-trip LOG with level placeholder field"
```

---

## Task 9: Custom logback `StrategyFilenameDiscriminator`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/logging/StrategyFilenameDiscriminator.kt`

- [ ] **Step 1: Create the discriminator**

```kotlin
package com.qkt.cli.daemon.logging

import ch.qos.logback.classic.sift.MDCBasedDiscriminator

/**
 * Logback discriminator that substitutes `/` with `__` in the strategy MDC value
 * so per-strategy log file names are filesystem-safe for child names like
 * `mybook/trend`. Mirrors `StateDir.logFile`.
 */
class StrategyFilenameDiscriminator : MDCBasedDiscriminator() {
    init {
        key = "strategy_filename"
        defaultValue = "main"
    }

    override fun getDiscriminatingValue(e: ch.qos.logback.classic.spi.ILoggingEvent): String {
        val raw = e.mdcPropertyMap?.get("strategy") ?: return "main"
        return raw.replace("/", "__")
    }
}
```

> If `MDCBasedDiscriminator` does not expose the right base behavior, switch to extending `ContextBasedDiscriminator` and overriding `getDiscriminatingValue` only. Verify by reading the logback-classic 1.4.x source for the closest match.

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/logging/StrategyFilenameDiscriminator.kt
git commit -m "feat(daemon): logback discriminator substitutes slash with double underscore"
```

---

## Task 10: Update logback.xml — stdout pattern + sift discriminator

**Files:**
- Modify: `src/main/resources/logback.xml`

- [ ] **Step 1: Update stdout pattern**

Change:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
```

to:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{strategy:-main}] %logger{36} - %msg%n</pattern>
```

- [ ] **Step 2: Replace SiftingAppender discriminator**

Change:

```xml
<appender name="STRATEGY_FILE" class="ch.qos.logback.classic.sift.SiftingAppender">
    <discriminator>
        <key>strategy</key>
        <defaultValue>main</defaultValue>
    </discriminator>
    <sift>
        <appender name="FILE-${strategy}" class="ch.qos.logback.core.FileAppender">
            <file>${QKT_STATE_DIR:-${user.home}/.local/state/qkt}/logs/${strategy}.log</file>
            ...
```

to:

```xml
<appender name="STRATEGY_FILE" class="ch.qos.logback.classic.sift.SiftingAppender">
    <discriminator class="com.qkt.cli.daemon.logging.StrategyFilenameDiscriminator"/>
    <sift>
        <appender name="FILE-${strategy_filename}" class="ch.qos.logback.core.FileAppender">
            <file>${QKT_STATE_DIR:-${user.home}/.local/state/qkt}/logs/${strategy_filename}.log</file>
            ...
```

- [ ] **Step 3: Run all tests for regression**

Run: `./gradlew test`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/logback.xml
git commit -m "chore(daemon): logback shows strategy prefix on stdout and safe filenames"
```

---

## Task 11: Logback pattern integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/daemon/logging/LogbackPatternTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.qkt.cli.daemon.logging

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class LogbackPatternTest {
    @Test
    fun `child strategy name with slash maps to underscore filename`(@TempDir tmp: Path) {
        System.setProperty("QKT_STATE_DIR", tmp.toString())
        val logger = LoggerFactory.getLogger("test.logback.pattern")
        try {
            MDC.put("strategy", "mybook/trend")
            logger.info("hello from child")
        } finally {
            MDC.remove("strategy")
        }
        // Force logback to flush.
        (LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext).stop()

        val safeFile = tmp.resolve("logs/mybook__trend.log")
        val unsafeFile = tmp.resolve("logs/mybook/trend.log")
        assertThat(Files.exists(safeFile)).isTrue
        assertThat(Files.exists(unsafeFile)).isFalse
        assertThat(Files.readString(safeFile)).contains("hello from child")
    }
}
```

> The test relies on `QKT_STATE_DIR` being honored at logback init time. If logback was already initialized before the system property is set, this test won't observe the override. The cleanest fix is to mark the test class with logback config-reset logic, or run it via `@TestMethodOrder` last. If unstable, restructure to use a per-test logback context.

- [ ] **Step 2: Run test**

Run: `./gradlew test --tests com.qkt.cli.daemon.logging.LogbackPatternTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/cli/daemon/logging/LogbackPatternTest.kt
git commit -m "test(daemon): slash strategy names produce underscore log filenames"
```

---

## Task 12: Version bump + README + phase changelog

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt` (`VERSION = "0.17.0"`)
- Modify: `README.md`
- Create: `docs/phases/phase-15.md`

- [ ] **Step 1: Bump version**

```kotlin
const val VERSION: String = "0.17.0"
```

- [ ] **Step 2: README**

Update the latest-release line and add a feature entry under the existing list:

```
- **DSL `LOG` action** — levels (`INFO`/`WARN`/`ERROR`/`DEBUG`), `{name}` placeholders, structured `key=expr` fields. Stdout shows `[strategy]` prefix; child log files use safe `__`-substituted names ([phase 15](docs/phases/phase-15.md)).
```

Bump release link to `v0.17.0`.

- [ ] **Step 3: Phase changelog**

Create `docs/phases/phase-15.md` with the seven required sections per qkt SKILL §6: Summary, What's new, Migration, Usage cookbook (cover INFO/WARN/ERROR/DEBUG, placeholder, kvs, composition with portfolios), Testing patterns, Known limitations, References.

Cookbook examples must include:
- A simple `LOG "hello"` rendered on stdout with `[strategy]` prefix.
- `LOG WARN "drawdown"` showing it stands out in the file at WARN level.
- `LOG "buy at {p}" p=btc.close` with the rendered output.
- `LOG ERROR "code" retry=3` with the structured-MDC observation.
- A child strategy emitting LOG → file path is `mybook__trend.log`.

Known limitations: no string concatenation in expression grammar, no escaping of `{` in messages, JSON appender plumbing deferred.

- [ ] **Step 4: Update backlog**

In `docs/backlog.md`, find the line:

```
- `tbd` — DSL `LOG` action: emit log lines from inside the DSL with strategy/child prefix
```

Replace `tbd` with `done` and append `(see [phase 15](phases/phase-15.md))`:

```
- `done` — DSL `LOG` action: emit log lines from inside the DSL with strategy/child prefix (see [phase 15](phases/phase-15.md))
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md \
        docs/phases/phase-15.md docs/backlog.md
git commit -m "chore(cli): bump version to 0.17.0 and add phase 15 changelog"
```

---

## Task 13: Final precheck and merge

- [ ] **Step 1: Run precheck**

Run: `./scripts/precheck.sh`
Expected: All steps green.

- [ ] **Step 2: Verify commit log conventions**

Run: `git log --oneline main..HEAD`
Expected: every commit follows §3 conventions, no AI references, no emoji.

- [ ] **Step 3: Use `superpowers:finishing-a-development-branch`**

Announce and follow that skill to merge.

---

## Self-Review

**Spec coverage check:**
- Levels (INFO/WARN/ERROR/DEBUG) — Tasks 3, 4, 5, 6
- `{name}` placeholders — Tasks 5 (parser validation), 6 (compiler render)
- Structured `name=expr` fields with MDC `log.*` namespacing — Task 6
- String literals in expression position — Tasks 1, 2 (`Value.Str` + `StringLit`), Task 5 (parser)
- Stdout `[%X{strategy:-main}]` prefix — Task 10
- Slash `/`→`__` filename substitution — Tasks 9, 10
- Kotlin DSL parity — Task 7
- Round-trip equivalence — Task 8
- Logback integration test for slash names — Task 11
- Version bump + README + changelog + backlog — Task 12

**Placeholder scan:** No `TBD`/`TODO`/"fill in later" markers in the plan body. All steps include either complete code or specific file paths to inspect first. Cautionary `>` notes flag external-API uncertainties (logback `Discriminator` base class, `ActionScope.actions` field name) that the implementer must verify by reading existing source — they are not gaps in the plan, they are explicit verification requests.

**Type consistency check:**
- `LogLevel` enum order — Task 3 defines `DEBUG, INFO, WARN, ERROR`; Task 6 uses the same names in `when`. Consistent.
- `Log(level, messageFormat, fields)` field order — Task 3 defines, Task 5 / Task 6 / Task 7 / Task 8 all construct in the same order.
- `Value.Str(v: String)` — Task 1 defines, Task 2 produces, Task 6 stringifies. Consistent.
- MDC key namespace `log.<name>` — Task 6 defines and clears; Task 11 validates structured keys persist across logback. Consistent.

**Open verifications during execution:**
- `Parser.kt` helper names (`peekAhead`, `expect`, `error`) and the existing primary-expression dispatch site (Task 5).
- `ActionScope` actions field name (Task 7).
- Logback `MDCBasedDiscriminator` vs `ContextBasedDiscriminator` API choice (Task 9).
- `QKT_STATE_DIR` system property reset for Task 11 (logback-context lifecycle).
