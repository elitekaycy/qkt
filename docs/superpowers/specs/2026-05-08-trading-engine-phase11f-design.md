# Phase 11f — External SQL-like Parser

**Status:** Design draft.
**Predecessor:** Phase 11e (multi-stream, multi-timeframe, multi-broker, hub).
**Successor:** Phase 12 (CLI runner, deferred).

---

## 1. Mission

After 11e the DSL surface is complete via the Kotlin builder. Phase 11f adds the file-based front-end: a hand-rolled SQL-like parser that reads `.qkt` files from disk and produces the same `StrategyAst` the Kotlin DSL produces. After 11f the engine can run a strategy authored as text:

```
qkt> Dsl.parseFile(Path.of("strategies/momentum_basket.qkt"))  // returns StrategyAst
qkt> AstCompiler().compile(ast).run(...)                       // runs unchanged
```

The runtime, AST, and compiler are unchanged. The parser is a pure front-end: text → AST.

Three architectural decisions are taken before writing the spec, and recorded here as the prevailing design:

1. **Hand-rolled recursive-descent parser.** No ANTLR, no parser combinators, no external grammar tooling. ~550 LoC across `Lexer` and `Parser`. The grammar is closed (we own it); a generator buys nothing and costs error-message quality plus a runtime dep.
2. **SQL conventions.** Strings use single quotes (`'msg'`). Comments are `-- line` and `/* block */`. Keywords are case-insensitive (`STRATEGY` == `strategy`). Identifiers are case-sensitive (`btc` ≠ `BTC`). This matches the "SQL-like" framing in the master spec and the way every quant reads SQL.
3. **Multi-error accumulator with synchronization.** Parser collects `List<ParseError>` instead of throwing on the first error; on error it advances tokens until the next synchronization point (top-level section keyword or rule keyword) and resumes. The result is `Either<List<ParseError>, StrategyAst>` — one diagnostic pass surfaces every typo in the file, not just the first.

---

## 2. Goals

- **`Dsl.parseFile(path: Path): StrategyAst`** — primary entry point. Parses a `.qkt` file end-to-end, returns the AST that `AstCompiler` already understands.
- **`Dsl.parse(source: String): StrategyAst`** — string entry point. Useful for in-process / test contexts.
- **Lexer** — `com.qkt.dsl.parse.Lexer` tokenizes the SQL-like surface. Single-pass, character-by-character, tracks line/column.
- **Parser** — `com.qkt.dsl.parse.Parser` is recursive-descent over the token stream. Pratt-style precedence climbing for expressions. Synchronization-based error recovery.
- **Coverage of every DSL construct that landed in 11b–11e.** Anything the Kotlin DSL can express, the parser can parse and produce an equivalent AST.
- **Multi-error reporting.** Errors carry `(line, col, message)`. On parse failure, the caller receives a `List<ParseError>` covering every diagnosable problem in the file.
- **Round-trip equivalence.** A worked-example fixture runs through the parser, the compiler, and a deterministic backtest — and produces the same `BacktestResult` as the equivalent hand-written Kotlin DSL strategy.

## Non-goals

- **CLI runner.** No `qkt run foo.qkt` command. That is Phase 12 (designed-but-deferred per master spec §10).
- **No new DSL features.** The parser exposes exactly the constructs 11b–11e already implement. If the Kotlin DSL doesn't have it, the parser doesn't have it.
- **No source maps for runtime errors.** Runtime errors at compile or run time refer to AST node positions only via best-effort. Token-level position carry-through is not threaded into AST nodes in 11f.
- **No formatter / pretty-printer.** Reading text → AST is in scope. AST → canonical text isn't.
- **No `INCLUDE` / file imports.** A `.qkt` file is self-contained. Multi-file composition is not in 11f.
- **No type checking beyond what the existing `AstCompiler` already does.** Parser produces structurally valid AST; semantic validation stays where it lives today.
- **No grammar versioning.** Strategy `VERSION n` is a user-facing field on the AST already; the parser doesn't gate features on it.

---

