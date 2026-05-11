# Phase 13b — STACK polish, CANCEL, PORTFOLIO

## Summary

Phase 13b ships three pieces:

1. **STACK polish** — close test-coverage gaps from 13a and lift the ChildRr-throws limitation.
2. **CANCEL action** — `CANCEL <stream>` and `CANCEL_ALL` are now usable from the DSL (previously threw `error("deferred")`).
3. **PORTFOLIO** — a new file-level `PORTFOLIO` keyword + `IMPORT` + `WHEN ... RUN` syntax that lets one file compose N strategy files with regime-gated execution.

Phase 13b is additive across all three pieces. One small refactor: `Signal.CancelStacksForSymbol` was renamed to `Signal.CancelPendingForSymbol` and generalized to cancel both stack and non-stack pending orders for a symbol.

## What's new

### α — STACK polish

- **`ChildRr` in stack outerBracket.** `BRACKET { STOP LOSS BY 50, TAKE PROFIT RR 2.0 }` now works. The TP price is `entry + slDistance × multiplier`, computed at layer fill time.
- **Direction-aware order type for stack layers.** `BUY ... STACK 3 SPACING 100 BELOW` and `SELL ... STACK 3 SPACING 100` now generate Limit orders for averaging-into-trend cases (BUY+BELOW, SELL+ABOVE). Previously these always generated Stop orders, which fired on the wrong tick. This was a latent 13a bug; fixed in 13b.
- **3 new backtest scenarios** in `StackBacktestTest`:
  - SELL pyramid fills three layers at decreasing prices.
  - BUY stack with BELOW direction averages down at decreasing prices.
  - Concurrent stacks: one stack going flat does not cancel another's pending layers.

### β — CANCEL action

- `THEN CANCEL <stream>` cancels all pending orders for the stream's symbol within the current strategy. Includes pending stack layers.
- `THEN CANCEL_ALL` cancels all pending orders for the strategy across all symbols.
- Filled positions are NOT touched — use `CLOSE` for that.
- Round-trip equivalence verified.

**Refactor (rename):**
- `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol`.
- `OrderManager.cancelStacksForSymbol(symbol)` → `OrderManager.cancelPendingForSymbol(symbol)`. The new method cancels stack-related AND non-stack pending orders for the symbol (previously only stacks).

### γ — PORTFOLIO

