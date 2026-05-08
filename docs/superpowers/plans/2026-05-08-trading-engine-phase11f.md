# Phase 11f — External SQL-like Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a hand-rolled recursive-descent parser that reads `.qkt` files and produces the same `StrategyAst` the Kotlin DSL builders produce. After 11f the engine can run a strategy authored as text — same compiler, same runtime.

**Architecture:** Pure additive front-end in `com.qkt.dsl.parse`. `Lexer` walks the source character-by-character emitting `List<Token>`. `Parser` does recursive descent over the token stream with Pratt-style precedence for expressions. Errors accumulate into `List<ParseError>` with synchronization at top-level section boundaries (`DEFAULTS`, `SYMBOLS`, `LET`, `RULES`) and rule boundaries (`WHEN`, `FOR`). Two entry points: `Dsl.parse(source)` and `Dsl.parseFile(path)`. Round-trip equivalence tests assert that parsed `.qkt` and hand-written Kotlin DSL produce identical `BacktestResult`.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase11f-design.md`.

**Branch:** `phase11f-parser` — cut from `main` at start of Task 1.

---

## Design notes

### Lexer

Single class, single pass, character-by-character. Fields:
- `private val src: String`
- `private var pos: Int = 0`
- `private var line: Int = 1`
- `private var col: Int = 1`

Each `read*` method advances `pos`/`line`/`col` and returns a `Token`. Whitespace and comments are skipped between tokens. Two comment styles: `--` line and `/* */` block (non-nesting).

Keyword lookup table is uppercase-canonical; `KEYWORDS[lex.uppercase()]` resolves an identifier to its `TokenKind`. Identifiers that don't match a keyword stay as `IDENT`.

Number lexing supports integers (`100`), decimals (`100.5`), and scientific notation (`1.5e-3`). Stored as `BigDecimal`-compatible string in the lexeme; the parser converts at AST-construction time.

String lexing: single-quoted, `\\` and `\'` escapes only. `LOG 'don\'t fire'` works. No interpolation.

Operators tokenized eagerly: `==`, `!=`, `>=`, `<=` are single tokens, distinct from `=`, `>`, `<`. The `:` punctuation appears in `BYBIT:BTCUSDT` — single-char token.

### Parser

Recursive descent. Each grammar production is a method. Pratt-style precedence climb for expressions: `parseOr → parseAnd → parseNot → parseCmp → parseAdd → parseMul → parseUnary → parsePrimary`.

State:
- `private val tokens: List<Token>`
- `private var pos: Int = 0`
- `private val errors: MutableList<ParseError> = mutableListOf()`

Helpers:
- `peek(): Token` — current token without advancing.
- `advance(): Token` — current token, then increment pos.
- `match(kind: TokenKind): Boolean` — if current matches, advance and return true.
- `expect(kind: TokenKind, msg: String): Token` — match-or-error.
- `error(msg: String): Nothing` — record error, throw `ParseException` to unwind.
- `synchronize()` — advance until peek().kind ∈ SYNC_KINDS.

`SYNC_KINDS = { DEFAULTS, SYMBOLS, LET, RULES, WHEN, FOR, EOF }`.

Top-level driver:

```kotlin
fun parseStrategy(): ParseResult<StrategyAst> {
    val header = tryParse { parseHeader() }
    val defaults = if (peek().kind == DEFAULTS) tryParse { parseDefaults() } else null
    val symbols = tryParse { parseSymbols() }
    val lets = if (peek().kind == LET) tryParse { parseLet() } else emptyList()
    val rules = tryParse { parseRules() }
    return if (errors.isEmpty()) Success(StrategyAst(header.name, header.version, symbols, emptyList(), lets, defaults, rules))
           else Failure(errors.toList())
}

private inline fun <T> tryParse(block: () -> T): T? =
    try { block() } catch (_: ParseException) { synchronize(); null }
```

### File structure

#### New files

```
src/main/kotlin/com/qkt/dsl/parse/
├── TokenKind.kt
├── Token.kt
├── Lexer.kt
├── ParseError.kt
├── ParseResult.kt
├── Parser.kt
└── Dsl.kt                               # parse(source) + parseFile(path)

src/test/kotlin/com/qkt/dsl/parse/
├── LexerTest.kt
├── ParserHeaderTest.kt
├── ParserDefaultsTest.kt
├── ParserSymbolsTest.kt
├── ParserLetTest.kt
├── ParserRulesTest.kt
├── ParserActionTest.kt
├── ParserExprArithmeticTest.kt
├── ParserExprComparisonTest.kt
├── ParserExprBooleanTest.kt
├── ParserExprIndicatorTest.kt
├── ParserExprSnapshotTest.kt
├── ParserExprAccountTest.kt
├── ParserSizingTest.kt
├── ParserOrderTypeTest.kt
├── ParserBracketOcoTest.kt
├── ParserForEachTest.kt
├── ParserErrorRecoveryTest.kt
├── DslEntryTest.kt
└── RoundTripEquivalenceTest.kt