## 3. Worked example

A complete `.qkt` file exercising every major construct. This file is the canonical fixture in `src/test/resources/dsl/momentum_basket.qkt`:

```
-- momentum basket: cross-asset entry on EMA cross with ATR stop and 3:1 RR.

STRATEGY momentum_basket VERSION 1

DEFAULTS {
    SIZING       = RISK ONE_PERCENT
    STOP_LOSS    = BY ATR(SYMBOL, 14) * 2
    TAKE_PROFIT  = RR 3
    TIF          = GTC
    ORDER_TYPE   = LIMIT AT close - 0.001
}

SYMBOLS
    btc    = BYBIT:BTCUSDT       EVERY 1m,
    btc_h1 = BYBIT:BTCUSDT       EVERY 1h,
    gold   = INTERACTIVE:XAUUSD  EVERY 15m,
    aapl   = ALPACA:AAPL         EVERY 5m

LET
    fast      = EMA(close, 9),
    slow      = EMA(close, 21),
    momentum  = (fast - slow) / slow

RULES
    FOR EACH s IN [btc, gold, aapl] DO
        WHEN s.fast CROSSES ABOVE s.slow
         AND ATR(s, 14) > 0.005 * s.close
        THEN BUY s

    WHEN POSITION.btc > 0
     AND btc.momentum < 0
     AND btc_h1.fast < btc_h1.slow
    THEN SELL btc SIZING POSITION.btc

    WHEN ACCOUNT.equity < 9500
    THEN CLOSE_ALL
```

Every construct in this file produces an AST node already defined in `com.qkt.dsl.ast`.

---

## 4. Architecture

```
                       .qkt source text
                              │
                              ▼
                  ┌────────────────────────┐
                  │ Lexer                  │   single pass, char-by-char
                  │  • whitespace/comments │   ~150 LoC
                  │  • numbers, strings    │
                  │  • identifiers/keywords│
                  │  • operators, punct.   │
                  └───────────┬────────────┘
                              │ List<Token>
                              ▼
                  ┌────────────────────────┐
                  │ Parser                 │   recursive descent
                  │  • parseStrategy()     │   Pratt precedence for exprs
                  │  • parseDefaults()     │   ~400 LoC
                  │  • parseSymbols()      │
                  │  • parseLet()          │
                  │  • parseRules()        │
                  │  • parseExpr()         │
                  │  • parsePrimary()      │
                  │  errors: List<ParseError> sync points: top-level kws
                  └───────────┬────────────┘
                              │ ParseResult<StrategyAst>
                              ▼
                  ┌────────────────────────┐
                  │ AstCompiler (existing) │   unchanged from 11b–11e
                  └───────────┬────────────┘
                              │ Strategy
                              ▼
                  Backtest / TradingPipeline
```

Components, by package:

- `com.qkt.dsl.parse.TokenKind` — enum of every token type.
- `com.qkt.dsl.parse.Token` — `(kind, lexeme, line, col)`.
- `com.qkt.dsl.parse.Lexer` — `Lexer(source: String).tokenize() : List<Token>`.
- `com.qkt.dsl.parse.ParseError` — `(line, col, message)`. Throwable for internal sync, surfaced as a list to callers.
- `com.qkt.dsl.parse.Parser` — recursive-descent over the token list.
- `com.qkt.dsl.parse.ParseResult` — sealed `Success(StrategyAst) | Failure(List<ParseError>)`.
- `com.qkt.dsl.parse.Dsl` — top-level `parse(source)` and `parseFile(path)` entry points.

The AST is the only contract between parser and runtime. The parser is purely additive — no existing file outside `com.qkt.dsl.parse` changes.

---

## 5. Grammar

EBNF for the surface 11f parses. Whitespace and comments are skipped between every terminal.

