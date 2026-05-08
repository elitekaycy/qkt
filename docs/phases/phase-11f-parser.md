# Phase 11f — External SQL-like Parser

## Summary

Phase 11f adds the file-based front-end. A hand-rolled recursive-descent parser reads `.qkt` files from disk and produces the same `StrategyAst` the Kotlin DSL builders produce. After 11f the engine can run a strategy authored as text — `Dsl.parseFile(Path) → StrategyAst → AstCompiler().compile(ast) → Strategy`. The runtime, AST, and compiler are unchanged. The parser is purely additive: a new package `com.qkt.dsl.parse` is added; nothing else outside that package needed to change beyond a small `ActionScope` builder addition for parity with the parser's no-sizing default.

The DSL surface is **SQL-flavoured**: keywords are case-insensitive, identifiers are case-sensitive, strings use single quotes, comments are `--` line and `/* */` block. Errors are accumulated rather than thrown on the first occurrence; one parse pass surfaces every diagnosable problem in the file by synchronizing at top-level section boundaries (`DEFAULTS`, `SYMBOLS`, `LET`, `RULES`) and rule boundaries (`WHEN`, `FOR`).

## What's new

- `com.qkt.dsl.parse.TokenKind` — exhaustive enum of token types: keywords, literals, identifiers, operators, punctuation, EOF.
- `com.qkt.dsl.parse.Token` — `(kind, lexeme, line, col)`.
- `com.qkt.dsl.parse.Lexer(source).tokenize() : List<Token>` — single-pass character-by-character tokenizer. Skips whitespace and SQL-style comments. Tracks line/column for diagnostics.
- `com.qkt.dsl.parse.ParseError(line, col, message)` — diagnostic record.
- `com.qkt.dsl.parse.ParseResult<T>` — sealed `Success(T) | Failure(List<ParseError>)`.
- `com.qkt.dsl.parse.Parser(tokens).parseStrategy() : ParseResult<StrategyAst>` — recursive-descent parser with Pratt-style precedence climbing for expressions. Synchronizes at section boundaries on error.
- `com.qkt.dsl.parse.IterVarSubstitution` — internal AST walker that substitutes the `FOR EACH` iteration variable with each literal stream alias at parse time. Mirrors the 11e Kotlin builder's `forEach` macro semantics.
- `com.qkt.dsl.parse.Dsl.parse(source: String) : ParseResult<StrategyAst>` — primary string entry point.
- `com.qkt.dsl.parse.Dsl.parseFile(path: Path) : ParseResult<StrategyAst>` — file entry point. Reads the path, parses, returns the result.
- `ActionScope.buy(stream)` and `ActionScope.sell(stream)` — no-sizing overloads, parity with the parser's `BUY btc` form (sizing inherited from `DEFAULTS` at compile time).

## Migration from previous phase

No production code outside `com.qkt.dsl.parse` changes. `ActionScope` gains two no-sizing `buy`/`sell` overloads; existing callers that supply `qty` or `sizing` are unaffected.

The parser produces a `StrategyAst` structurally identical to the one the Kotlin DSL builds. Round-trip equivalence is tested for every supported construct (single stream market buy, multi-timeframe, multi-broker, `FOR EACH` macro, `DEFAULTS` with `SYMBOL` placeholder, the full `momentum_basket` example).

## Usage cookbook

### Parse a file

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Path

val ast =
    when (val r = Dsl.parseFile(Path.of("strategies/momentum_basket.qkt"))) {
        is ParseResult.Success -> r.value
        is ParseResult.Failure -> {
            r.errors.forEach { println("line ${it.line}:${it.col} — ${it.message}") }
            error("parse failed")
        }
    }
val strategy = AstCompiler().compile(ast)
// strategy is a regular `Strategy`, ready to feed into TradingPipeline / Backtest.
```

### Single-stream market buy

```
STRATEGY single_stream VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
```

### Multi-timeframe, cross-timeframe condition

```
STRATEGY mtf VERSION 1

SYMBOLS
    btc    = BACKTEST:BTCUSDT EVERY 1m,
    btc_h1 = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN btc.close > 105 AND btc_h1.close > 100
    THEN BUY btc SIZING 1
```

### Multi-broker

```
STRATEGY mb VERSION 1

SYMBOLS
    btc  = BYBIT:BTCUSDT       EVERY 1m,
    gold = INTERACTIVE:XAUUSD  EVERY 1m,
    aapl = ALPACA:AAPL         EVERY 1m