src/test/resources/dsl/
├── single_stream_market_buy.qkt
├── multi_timeframe.qkt
├── multi_broker.qkt
├── for_each.qkt
├── defaults_with_symbol.qkt
├── momentum_basket.qkt
└── syntax_errors.qkt
```

#### Modified files

```
docs/phases/phase-11f-parser.md         # changelog (Task 24)
```

No production code outside `com.qkt.dsl.parse` changes. The AST and compiler stay untouched.

---

## Tasks

### Task 1: TokenKind + Token

Define every token kind we'll lex, plus the `Token` data class with position info.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Create: `src/main/kotlin/com/qkt/dsl/parse/Token.kt`

- [ ] **Step 1: Cut branch**

```bash
git checkout -b phase11f-parser
```

- [ ] **Step 2: Write `TokenKind` enum**

Cover every token from §5 of the spec: keywords, literals, identifiers, operators, punctuation. ~80 entries. Sample shape:

```kotlin
package com.qkt.dsl.parse

enum class TokenKind {
    // Top-level keywords
    STRATEGY, VERSION, DEFAULTS, SYMBOLS, LET, RULES,

    // Rule keywords
    WHEN, THEN, FOR, EACH, IN, DO,

    // Action keywords
    BUY, SELL, CLOSE, CLOSE_ALL, CANCEL, CANCEL_ALL, LOG,

    // Order-type keywords
    MARKET, LIMIT, STOP, TRAILING, AT, BY, PCT,

    // Sizing keywords
    SIZING, RISK, USD, OF, EQUITY, BALANCE, POSITION,
    POSITION_AVG_PRICE, OPEN_ORDERS,

    // Bracket/OCO keywords
    BRACKET, OCO, TAKE, PROFIT, LOSS, RR,

    // TIF keywords
    TIF, GTC, IOC, FOK, DAY, GTD,

    // Indicator/aggregate keywords
    EVERY, CROSSES, ABOVE, BELOW, BETWEEN, AND, OR, NOT,
    CASE, WHEN_KW_DUP_GUARD,        // CASE has its own WHEN context
    ELSE, END, SINCE, OPEN, MAX, MIN, MEAN, SUM,

    // State accessor keywords
    ACCOUNT, SYMBOL,

    // Boolean literals
    TRUE, FALSE,

    // Literals + identifiers
    NUMBER, STRING, IDENT,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, EQEQ, NEQ, GT, LT, GE, LE,
    AT_SIGN, COLON, DOLLAR,

    // Punctuation
    LBRACE, RBRACE, LBRACKET, RBRACKET, LPAREN, RPAREN,
    COMMA, DOT, SEMICOLON,

    // End
    EOF,
}
```

- [ ] **Step 3: Write `Token` data class**

```kotlin
package com.qkt.dsl.parse

data class Token(
    val kind: TokenKind,
    val lexeme: String,
    val line: Int,
    val col: Int,
)
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt \
        src/main/kotlin/com/qkt/dsl/parse/Token.kt
git commit -m "feat(dsl): add TokenKind enum and Token data class"
```

---

### Task 2: Lexer skeleton — whitespace, comments, EOF

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`

- [ ] **Step 1: Implement `Lexer.tokenize()` with whitespace + comments only**

```kotlin
package com.qkt.dsl.parse

class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val tokens = mutableListOf<Token>()

    fun tokenize(): List<Token> {
        while (pos < src.length) {
            skipWhitespaceAndComments()
            if (pos >= src.length) break
            // Will dispatch to read methods in later tasks.
            error("Unrecognized character '${src[pos]}'")
        }
        tokens.add(Token(TokenKind.EOF, "", line, col))
        return tokens.toList()
    }

    private fun skipWhitespaceAndComments() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == ' ' || c == '\t' || c == '\r' -> advance()
                c == '\n' -> { pos++; line++; col = 1 }
                c == '-' && pos + 1 < src.length && src[pos + 1] == '-' -> {
                    while (pos < src.length && src[pos] != '\n') advance()
                }
                c == '/' && pos + 1 < src.length && src[pos + 1] == '*' -> {
                    advance(); advance()
                    while (pos + 1 < src.length && !(src[pos] == '*' && src[pos + 1] == '/')) {
                        if (src[pos] == '\n') { pos++; line++; col = 1 } else advance()
                    }
                    if (pos + 1 < src.length) { advance(); advance() }
                }
                else -> return
            }
        }
    }

    private fun advance() { pos++; col++ }

    private fun error(msg: String): Nothing =
        throw IllegalStateException("Lexer error at line $line col $col: $msg")
}
```

