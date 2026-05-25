# Phase 24 — Risk-sizing primitives

## Summary

Four DSL surface additions that close long-standing gaps in the language:

- `SIZING <N> PCT RISK` — risk-percent sizing as a first-class form (sugar over `SIZING RISK N/100`).
- `WARMUP <N> BARS` on `SYMBOLS` lines — per-stream gate that suppresses rule firing until N closed candles arrive.
- `<expr> IS NULL` / `IS NOT NULL` — explicit test for the internal `Value.Undefined` sentinel.
- `FLATTEN` — keyword alias for `CLOSE_ALL`, reads better in session-end / risk-off rules.

Three of the four (`PCT RISK`, `IS NULL`, `FLATTEN`) compile to existing engine paths — they're parser-surface improvements. Only `WARMUP` adds a new runtime component (`WarmupGate`).

## What's new

### `SIZING N PCT RISK`

```qkt
BUY btc SIZING 0.5 PCT RISK
    BRACKET { STOP_LOSS AT btc.close - atr(btc, 14) * 2, TAKE_PROFIT RR 3 }
```

Constant-folded at parse time to `SizeRiskFrac(NumLit(N/100))`. Rejects non-literal N and N <= 0 at parse time. Requires a `BRACKET STOP_LOSS` — without one the engine has no stop distance to size against.

### `WARMUP N BARS`

```qkt
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS
```

A rule fires only when every stream alias it references has observed N closed candles. A rule that references two streams waits for both to be warm; a stream with no `WARMUP` declared is warm from tick zero.

Counts live closed candles only — historical prefetch lands in Phase 25 with the `qkt fetch` CLI. Engine restart resets the counter.

### `IS NULL` / `IS NOT NULL`

```qkt
WHEN ema(btc.close, 50) IS NOT NULL AND btc.close > ema(btc.close, 50)
THEN BUY btc
```

Tests whether an expression evaluates to `Value.Undefined`. Always returns a boolean — never propagates undefined itself — so it composes safely with `AND` / `OR`. Binds at comparison precedence, tighter than `AND`.

### `FLATTEN`

```qkt
WHEN NOW.hour_utc = 21 THEN FLATTEN              -- session close
WHEN ACCOUNT.realized_pnl < -1000 THEN FLATTEN   -- daily loss kill switch
```

Same compiled action as `CLOSE_ALL`. Pick whichever reads more naturally.

## Migration from previous phase

Pure additions — every existing strategy continues to parse and run unchanged. The `SIZING N PCT RISK` form replaces the manual `LET stopDist / LET riskUsd / LET riskQty` workaround documented under [Computing risk-based size manually](../reference/dsl/sizing.md#computing-risk-based-size-manually), but the workaround still works for cases where you need a sizing expression `PCT RISK` doesn't model (e.g. correlation across positions).

## File map

| Concern | Files |
| --- | --- |
| Lexer tokens (`WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL`) | `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` |
| Parser surface | `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` |
| AST | `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` (`IsNull`), `StrategyAst.kt` (`StreamDecl.warmupBars`) |
| Compile / runtime | `src/main/kotlin/com/qkt/dsl/compile/{ExprCompiler,RuleAliasScan,WarmupGate,AstCompiler,CompiledRule}.kt` |
| Visitor passes patched for `IsNull` | `MetaRef.kt`, `LetResolver.kt`, `SnapshotPlan.kt`, `IterVarSubstitution.kt`, `DefaultsMerge.kt` |
| Editor | `editor/textmate/qkt.tmLanguage.json` |
| Docs | `docs/reference/dsl/{sizing,streams,expressions,actions}.md`, `docs/reference/dsl-grammar.md` |

## Common gotchas

- **`SIZING 1 PCT RISK` without a stop.** Rejected at order submission. Risk sizing needs a stop distance; provide one via `BRACKET STOP_LOSS`.
- **`WARMUP` doesn't backfill.** Counter starts at zero per engine restart. Use it as a guard against not-yet-warm indicators, not as a substitute for historical prefetch.
- **`IS NULL` precedence.** Binds tighter than `AND`, looser than arithmetic. `a + b IS NULL` parses as `(a + b) IS NULL`. `a IS NULL AND b > 0` parses as `(a IS NULL) AND (b > 0)`.
- **`FLATTEN` vs `CLOSE <stream>`.** `FLATTEN` closes every position across every stream the strategy holds; `CLOSE <stream>` only closes that one stream.