```
strategy        = header defaults? symbols? lets? rules? EOF ;

header          = 'STRATEGY' IDENT 'VERSION' INT ;

defaults        = 'DEFAULTS' '{' defaultsClause* '}' ;
defaultsClause  = ('SIZING'      '=' sizing
                |  'STOP_LOSS'   '=' childPrice
                |  'TAKE_PROFIT' '=' childPrice
                |  'TIF'         '=' tif
                |  'ORDER_TYPE'  '=' orderType
                |  'TRAILING'    '=' orderType) ;

symbols         = 'SYMBOLS' streamDecl (',' streamDecl)* ;
streamDecl      = IDENT '=' IDENT ':' IDENT 'EVERY' tfSpec ;
tfSpec          = INT IDENT ;     -- e.g. 1 m, 5 m, 1 h ; merged in lexer

lets            = 'LET' letDecl (',' letDecl)* ;
letDecl         = IDENT '=' expr ;

rules           = 'RULES' (forEach | whenThen)+ ;
forEach         = 'FOR' 'EACH' IDENT 'IN' '[' IDENT (',' IDENT)* ']' 'DO' rule ;
rule            = whenThen ;
whenThen        = 'WHEN' expr 'THEN' action ;

action          = buy | sell | close | closeAll | cancel | cancelAll | log ;
buy             = 'BUY' IDENT actionOpts? ;
sell            = 'SELL' IDENT actionOpts? ;
close           = 'CLOSE' IDENT ;
closeAll        = 'CLOSE_ALL' ;
cancel          = 'CANCEL' IDENT ;
cancelAll       = 'CANCEL_ALL' ;
log             = 'LOG' STRING ;

actionOpts      = sizingClause | orderTypeClause | tifClause | bracketClause | ocoClause ;
sizingClause    = 'SIZING' sizing ;
orderTypeClause = 'ORDER_TYPE' '=' orderType ;
tifClause       = 'TIF' tif ;
bracketClause   = 'BRACKET' '{' bracketChild (',' bracketChild)* '}' ;
ocoClause       = 'OCO' '{' ocoChild (',' ocoChild)* '}' ;
bracketChild    = ('STOP' 'LOSS' childPrice | 'TAKE' 'PROFIT' childPrice) ;
ocoChild        = ('STOP' 'AT' expr | 'LIMIT' 'AT' expr) ;

childPrice      = ('AT' expr | 'BY' expr | 'PCT' expr | 'RR' expr) ;

sizing          = expr ('USD' | '%' 'OF' 'EQUITY' | '%' 'OF' 'BALANCE')?
                | 'RISK' expr
                | 'RISK' '$' expr
                | 'POSITION' '.' IDENT ;

orderType       = 'MARKET'
                | 'LIMIT' 'AT' expr
                | 'STOP'  'AT' expr ('LIMIT' 'AT' expr)?
                | 'TRAILING' 'BY' expr
                | 'TRAILING' 'PCT' expr ;

tif             = 'GTC' | 'IOC' | 'FOK' | 'DAY' | 'GTD' expr ;

-- Expressions, Pratt-style precedence (low to high):
--   OR < AND < NOT < cmp < +/- < */ < unary < primary
expr            = orExpr ;
orExpr          = andExpr ('OR' andExpr)* ;
andExpr         = notExpr ('AND' notExpr)* ;
notExpr         = 'NOT' notExpr | cmpExpr ;
cmpExpr         = addExpr (('>'|'<'|'>='|'<='|'='|'!='|'CROSSES' ('ABOVE'|'BELOW')|'BETWEEN' addExpr 'AND'|'IN' '[' ... ']') addExpr)* ;
addExpr         = mulExpr (('+'|'-') mulExpr)* ;
mulExpr         = unaryExpr (('*'|'/') unaryExpr)* ;
unaryExpr       = '-' unaryExpr | primary ;

primary         = NUMBER
                | STRING
                | 'true' | 'false'
                | streamField     -- IDENT '.' IDENT
                | call            -- IDENT '(' args? ')'
                | snapshot        -- IDENT '@' snapshotKind
                | aggregate       -- aggFn '(' expr ')' 'SINCE' window
                | caseWhen        -- 'CASE' ('WHEN' expr 'THEN' expr)+ ('ELSE' expr)? 'END'
                | accountRef      -- 'ACCOUNT' '.' IDENT
                | positionRef     -- 'POSITION' '.' IDENT
                | stateAccessor   -- ('POSITION_AVG_PRICE'|'OPEN_ORDERS') '.' IDENT
                | symbolPlaceholder -- 'SYMBOL'
                | IDENT           -- bare LET ref
                | '(' expr ')' ;

snapshotKind    = 'buy' | 'sell' | 'open' | 'T' '-' INT ;
window          = 'OPEN' | 'T' '-' INT ;
aggFn           = 'MAX' | 'MIN' | 'MEAN' | 'SUM' ;

args            = expr (',' expr)* ;
```