This compiles but throws on any non-whitespace character. Later tasks add real token-reading.

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Lexer.kt
git commit -m "feat(dsl): lexer skeleton with whitespace and comment handling"
```

---

### Task 3: Lexer — identifiers and keywords

Add identifier reading + the keyword lookup table.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LexerTest {
    @Test
    fun `tokenizes case-insensitive keywords`() {
        val tokens = Lexer("STRATEGY strategy Strategy").tokenize()
        assertThat(tokens.map { it.kind })
            .containsExactly(TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.STRATEGY, TokenKind.EOF)
    }

    @Test
    fun `case-sensitive identifiers`() {
        val tokens = Lexer("btc BTC").tokenize()
        assertThat(tokens.map { it.lexeme }).containsExactly("btc", "BTC", "")
        assertThat(tokens[0].kind).isEqualTo(TokenKind.IDENT)
        assertThat(tokens[1].kind).isEqualTo(TokenKind.IDENT)
    }

    @Test
    fun `identifier with underscore and digits`() {
        val tokens = Lexer("btc_h1 my_var2").tokenize()
        assertThat(tokens.map { it.lexeme }).containsExactly("btc_h1", "my_var2", "")
    }

    @Test
    fun `tracks line and column`() {
        val tokens = Lexer("STRATEGY\n  btc").tokenize()
        assertThat(tokens[1].line).isEqualTo(2)
        assertThat(tokens[1].col).isEqualTo(3)
    }
}
```

- [ ] **Step 2: Run, expect compile success but assertion failures**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest
```

Expected: tests fail because `tokenize()` throws on the first letter.

- [ ] **Step 3: Implement identifier/keyword reading**

```kotlin
companion object {
    private val KEYWORDS: Map<String, TokenKind> =
        TokenKind.values()
            .filter { it.name !in setOf("NUMBER", "STRING", "IDENT", "EOF",
                "PLUS", "MINUS", "STAR", "SLASH", "PERCENT",
                "EQ", "EQEQ", "NEQ", "GT", "LT", "GE", "LE",
                "AT_SIGN", "COLON", "DOLLAR",
                "LBRACE", "RBRACE", "LBRACKET", "RBRACKET", "LPAREN", "RPAREN",
                "COMMA", "DOT", "SEMICOLON",
                "WHEN_KW_DUP_GUARD") }
            .associateBy { it.name }
}

private fun readIdentOrKeyword(): Token {
    val startLine = line; val startCol = col
    val start = pos
    while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) advance()
    val lex = src.substring(start, pos)
    val kind = KEYWORDS[lex.uppercase()] ?: TokenKind.IDENT
    return Token(kind, lex, startLine, startCol)
}
```

In `tokenize()`, dispatch:

```kotlin
fun tokenize(): List<Token> {
    while (pos < src.length) {
        skipWhitespaceAndComments()
        if (pos >= src.length) break
        val c = src[pos]
        val token = when {
            c.isLetter() || c == '_' -> readIdentOrKeyword()
            else -> error("Unrecognized character '$c'")
        }
        tokens.add(token)
    }
    tokens.add(Token(TokenKind.EOF, "", line, col))
    return tokens.toList()
}
```

- [ ] **Step 4: Run tests, expect green**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest
```

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): lexer reads identifiers and case-insensitive keywords"
```

---

### Task 4: Lexer — numbers

Decimal, integer, scientific notation. Lexeme stored as string; parser converts to `BigDecimal`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` (extend)

- [ ] **Step 1: Add tests**

```kotlin
@Test
fun `tokenizes integers and decimals`() {
    val tokens = Lexer("100 100.5 0.001").tokenize()
    assertThat(tokens.map { it.lexeme }).containsExactly("100", "100.5", "0.001", "")
    tokens.dropLast(1).forEach { assertThat(it.kind).isEqualTo(TokenKind.NUMBER) }
}

@Test
fun `tokenizes scientific notation`() {
    val tokens = Lexer("1e-3 2.5E+10 1.5e6").tokenize()
    assertThat(tokens.map { it.lexeme }).containsExactly("1e-3", "2.5E+10", "1.5e6", "")
}
```

- [ ] **Step 2: Implement `readNumber()`**

```kotlin
private fun readNumber(): Token {
    val startLine = line; val startCol = col
    val start = pos
    while (pos < src.length && src[pos].isDigit()) advance()
    if (pos < src.length && src[pos] == '.') {
        advance()
        while (pos < src.length && src[pos].isDigit()) advance()
    }
    if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
        advance()
        if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) advance()
        while (pos < src.length && src[pos].isDigit()) advance()
    }
    return Token(TokenKind.NUMBER, src.substring(start, pos), startLine, startCol)
}
```

Add dispatch in `tokenize()`: `c.isDigit() -> readNumber()`.

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): lexer reads numeric literals with scientific notation"
```

---

### Task 5: Lexer — strings (single-quoted with escapes)

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

- [ ] **Step 1: Add tests**

```kotlin
@Test
fun `tokenizes single-quoted strings`() {
    val tokens = Lexer("'hello world'").tokenize()
    assertThat(tokens[0].kind).isEqualTo(TokenKind.STRING)
    assertThat(tokens[0].lexeme).isEqualTo("hello world")
}

@Test
fun `string escapes apostrophe and backslash`() {
    val tokens = Lexer("""'don\'t \\fire'""").tokenize()
    assertThat(tokens[0].lexeme).isEqualTo("""don't \fire""")
}