RULES
    WHEN btc.close > 0
    THEN BUY btc SIZING 1
    WHEN gold.close > 0
    THEN BUY gold SIZING 1
    WHEN aapl.close > 0
    THEN BUY aapl SIZING 1
```

### `FOR EACH` macro — cross-asset entry rule

```
STRATEGY fe VERSION 1

SYMBOLS
    btc  = BACKTEST:BTCUSDT EVERY 1m,
    gold = BACKTEST:XAUUSD  EVERY 1m,
    aapl = BACKTEST:AAPL    EVERY 1m

RULES
    FOR EACH s IN [btc, gold, aapl] DO
        WHEN s.close > 0
        THEN BUY s SIZING 1
```

### `DEFAULTS` with `SYMBOL` placeholder

```
STRATEGY ds VERSION 1

DEFAULTS {
    SIZING       = 1
    STOP_LOSS    = BY ATR(SYMBOL, 14) * 2
    TAKE_PROFIT  = RR 3
    TIF          = GTC
}

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc
```

The bare `BUY btc` action without an inline `SIZING` clause inherits sizing, stop-loss, take-profit, and TIF from `DEFAULTS`. `SYMBOL` inside `DEFAULTS` is bound at action-expansion time (Phase 11e behaviour) — for `btc` it becomes `ATR(btc, 14)`.

### The full `momentum_basket`

See `src/test/resources/dsl/momentum_basket.qkt` — exercises `DEFAULTS`, multi-broker `SYMBOLS`, `FOR EACH`, cross-stream + cross-timeframe rules, `POSITION` and `ACCOUNT` accessors, `CLOSE_ALL`.

## Testing patterns

### Lexer testing

```kotlin
val tokens = Lexer("STRATEGY x VERSION 1").tokenize()
assertThat(tokens.map { it.kind })
    .containsExactly(TokenKind.STRATEGY, TokenKind.IDENT, TokenKind.VERSION, TokenKind.NUMBER, TokenKind.EOF)
```

### Parser unit testing — per production

```kotlin
private fun parse(s: String): ParseResult<StrategyAst> = Parser(Lexer(s).tokenize()).parseStrategy()

@Test
fun `parses STRATEGY name VERSION n`() {
    val r = parse("STRATEGY momentum_basket VERSION 1") as ParseResult.Success
    assertThat(r.value.name).isEqualTo("momentum_basket")
}
```

### Multi-error recovery

```kotlin
@Test
fun `surfaces multiple errors per file`() {
    val r = Dsl.parseFile(Path.of("src/test/resources/dsl/syntax_errors.qkt")) as ParseResult.Failure
    assertThat(r.errors).hasSizeGreaterThanOrEqualTo(3)
}
```

### Round-trip equivalence

```kotlin
val parsed = (Dsl.parseFile(Path.of("strategies/foo.qkt")) as ParseResult.Success).value
val handwritten = strategy("foo", 1) {
    val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
    rule { whenever(btc.close gt 100.bd); then { buy(stream = btc, qty = BigDecimal.ONE.bd) } }
}
assertThat(parsed).isEqualTo(handwritten)  // structural equality on data classes
```

## Known limitations

- **No CLI runner.** Phase 12 will add `qkt run foo.qkt`. 11f only exposes the in-process `Dsl.parseFile`.
- **No source maps from runtime errors back to source lines.** Parse errors carry `(line, col)`; runtime errors at compile or execution time do not yet point back to source positions.
- **No formatter / pretty-printer.** Reading text → AST is supported; AST → canonical text is not.
- **No `INCLUDE` / file imports.** A `.qkt` file is self-contained.
- **Single-quoted strings only.** Double-quoted strings are not recognised. SQL convention.
- **No comment nesting.** `/* /* */ */` closes at the first `*/`. Standard SQL/C behaviour.
- **No grammar versioning.** `STRATEGY ... VERSION n` is a user-facing field on the AST; the parser does not gate features on it.
- **Reserved words cannot be used as identifiers.** A stream alias named `LIMIT` will not parse. Pick non-keyword aliases.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase11f-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase11f.md`
- Master spec (DSL surface, grammar, FOR EACH, broker prefix semantics): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`
- Phase 11e (multi-stream / multi-timeframe / multi-broker / forEach / SYMBOL): `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`
- Merge commit: TBD