Whitespace is significant only as a separator. Newlines are whitespace.

---

## 6. Error reporting

```kotlin
data class ParseError(val line: Int, val col: Int, val message: String)

sealed interface ParseResult<T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Failure<T>(val errors: List<ParseError>) : ParseResult<T>
}
```

The parser collects errors into a mutable list. On any error it throws an internal `ParseException` (caught at the nearest synchronization boundary) carrying the same payload that was added to the list. Synchronization advances the token cursor until it sees one of:

- `DEFAULTS`, `SYMBOLS`, `LET`, `RULES` (top-level sections),
- `WHEN`, `FOR` (rule boundaries),
- `EOF`.

This matches the recursive-descent parsing convention used in compilers like the Kotlin or Rust compiler. The end result: each top-level section is parsed independently; an error in `DEFAULTS` doesn't mask errors in `RULES`.

Error messages follow a consistent shape:

```
line 7:14 — expected '=' after SIZING, got 'BUY'
line 12:3 — unknown stream alias 'btx', did you mean 'btc'?
line 18:9 — RISK requires either a percentage (RISK 1%) or absolute ($) prefix
```

Stretch goal (not scope-gating for 11f): Levenshtein-1 suggestions for unknown identifiers / keywords (`'btx'` → `'btc'`). Reasonable polish.

---

## 7. Testing strategy

Per qkt convention: real types, no mocks, JUnit 5 + AssertJ, deterministic fixtures.