@Test
fun `unterminated string errors`() {
    org.assertj.core.api.Assertions.assertThatThrownBy { Lexer("'oops").tokenize() }
        .isInstanceOf(IllegalStateException::class.java)
}
```

- [ ] **Step 2: Implement `readString()`**

```kotlin
private fun readString(): Token {
    val startLine = line; val startCol = col
    advance() // opening quote
    val sb = StringBuilder()
    while (pos < src.length && src[pos] != '\'') {
        if (src[pos] == '\\' && pos + 1 < src.length) {
            advance()
            sb.append(when (val esc = src[pos]) {
                '\'' -> '\''; '\\' -> '\\'; 'n' -> '\n'; 't' -> '\t'
                else -> error("Unknown escape '\\$esc'")
            })
            advance()
        } else if (src[pos] == '\n') {
            error("Unterminated string at line $startLine col $startCol")
        } else {
            sb.append(src[pos])
            advance()
        }
    }
    if (pos >= src.length) error("Unterminated string at line $startLine col $startCol")
    advance() // closing quote
    return Token(TokenKind.STRING, sb.toString(), startLine, startCol)
}
```

Dispatch in `tokenize()`: `c == '\'' -> readString()`.

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): lexer reads single-quoted strings with escapes"
```

---

### Task 6: Lexer — operators and punctuation

Single- and multi-character operators, all punctuation. Match longest first (`==` before `=`, `>=` before `>`, etc.).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

- [ ] **Step 1: Add tests**

```kotlin
@Test
fun `tokenizes operators`() {
    val tokens = Lexer("> < >= <= == != = + - * / %").tokenize()
    assertThat(tokens.map { it.kind }).containsExactly(
        TokenKind.GT, TokenKind.LT, TokenKind.GE, TokenKind.LE,
        TokenKind.EQEQ, TokenKind.NEQ, TokenKind.EQ,
        TokenKind.PLUS, TokenKind.MINUS, TokenKind.STAR, TokenKind.SLASH, TokenKind.PERCENT,
        TokenKind.EOF,
    )
}

@Test
fun `tokenizes punctuation`() {
    val tokens = Lexer("{ } [ ] ( ) , . ; : @ $").tokenize()
    assertThat(tokens.map { it.kind }).containsExactly(
        TokenKind.LBRACE, TokenKind.RBRACE,
        TokenKind.LBRACKET, TokenKind.RBRACKET,
        TokenKind.LPAREN, TokenKind.RPAREN,
        TokenKind.COMMA, TokenKind.DOT, TokenKind.SEMICOLON,
        TokenKind.COLON, TokenKind.AT_SIGN, TokenKind.DOLLAR,
        TokenKind.EOF,
    )
}
```

- [ ] **Step 2: Implement operator/punctuation dispatch in `tokenize()`**

Add a big `when (c)` block that matches each operator/punctuation, including two-char lookahead for `==`/`!=`/`>=`/`<=`. Each case constructs a `Token`, advances `pos`, returns to the outer loop.

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): lexer reads operators and punctuation"
```

---

### Task 7: ParseError + ParseResult + ParseException

Error reporting types. ParseException is internal-only (caught at sync points).

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/parse/ParseError.kt`
- Create: `src/main/kotlin/com/qkt/dsl/parse/ParseResult.kt`

- [ ] **Step 1: Define types**

```kotlin
package com.qkt.dsl.parse

data class ParseError(
    val line: Int,
    val col: Int,
    val message: String,
)

internal class ParseException(val error: ParseError) : RuntimeException(error.message)

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Failure<T>(val errors: List<ParseError>) : ParseResult<T>
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/ParseError.kt \
        src/main/kotlin/com/qkt/dsl/parse/ParseResult.kt
git commit -m "feat(dsl): ParseError + ParseResult types"
```

---

### Task 8: Parser skeleton — header and entry point

Walking-skeleton parser that just reads `STRATEGY name VERSION n EOF` and produces a minimal `StrategyAst`.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserHeaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserHeaderTest {
    private fun parse(s: String): ParseResult<com.qkt.dsl.ast.StrategyAst> =
        Parser(Lexer(s).tokenize()).parseStrategy()

    @Test
    fun `parses STRATEGY name VERSION n`() {
        val r = parse("STRATEGY momentum_basket VERSION 1") as ParseResult.Success
        assertThat(r.value.name).isEqualTo("momentum_basket")
        assertThat(r.value.version).isEqualTo(1)
    }

    @Test
    fun `header without version errors`() {
        val r = parse("STRATEGY x") as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }

    @Test
    fun `header with non-integer version errors`() {
        val r = parse("STRATEGY x VERSION abc") as ParseResult.Failure
        assertThat(r.errors).isNotEmpty
    }
}
```

- [ ] **Step 2: Implement `Parser`**

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl

class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private val errors = mutableListOf<ParseError>()

    private val SYNC_KINDS = setOf(
        TokenKind.DEFAULTS, TokenKind.SYMBOLS, TokenKind.LET,
        TokenKind.RULES, TokenKind.WHEN, TokenKind.FOR, TokenKind.EOF,
    )

    fun parseStrategy(): ParseResult<StrategyAst> {
        var name = "_unparsed"
        var version = 0
        try {
            expect(TokenKind.STRATEGY, "expected STRATEGY")
            name = expect(TokenKind.IDENT, "expected strategy name").lexeme
            expect(TokenKind.VERSION, "expected VERSION")
            val v = expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: error("VERSION must be an integer, got '${v.lexeme}'")
        } catch (_: ParseException) { synchronize() }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors.toList())
        return ParseResult.Success(
            StrategyAst(
                name = name,
                version = version,
                streams = emptyList(),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            ),
        )
    }

    // --- helpers ---

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]
    private fun match(kind: TokenKind): Boolean =
        if (peek().kind == kind) { advance(); true } else false
    private fun expect(kind: TokenKind, msg: String): Token {
        if (peek().kind == kind) return advance()
        error("$msg, got '${peek().lexeme}'")
    }
    private fun error(msg: String): Nothing {
        val t = peek()
        val e = ParseError(t.line, t.col, msg)
        errors.add(e)
        throw ParseException(e)
    }
    private fun synchronize() {
        while (peek().kind !in SYNC_KINDS) advance()
    }
}
```