- **New file format**: `PORTFOLIO <name> VERSION <n>` files alongside `STRATEGY` files. Same `.qkt` extension; distinguished by content.
- **`IMPORT 'path' AS alias [HOLD]`** — pulls a strategy file as a named child. Relative paths only (resolved against the portfolio file's directory). The optional `HOLD` keyword keeps a child's open positions alive when its gate flips false.
- **`WHEN <expr> RUN <alias>`** — gates a child's activation by an arbitrary boolean expression evaluated against the portfolio's own SYMBOLS. Same expression language as inside strategies.
- **`RUN <alias>`** — bare always-on activation.
- **A child is active iff ANY of its RUN clauses evaluate true** — multiple gates OR together.
- **Cycle detection at load time.**
- **Children iterate in declaration order** on every tick.
- **AST**: `PortfolioAst`, `ImportClause`, `PortfolioRule` (sealed), `WhenRun`, `AlwaysRun`.
- **Runtime**: `PortfolioStrategy` (a `DslCompiledStrategy`), `PortfolioLoader`, `PortfolioCompiled`, `CompiledChild`.
- **Lexer tokens**: `PORTFOLIO`, `IMPORT`, `AS`, `RUN`, `HOLD`.
- **Parser entry points**: `Parser.parseFile()` returns `ParseResult<ParsedFile>` (sealed `StrategyFile | PortfolioFile`); `Dsl.parseAny(source)` and `Dsl.parseFileAny(path)` are the new entry points.
- **Kotlin DSL**: `portfolio(name, version) { import(...); rules { run(...); whenRun(...) } }` builder.

## Migration

- **Rename** `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol` if you reference it. Same for `OrderManager.cancelStacksForSymbol` → `cancelPendingForSymbol`. Existing call sites in qkt itself were updated.

## Usage cookbook

### CANCEL inside a strategy

```qkt
STRATEGY guard VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 50000
    THEN BUY btc SIZING 0.1
         STACK 3 SPACING 100
    WHEN btc.close < 49000
    THEN CANCEL btc
```

When price drops below 49000, all pending stack layers (and any non-stack pending orders) for `btc` are cancelled. Filled positions remain.

### ChildRr in stack outerBracket

```qkt
BUY btc SIZING 0.1
STACK 3 SPACING 100
BRACKET { STOP LOSS BY 50, TAKE PROFIT RR 2.0 }
```

Each layer's TP fires at `fill_price + (50 × 2.0) = fill_price + 100`. SL at `fill_price - 50`. Risk/reward 1:2.

### Regime-switched portfolio

```qkt
PORTFOLIO trend_or_range VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

IMPORT 'ema_cross.qkt' AS trend
IMPORT 'mean_revert.qkt' AS range

RULES
    WHEN btc.close > 50000 RUN trend
    WHEN btc.close <= 50000 RUN range
```

One child active at any time. When the gate flips, the deactivated child's open positions close at market and pending orders cancel.

### Always-on bundle

```qkt
PORTFOLIO multi VERSION 1

IMPORT 'ema9.qkt' AS ema9
IMPORT 'ema21.qkt' AS ema21

RULES
    RUN ema9
    RUN ema21
```

Both children active throughout. Their signals share the portfolio's PnL/positions (per v1 limitations below).

### HOLD — keep child positions across deactivation

```qkt
PORTFOLIO mixed VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

IMPORT 'scalper.qkt' AS s
IMPORT 'swing.qkt'   AS w HOLD

RULES
    WHEN btc.close > 50000 RUN s
    RUN w
```

When `s`'s gate flips false, `s`'s positions close. When `w`'s gate (always true here, but if it flipped false) deactivates, `w`'s open positions stay open with their existing brackets — only pending entries are cancelled.

### Kotlin DSL parity

```kotlin
val pf = portfolio("trend_or_range", version = 1) {
    val btc = stream("btc", "BACKTEST", "BTCUSDT", "1h")
    import("ema_cross.qkt", alias = "trend")
    import("mean_revert.qkt", alias = "range")
    rules {
        whenRun(btc.close gt 50000.bd, child = "trend")
        whenRun(btc.close le 50000.bd, child = "range")
    }
}
```

## Testing patterns

```kotlin
val portfolioPath = Paths.get("src/test/resources/dsl/portfolio_simple.qkt")
val compiled = PortfolioLoader.load(portfolioPath)
val portfolio =
    PortfolioStrategy(compiled, ExprCompiler(IndicatorBinding.Bag(), AggregateBinding.Bag()))

val result =
    Backtest(
        strategies = listOf("simple" to portfolio),
        ticks = ticks,
    ).run()
```

Standard Backtest harness — PortfolioStrategy implements `DslCompiledStrategy` so it integrates with `bindToHub` like any DSL-compiled strategy.

## Known limitations

- **Per-child PnL/positions drill-down deferred (v1 simplification).** All children of a portfolio share the portfolio's strategyId for PositionTracker / StrategyPnL purposes. Status snapshots show aggregate equity only — not per-child. v2 will introduce per-child strategyIds and the `<portfolio>:<child>` daemon CLI sub-key syntax.
- **Daemon `qkt deploy portfolio.qkt` requires CLI integration (deferred).** The portfolio file format works end-to-end via `PortfolioLoader` + `Backtest` (Kotlin API), but `qkt deploy/list/status/stop` against a `.qkt` portfolio file from the daemon CLI is not yet wired. v2.
- **WEIGHT clause / capital allocation per child not supported.** All children share one equity pool. A child's `RISK 1%` sees the FULL portfolio equity. v2.
- **Import-time parameter overrides not supported** (`WITH { sizing = ... }`). Defer to v2.
- **Same strategy file imported twice with different aliases not supported** (would always be a duplicate without overrides). Defer to v2.
- **Nested PORTFOLIO inside PORTFOLIO not supported.** v2.
- **Hot-reload of portfolio file changes not supported.** Restart to reload. Same constraint as 12c daemon.
- **State persistence across daemon restart not supported.** Children re-evaluate gates fresh on restart.
- **Per-child `DISABLE_ON_ERROR` policy not supported.** Child runtime errors propagate. v2.
- **Portfolio-level risk caps not supported.** Children continue to use per-strategy RiskState.

## References

- Spec: `docs/superpowers/specs/2026-05-09-trading-engine-phase13b-design.md`.
- Plan: `docs/superpowers/plans/2026-05-09-trading-engine-phase13b.md`.
- Phase 13a (STACK) spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase13a-stack-design.md`.
- Phase 13a changelog: `docs/phases/phase-13a-stack.md`.
- Merge commit: TBD (filled in after merge).