- **Lexer unit tests.** Every token kind has a positive case (recognises) and a negative case (where the surrounding context forces a different lexeme). Edge cases: unterminated strings, unterminated block comments, unknown characters, trailing whitespace, large numbers, exponents, nested-comment behaviour (we don't nest — `/* /* */ */` closes at the first `*/`).

- **Parser unit tests** per production. `parseHeader`, `parseDefaults`, `parseSymbols`, `parseLet`, `parseRules`, `parseExpr`, `parsePrimary`. Exercise both happy paths and the error-recovery paths (assert that a known-bad input emits the expected number of `ParseError`s with expected line/col).

- **Round-trip equivalence tests.** For each fixture in `src/test/resources/dsl/`:
  1. Parse the `.qkt` file → AST_a.
  2. Build the equivalent strategy via the Kotlin DSL → AST_b.
  3. Assert `AST_a == AST_b` (data-class structural equality).
  4. Compile both, run both against the same deterministic tick fixture, assert `BacktestResult` is identical trade-by-trade and equity-by-equity.

- **End-to-end fixtures** mirror the master-spec worked example plus narrower per-construct fixtures:
  - `src/test/resources/dsl/momentum_basket.qkt` — full surface.
  - `src/test/resources/dsl/single_stream_market_buy.qkt` — minimal valid file.
  - `src/test/resources/dsl/multi_timeframe.qkt` — same symbol, two timeframes (regression for 11e behaviour).
  - `src/test/resources/dsl/defaults_with_symbol.qkt` — `SYMBOL` placeholder inside `DEFAULTS`.
  - `src/test/resources/dsl/for_each.qkt` — `FOR EACH` macro expansion.
  - `src/test/resources/dsl/syntax_errors.qkt` — file with multiple intentional errors; assert error count and locations.

---

## 8. Risk

**Risk: Medium-Low.** The parser is bounded, the AST contract is fixed, and there's no runtime change. Mitigations:

- **AST as the contract.** A parser bug cannot break the runtime; a runtime change cannot break the parser. The round-trip equivalence test catches any divergence.
- **Hand-rolled but small.** ~550 LoC is well within "fully understood by one person." Reviewable in one sitting.
- **Multi-error accumulator is well-trodden.** The synchronizing-recursive-descent pattern has decades of compiler precedent. The risk is implementation bugs, not design uncertainty.
- **Fixtures lock behaviour.** Every supported construct gets a fixture. New runtime features in future phases require new fixtures + parser updates — but that's a healthy forcing function.

**Risk: Pratt-style operator precedence subtleties.** Comparison chaining (`a < b < c`), associativity of `CROSSES ABOVE`, mixing `BETWEEN` with arithmetic — these are easy to get wrong. Mitigation: per-precedence-level tests, each asserting the expected AST shape for representative expressions.

**Risk: SYMBOL-prefixed broker keywords colliding with identifier names.** A stream alias named `LIMIT` would conflict with the `LIMIT` keyword. Mitigation: keywords reserved in the lexer; aliases must be non-keyword identifiers. Caught at parse time with a clear error.

---

## 9. Phase decomposition (preview for the plan)

Approximately 22 tasks. Order:

1. `TokenKind` enum + `Token` data class.
2. `Lexer`: whitespace, comments, identifiers, keywords (case-insensitive lookup).
3. `Lexer`: numbers (with decimals, exponents).
4. `Lexer`: strings (single-quoted with escapes).
5. `Lexer`: operators + punctuation (`=`, `==`, `>=`, etc.).
6. `Lexer` test surface (~10 tests).
7. `ParseError` + `ParseResult` + sync-point machinery.
8. `Parser` skeleton: `parseStrategy`, `parseHeader`.
9. `Parser`: `parseDefaults` + `parseSymbols`.
10. `Parser`: `parseLet`.
11. `Parser`: `parseRules` (driver loop).
12. `Parser`: `parseAction` (BUY/SELL/CLOSE/CLOSE_ALL/CANCEL/CANCEL_ALL/LOG).
13. `Parser`: `parseExpr` Pratt skeleton + primaries (literals, idents).
14. `Parser`: `parseExpr` arithmetic + comparisons.
15. `Parser`: `parseExpr` boolean (AND/OR/NOT).
16. `Parser`: `parseExpr` indicator/function calls.
17. `Parser`: `parseExpr` snapshots, aggregates, CASE WHEN, BETWEEN, IN, CROSSES.
18. `Parser`: `parseExpr` ACCOUNT/POSITION/POSITION_AVG_PRICE/SYMBOL.
19. `Parser`: `parseSizing`, `parseOrderType`, `parseTif`, `parseBracket`, `parseOco`, `parseChildPrice`.
20. `Parser`: `parseForEach` macro expansion.
21. `Dsl.parse(source)` + `Dsl.parseFile(path)` entry points.
22. End-to-end fixtures + round-trip equivalence test.
23. (Bonus) Lev-1 suggestion polish for unknown-identifier errors.
24. Phase 11f changelog under `docs/phases/`.

---

## 10. Out of scope (explicit)

- **CLI runner with observability port.** Phase 12, deferred.
- **Source-position threading into AST nodes.** A future phase if runtime errors need to point back at source lines. For now compile-time errors only have AST-level position info, which is fine for parser-emitted errors.
- **Formatter / pretty-printer.** Not in 11f.
- **`INCLUDE` / multi-file strategies.** One file, one strategy. Composition stays at runtime level.
- **Grammar evolution / versioning.** When the surface adds a new construct in a later phase, the parser is updated in the same phase. No version gating in 11f.

---

## 11. References

- Master spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §3 worked example, §5 grammar surface, §7 phase 11f scope.
- Phase 11b (StrategyAst, AstCompiler): `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`.
- Phase 11e (multi-stream / multi-timeframe / multi-broker / forEach / SYMBOL): `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`.