Note: `StrategyAst` requires `name.isNotBlank()` and `version >= 0`; the placeholder `_unparsed` keeps construction valid when an error happens. Tests assert `Failure` separately.

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.ParserHeaderTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): parser skeleton with strategy header"
```

---

### Task 9: Parser — SYMBOLS block

Stream declarations: `IDENT '=' IDENT ':' IDENT 'EVERY' tfSpec`. Comma-separated.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserSymbolsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class ParserSymbolsTest {
    private fun parseStreams(s: String) =
        (Parser(Lexer(s).tokenize()).parseStrategy() as ParseResult.Success).value.streams

    @Test
    fun `parses single stream declaration`() {
        val streams = parseStreams("STRATEGY s VERSION 1\nSYMBOLS btc = BYBIT:BTCUSDT EVERY 1m")
        assertThat(streams).hasSize(1)
        with(streams[0]) {
            assertThat(alias).isEqualTo("btc")
            assertThat(broker).isEqualTo("BYBIT")
            assertThat(symbol).isEqualTo("BTCUSDT")
            assertThat(timeframe).isEqualTo("1m")
        }
    }

    @Test
    fun `parses multiple comma-separated streams`() {
        val streams = parseStreams(
            "STRATEGY s VERSION 1\nSYMBOLS\n  btc = BYBIT:BTCUSDT EVERY 1m,\n  gold = INTERACTIVE:XAUUSD EVERY 15m",
        )
        assertThat(streams).hasSize(2)
        assertThat(streams[1].alias).isEqualTo("gold")
        assertThat(streams[1].timeframe).isEqualTo("15m")
    }
}
```

- [ ] **Step 2: Implement `parseSymbols`**

Add to `parseStrategy` after header parsing. The `tfSpec` is `INT IDENT` joined to a string `"1m"`, `"5m"`, `"1h"`, etc.:

```kotlin
private fun parseSymbols(): List<StreamDecl> {
    val out = mutableListOf<StreamDecl>()
    expect(TokenKind.SYMBOLS, "expected SYMBOLS")
    do {
        val alias = expect(TokenKind.IDENT, "expected stream alias").lexeme
        expect(TokenKind.EQ, "expected '=' after stream alias")
        val broker = expect(TokenKind.IDENT, "expected broker prefix").lexeme
        expect(TokenKind.COLON, "expected ':' between broker and symbol")
        val symbol = expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
        expect(TokenKind.EVERY, "expected EVERY")
        val tfNum = expect(TokenKind.NUMBER, "expected timeframe count").lexeme
        val tfUnit = expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
        out.add(StreamDecl(alias = alias, broker = broker, symbol = symbol, timeframe = "$tfNum$tfUnit"))
    } while (match(TokenKind.COMMA))
    return out
}
```

In `parseStrategy`, call `parseSymbols()` after header (gated on `peek().kind == SYMBOLS`).

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.ParserSymbolsTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): parser handles SYMBOLS block"
```

---

### Task 10: Parser — LET block

`LET name = expr (, name = expr)*`. Requires expression parsing — implement enough of `parseExpr` for numeric literals + identifier refs to make this task self-contained, and grow `parseExpr` in subsequent tasks.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserLetTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class ParserLetTest {
    @Test
    fun `parses single let with numeric literal`() {
        val ast = parse("STRATEGY s VERSION 1\nLET threshold = 100")
        assertThat(ast.lets).hasSize(1)
        assertThat(ast.lets[0].name).isEqualTo("threshold")
        assertThat(ast.lets[0].expr).isEqualTo(com.qkt.dsl.ast.NumLit(BigDecimal("100")))
    }

    @Test
    fun `parses multiple lets`() {
        val ast = parse("STRATEGY s VERSION 1\nLET a = 1, b = 2, c = 3")
        assertThat(ast.lets.map { it.name }).containsExactly("a", "b", "c")
    }
}
```

- [ ] **Step 2: Implement `parseLet` + minimal `parsePrimary` for NUMBER and IDENT**

