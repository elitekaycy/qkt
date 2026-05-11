# DSL reference

The qkt strategy language, explained one construct at a time. Each page covers a single concept — what it does, every variant the parser accepts, examples for each, and what it pairs with.

If you want the one-page cheat sheet, see [DSL grammar (one-pager)](../dsl-grammar.md). If you want to learn it properly, start at the top of this page and work down.

## Structure of a strategy file

<div class="grid cards" markdown>

- :material-file-document-outline:{ .lg .middle } **The STRATEGY block**

    ---

    The outer envelope: `STRATEGY name VERSION n`, the SYMBOLS / RULES sections, what's required and what's optional.

    [:octicons-arrow-right-24: STRATEGY block](strategy-block.md)

- :material-chart-line:{ .lg .middle } **Streams: SYMBOLS**

    ---

    Declaring which markets your strategy listens to. Broker prefixes, symbols, timeframes, multiple streams.

    [:octicons-arrow-right-24: Streams](streams.md)

- :material-variable:{ .lg .middle } **LET and DEFAULTS**

    ---

    Naming values so you can reuse them. The two ways: `LET` (per-strategy aliases) and `DEFAULTS` (action defaults).

    [:octicons-arrow-right-24: LET / DEFAULTS](let-defaults.md)

</div>

## Conditions — when to act

<div class="grid cards" markdown>

- :material-arrow-decision:{ .lg .middle } **The WHEN clause**

    ---

    The "if" half of every rule. Edge-triggered vs level-triggered, combining conditions with AND/OR/NOT.

    [:octicons-arrow-right-24: WHEN](conditions.md)

- :material-function-variant:{ .lg .middle } **Indicators**

    ---

    Every indicator the parser knows — ema, sma, rsi, atr, vwap, macd, bollinger, donchian (highest/lowest), and the math helpers.

    [:octicons-arrow-right-24: Indicators](indicators.md)

- :material-calculator-variant:{ .lg .middle } **Expressions**

    ---

    Arithmetic, comparisons, account references (`account.equity`), position references (`position(stream)`).

    [:octicons-arrow-right-24: Expressions](expressions.md)

</div>

## Actions — what to do

<div class="grid cards" markdown>

- :material-cash-multiple:{ .lg .middle } **BUY / SELL / CLOSE / CANCEL / LOG**

    ---

    The action verbs. What each does, what they accept, how to combine them.

    [:octicons-arrow-right-24: Actions](actions.md)

- :material-resize:{ .lg .middle } **SIZING**

    ---

    Every way to size a position: fixed lots, percent of equity, fixed USD, risk-based, full-position close.

    [:octicons-arrow-right-24: SIZING](sizing.md)

- :material-bracket-arrow-down:{ .lg .middle } **BRACKET**

    ---

    Atomic stop-loss + take-profit groups. Fixed prices, percent offsets, ATR-based, scale-out targets.

    [:octicons-arrow-right-24: BRACKET](bracket.md)

- :material-stairs-up:{ .lg .middle } **STACK pyramiding**

    ---

    Layered entries — one signal becomes N price-triggered orders. Time fences, custom per-layer sizing.

    [:octicons-arrow-right-24: STACK](stack.md)

</div>

## Looping and composition

<div class="grid cards" markdown>

- :material-repeat-variant:{ .lg .middle } **FOR EACH**

    ---

    Apply the same rule body to multiple streams. AST-level expansion at compile time.

    [:octicons-arrow-right-24: FOR EACH](foreach.md)

- :material-folder-multiple-outline:{ .lg .middle } **PORTFOLIO files**

    ---

    Composing N strategies into a portfolio with regime-gated activation. `IMPORT`, `RUN`, `HOLD`.

    [:octicons-arrow-right-24: PORTFOLIO](portfolio.md)

</div>

## How qkt parses your strategy

When you run `qkt parse strategy.qkt`, the compiler walks the file in this order:

1. **Header** — `STRATEGY name VERSION n` or `PORTFOLIO ...`
2. **DEFAULTS** (optional) — captures default values for all later actions
3. **SYMBOLS** — declares every stream the strategy listens to
4. **LET** (optional) — name-bound expressions for reuse
5. **RULES** — pairs of `WHEN <condition> THEN <action>`
6. **FOR EACH** (optional, end of file) — macro expansion that emits additional rules

Errors are line/column tagged. A typo in `WHEN` or a missing `THEN` produces a clear error pointing to the line, not a cryptic stack trace.

## Quick legal/illegal

```qkt
-- legal: minimum valid strategy
STRATEGY hello VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 0
    THEN LOG INFO "tick received"
```

```qkt
-- illegal: SYMBOLS must come before RULES
STRATEGY hello VERSION 1
RULES
    WHEN btc.close > 0 THEN BUY btc
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
-- parse error: undefined stream 'btc' in RULES (line 3)
```

```qkt
-- illegal: missing VERSION
STRATEGY hello
SYMBOLS ...
-- parse error: expected VERSION after strategy name (line 1)
```

The parser is strict by design. A strategy file that compiles is one where the engine knows exactly what to do — there's no "interpret loosely and hope" mode.

## See also

- [Examples](../../examples/index.md) — every DSL feature used in a real strategy
- [Recipes](../../how-to/index.md) — task-oriented walkthroughs
- [CLI commands](../cli-commands.md) — `qkt parse`, `qkt backtest`, etc.