```kotlin
private fun parseLet(): List<LetDecl> {
    val out = mutableListOf<LetDecl>()
    expect(TokenKind.LET, "expected LET")
    do {
        val name = expect(TokenKind.IDENT, "expected let name").lexeme
        expect(TokenKind.EQ, "expected '=' after let name")
        val expr = parseExpr()
        out.add(LetDecl(name, expr))
    } while (match(TokenKind.COMMA))
    return out
}

private fun parseExpr(): ExprAst = parsePrimary()  // grow in later tasks

private fun parsePrimary(): ExprAst {
    val t = peek()
    return when (t.kind) {
        TokenKind.NUMBER -> { advance(); NumLit(BigDecimal(t.lexeme)) }
        TokenKind.IDENT -> { advance(); Ref(t.lexeme) }
        else -> error("expected expression, got '${t.lexeme}'")
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.ParserLetTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): parser handles LET block with minimal expressions"
```

---

### Task 11: Parser — expressions: arithmetic + parentheses

Pratt precedence climb for `+ - * /` with parentheses.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprArithmeticTest.kt`

- [ ] **Step 1: Tests**

```kotlin
class ParserExprArithmeticTest {
    private fun expr(s: String): ExprAst = parseExprFromHeader("STRATEGY s VERSION 1\nLET x = $s").lets[0].expr

    @Test
    fun `addition is left-associative`() {
        // 1 + 2 + 3 → ((1 + 2) + 3)
        val e = expr("1 + 2 + 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.ADD)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.ADD)
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        val e = expr("1 + 2 * 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.ADD)
        assertThat((e.rhs as BinaryOp).op).isEqualTo(BinOp.MUL)
    }

    @Test
    fun `parentheses override precedence`() {
        val e = expr("(1 + 2) * 3") as BinaryOp
        assertThat(e.op).isEqualTo(BinOp.MUL)
        assertThat((e.lhs as BinaryOp).op).isEqualTo(BinOp.ADD)
    }
}
```

- [ ] **Step 2: Implement `parseAddExpr`, `parseMulExpr`, `parseUnaryExpr`, paren in `parsePrimary`**

```kotlin
private fun parseExpr(): ExprAst = parseAddExpr()

private fun parseAddExpr(): ExprAst {
    var lhs = parseMulExpr()
    while (peek().kind == TokenKind.PLUS || peek().kind == TokenKind.MINUS) {
        val op = if (advance().kind == TokenKind.PLUS) BinOp.ADD else BinOp.SUB
        val rhs = parseMulExpr()
        lhs = BinaryOp(op, lhs, rhs)
    }
    return lhs
}

private fun parseMulExpr(): ExprAst {
    var lhs = parseUnaryExpr()
    while (peek().kind == TokenKind.STAR || peek().kind == TokenKind.SLASH) {
        val op = if (advance().kind == TokenKind.STAR) BinOp.MUL else BinOp.DIV
        val rhs = parseUnaryExpr()
        lhs = BinaryOp(op, lhs, rhs)
    }
    return lhs
}

private fun parseUnaryExpr(): ExprAst {
    if (match(TokenKind.MINUS)) return UnaryOp(UnOp.NEG, parseUnaryExpr())
    return parsePrimary()
}

private fun parsePrimary(): ExprAst {
    val t = peek()
    return when (t.kind) {
        TokenKind.NUMBER -> { advance(); NumLit(BigDecimal(t.lexeme)) }
        TokenKind.IDENT -> { advance(); Ref(t.lexeme) }
        TokenKind.LPAREN -> {
            advance()
            val e = parseExpr()
            expect(TokenKind.RPAREN, "expected ')'")
            e
        }
        else -> error("expected expression, got '${t.lexeme}'")
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.parse.ParserExprArithmeticTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): parser handles arithmetic with precedence and parentheses"
```

---

### Task 12: Parser — expressions: comparisons

Add `> < >= <= == !=` between arithmetic levels.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprComparisonTest.kt`

- [ ] **Step 1: Tests** asserting `1 + 2 > 3` parses to `CmpOp(GT, BinaryOp(ADD, 1, 2), 3)`.
- [ ] **Step 2: Implement `parseCmpExpr`** between bool + add levels.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles comparison operators`.

---

### Task 13: Parser — expressions: AND / OR / NOT

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprBooleanTest.kt`

- [ ] **Step 1: Tests** for left-associative AND/OR, NOT precedence (`NOT a AND b` parses as `(NOT a) AND b`).
- [ ] **Step 2: Implement** `parseOrExpr`, `parseAndExpr`, `parseNotExpr` at the top of the precedence ladder. `parseExpr` becomes `parseOrExpr`.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles boolean AND OR NOT operators`.

---

### Task 14: Parser — function calls (indicators)

`IDENT '(' args? ')'` parsed as `IndicatorCall(name, args)`. Also `funcCall` for stdlib functions like `ABS`, `MIN`, `MAX`, `LOG`, `SQRT`. Distinguish by the registry at AST-compile time, not parse time.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprIndicatorTest.kt`

- [ ] **Step 1: Tests**

```kotlin
@Test
fun `parses indicator call with stream field arg`() {
    val e = expr("EMA(close, 9)") as IndicatorCall
    assertThat(e.name).isEqualTo("EMA")
    assertThat(e.args).hasSize(2)
}

@Test
fun `parses nested indicator calls`() {
    val e = expr("RSI(EMA(close, 9), 14)") as IndicatorCall
    assertThat(e.args[0]).isInstanceOf(IndicatorCall::class.java)
}
```

- [ ] **Step 2: Modify `parsePrimary`** — when current token is IDENT and next is LPAREN, parse as call:

```kotlin
TokenKind.IDENT -> {
    val name = advance().lexeme
    if (match(TokenKind.LPAREN)) {
        val args = mutableListOf<ExprAst>()
        if (peek().kind != TokenKind.RPAREN) {
            args.add(parseExpr())
            while (match(TokenKind.COMMA)) args.add(parseExpr())
        }
        expect(TokenKind.RPAREN, "expected ')' after arguments")
        IndicatorCall(name, args)
    } else if (match(TokenKind.DOT)) {
        val field = expect(TokenKind.IDENT, "expected stream field").lexeme
        StreamFieldRef(name, field)
    } else {
        Ref(name)
    }
}
```

- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles indicator function calls`.

---

### Task 15: Parser — snapshots, aggregates, BETWEEN, IN, CROSSES, CASE WHEN

Larger task — bundles the per-construct snapshot/aggregate/case-when productions.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprSnapshotTest.kt`

- [ ] **Step 1: Tests** — one per construct. Snapshot `fast@buy`, `fast@T-3`. Aggregate `MAX(s.high) SINCE OPEN`. `expr BETWEEN lo AND hi`. `expr IN [a, b, c]`. `lhs CROSSES ABOVE rhs`. `CASE WHEN cond THEN expr ELSE expr END`.

- [ ] **Step 2: Implement** in `parsePrimary` (snapshot, aggregate, case) and `parseCmpExpr` (BETWEEN, IN, CROSSES) since the latter are infix-style.

- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles snapshot, aggregate, between, in, crosses, case-when`.

---

### Task 16: Parser — ACCOUNT, POSITION, POSITION_AVG_PRICE, OPEN_ORDERS, SYMBOL placeholder

All five state accessors.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserExprAccountTest.kt`

- [ ] **Step 1: Tests** for each: `ACCOUNT.equity`, `POSITION.btc`, `POSITION_AVG_PRICE.btc`, `OPEN_ORDERS.btc`, bare `SYMBOL`.

- [ ] **Step 2: Implement** in `parsePrimary` — match keyword, expect `.`, expect IDENT, return appropriate AST node. `SYMBOL` is special: returns `Ref("__SYMBOL__")` (matches the constant from Phase 11e).

- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles account, position, state accessors`.

---

### Task 17: Parser — sizing clauses

`SIZING <expr>`, `SIZING <expr> USD`, `SIZING <expr> % OF EQUITY`, `SIZING <expr> % OF BALANCE`, `SIZING RISK <expr>`, `SIZING RISK $ <expr>`, `SIZING POSITION.<sym>`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserSizingTest.kt`

- [ ] **Step 1: Tests** — one per sizing form, asserting the right `SizingAst` subtype.

- [ ] **Step 2: Implement `parseSizing()`** with lookahead to disambiguate forms.

- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles all sizing modes`.

---

### Task 18: Parser — order types, TIF, child prices

`MARKET`, `LIMIT AT expr`, `STOP AT expr [LIMIT AT expr]`, `TRAILING BY expr`, `TRAILING PCT expr`. TIF: `GTC | IOC | FOK | DAY | GTD expr`. Child prices: `AT expr | BY expr | PCT expr | RR expr`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserOrderTypeTest.kt`

- [ ] **Step 1: Tests** per form.
- [ ] **Step 2: Implement `parseOrderType`, `parseTif`, `parseChildPrice`**.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles order types, TIF, child prices`.

---

### Task 19: Parser — BRACKET and OCO blocks

`BRACKET { STOP LOSS childPrice, TAKE PROFIT childPrice }`. `OCO { STOP AT expr, LIMIT AT expr }`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserBracketOcoTest.kt`

- [ ] **Step 1: Tests** for both bracket and OCO with each child-price variant.
- [ ] **Step 2: Implement `parseBracket`, `parseOco`**.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles BRACKET and OCO blocks`.

---

### Task 20: Parser — actions and DEFAULTS block

Tie together actions (`BUY`, `SELL`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`, `LOG`) with sizing/ordertype/tif/bracket/oco clauses. Implement `parseDefaults()`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserActionTest.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserDefaultsTest.kt`

- [ ] **Step 1: Tests** for each action form + defaults block.
- [ ] **Step 2: Implement `parseAction`** (dispatches by leading keyword) **and `parseDefaults`**.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles actions and DEFAULTS block`.

---

### Task 21: Parser — RULES + WHEN/THEN + FOR EACH

Top-level rules block. `FOR EACH s IN [a, b, c] DO whenThen` expands into N rules at parse time (matches the Kotlin builder's `forEach` macro).

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserRulesTest.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserForEachTest.kt`

- [ ] **Step 1: Tests** including `FOR EACH s IN [btc, gold] DO WHEN s.close > 0 THEN BUY s` expanding to 2 rules.
- [ ] **Step 2: Implement `parseRules` + `parseWhenThen` + `parseForEach`**. The `forEach` substitutes the iteration variable inline by walking the parsed AST and replacing `Ref("s")` / `StreamFieldRef("s", ...)` / `PositionRef("s")` with the literal stream alias. Reuse the substitution helper pattern from `DefaultsMerge.substituteSymbol`.
- [ ] **Step 3: Run + commit:** `feat(dsl): parser handles RULES, WHEN-THEN, and FOR EACH expansion`.

---

### Task 22: Parser — error recovery integration test

Verify that a `.qkt` file with multiple errors produces multiple `ParseError`s, not just the first.

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/parse/ParserErrorRecoveryTest.kt`
- Create: `src/test/resources/dsl/syntax_errors.qkt`

- [ ] **Step 1: Author `syntax_errors.qkt`** with at least three intentional, distinct errors:
  - Bad SIZING clause
  - Unknown keyword in DEFAULTS
  - Malformed WHEN-THEN

- [ ] **Step 2: Test asserts `Failure` with at least 3 errors**, each at the expected line.

- [ ] **Step 3: Refine synchronization** if needed — error count assertion drives any fixes.

- [ ] **Step 4: Commit:** `test(dsl): error recovery surfaces multiple errors per file`.

---

### Task 23: `Dsl.parse(source)` and `Dsl.parseFile(path)` entry points

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/parse/Dsl.kt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/DslEntryTest.kt`

- [ ] **Step 1: Implement entry points**

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Files
import java.nio.file.Path

object Dsl {
    fun parse(source: String): ParseResult<StrategyAst> =
        Parser(Lexer(source).tokenize()).parseStrategy()

    fun parseFile(path: Path): ParseResult<StrategyAst> = parse(Files.readString(path))
}
```

- [ ] **Step 2: Tests** that both entry points return the same result for the same source string.

- [ ] **Step 3: Commit:** `feat(dsl): Dsl.parse and Dsl.parseFile entry points`.

---

### Task 24: End-to-end fixtures + round-trip equivalence

The capstone test. For each fixture, parse → AST_a; build the equivalent strategy via Kotlin DSL → AST_b; assert they're structurally equal AND that running both through `Backtest` produces identical `BacktestResult`.

**Files:**
- Create: `src/test/resources/dsl/single_stream_market_buy.qkt`
- Create: `src/test/resources/dsl/multi_timeframe.qkt`
- Create: `src/test/resources/dsl/multi_broker.qkt`
- Create: `src/test/resources/dsl/for_each.qkt`
- Create: `src/test/resources/dsl/defaults_with_symbol.qkt`
- Create: `src/test/resources/dsl/momentum_basket.qkt`
- Create: `src/test/kotlin/com/qkt/dsl/parse/RoundTripEquivalenceTest.kt`

- [ ] **Step 1: Author each fixture** as the smallest example of the construct it tests.

- [ ] **Step 2: For each fixture, write a Kotlin DSL counterpart** in the test class.

- [ ] **Step 3: Test asserts AST equality + BacktestResult equality.**

```kotlin
@Test
fun `single stream market buy round-trips`() {
    val parsed = Dsl.parseFile(Path.of("src/test/resources/dsl/single_stream_market_buy.qkt"))
    val ast_a = (parsed as ParseResult.Success).value
    val ast_b = strategy(name = "single", version = 1) {
        val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
        rule { whenever(btc.close gt 100.bd); then { buy(stream = btc, qty = BigDecimal.ONE.bd) } }
    }
    assertThat(ast_a).isEqualTo(ast_b)
    val ticks = /* fixture */
    assertThat(Backtest(listOf("s" to AstCompiler().compile(ast_a)), ticks).run().trades)
        .isEqualTo(Backtest(listOf("s" to AstCompiler().compile(ast_b)), ticks).run().trades)
}
```

- [ ] **Step 4: Commit:** `test(dsl): end-to-end round-trip equivalence parser to Kotlin DSL`.

---

### Task 25: Phase 11f changelog

User-facing changelog under `docs/phases/`. Per qkt SKILL.md §6: summary, what's new, migration, usage cookbook with worked examples (parsing each fixture), testing patterns, known limitations, references.

**Files:**
- Create: `docs/phases/phase-11f-parser.md`

- [ ] **Step 1: Write the changelog**.

- [ ] **Step 2: Commit:** `docs: phase 11f changelog`.

---

## Self-review checklist

After all tasks complete:

- [ ] `./gradlew build` green.
- [ ] All commits match `<type>(<scope>): <subject>` per qkt SKILL.md §3.
- [ ] No AI footers, no emoji.
- [ ] No leftover TODO without an issue link.
- [ ] Round-trip equivalence test green for every fixture.
- [ ] Phase 11f changelog `Merge commit:` line filled in after merge.
- [ ] Spec and plan committed alongside code.

---

## Merge

After all tasks pass:

```bash
git checkout main
git merge --no-ff phase11f-parser -m "merge: phase 11f external SQL-like parser"
./gradlew build   # verify
# Update changelog with merge SHA
git add docs/phases/phase-11f-parser.md
git commit -m "docs: link phase 11f changelog to merge commit"
git branch -d phase11f-parser
```
