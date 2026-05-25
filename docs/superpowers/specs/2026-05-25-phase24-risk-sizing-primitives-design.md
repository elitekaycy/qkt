# Phase 24 — risk-sizing DSL primitives

> Spec for the second of three "docs-to-engine catch-up" phases. Phase 23 closed the indicator-and-position catalog gap; this phase adds four small primitives that strategy authors reach for repeatedly and currently have to hand-roll. Phase 25 follows with operator tooling.

## Goal

Ship four DSL surface primitives — `SIZING N PCT RISK`, `WARMUP N BARS`, `IS NULL` / `IS NOT NULL`, `FLATTEN` — so authors stop writing workarounds for capabilities the engine already has, and so DSL strategies gain a per-stream warmup gate that mirrors what hand-written Kotlin strategies get via `Warmable`.

## Motivation

A recent audit of in-flight strategies surfaced four recurring patterns that don't have a clean DSL form today:

- **`SIZING N PCT RISK`** — the engine compiles `SIZING RISK <fraction>` end-to-end (it's the `SizeRiskFrac` path in `SizingCompiler`), but authors keep mistyping the fraction. `0.005` reads as "0.5%" to a human and gets entered as `0.05` instead. A percent-sugar form removes the decimal-shift class of bugs.
- **`WARMUP N BARS`** — hand-written Kotlin strategies declare warmup via the `Warmable` interface and the engine's `WarmupSpec`. Compiled DSL strategies have no such hook, so an EMA crossover rule fires on the first tick before the EMA is ready. Authors work around this by adding `WHEN fast > 0 AND slow > 0 AND ...` clauses that don't survive review.
- **`IS NULL` / `IS NOT NULL`** — `Value.Undefined` already represents "missing" (indicator not ready, snapshot absent, type mismatch). Today the only way to test for it from the DSL is through indirect comparisons that silently propagate undefined and read as bugs. SQL-style `IS NULL` makes the intent legible.
- **`FLATTEN`** — `CLOSE_ALL` exists and does exactly what FLATTEN means in industry parlance. The keyword alias costs almost nothing and matches the vocabulary quants already use.

Three of the four primitives are surface-only changes over engine capabilities that already exist. The fourth (`WARMUP`) adds one small runtime gate.

## Scope

### In scope

**A. `SIZING N PCT RISK`** — parser sugar over `SIZING RISK <fraction>`.

- New keyword: none (`PCT`, `RISK` already exist).
- Parser change: in `Parser.parseSizing()`'s `else` branch (where `<expr>` is followed by a percent/USD/etc. discriminator), add a `TokenKind.PCT` arm alongside the existing `TokenKind.PERCENT` arm. Inside, expect `RISK`; constant-fold `N / 100` at parse time; emit `SizeRiskFrac(NumLit(N / 100))`.
- No AST change. No compiler change.
- Reuses the existing stop-distance requirement: compile-time error if there's no resolvable `BRACKET STOP LOSS`.

**B. `WARMUP N BARS`** — per-stream gate that suppresses rule firing.

- New keywords: `WARMUP`, `BARS`.
- AST change: extend `StreamDecl` with `warmupBars: Int? = null`.
- Parser change: in the SYMBOLS-line parser, after `EVERY <tf>`, optionally consume `WARMUP <int> BARS`. Reject non-positive counts at parse time.
- Compiler change: build a `WarmupGate` from the per-stream warmup map. Wire it into `DslCompiledStrategy`'s closed-candle callback.
- Runtime semantics: a rule does not fire on a given tick until every stream alias the rule references has observed at least its declared `warmupBars` count of closed candles. A rule referencing zero streams (e.g., `NOW.hour_utc`-only) is always warm.
- Engine restart resets the counter. Documented limitation.

**C. `IS NULL` / `IS NOT NULL`** — explicit test against `Value.Undefined`.

- New keywords: `IS`, `NULL`. (`NOT` already exists.)
- AST change: new node `data class IsNull(val expr: ExprAst, val negated: Boolean) : ExprAst`.
- Parser change: at comparison precedence (peer of `BETWEEN`, `IN`, `CROSSES`), after parsing the primary expression, peek for `IS`. If found, consume `IS`, optionally `NOT`, then `NULL`. Emit `IsNull(inner, negated)`.
- Compiler change: one new branch in `ExprCompiler` that evaluates the inner expression and returns `Value.Bool(v is Value.Undefined)`, inverted when negated.
- Semantic note: `IS NULL` always returns `Value.Bool` — never `Value.Undefined`. This makes it the one place in the expression tree where Undefined is a defined value. Downstream `AND`/`OR` short-circuit normally.

**D. `FLATTEN`** — keyword alias for `CLOSE_ALL`.

- New keyword: `FLATTEN`.
- Parser change: in `parseAction`, accept both `CLOSE_ALL` and `FLATTEN`; both emit the existing `CloseAll` data object.
- No AST change. No compiler change. `CLOSE_ALL` remains canonical in docs; `FLATTEN` is the industry-vocabulary alias.

### Out of scope

Deferred to **Phase 25** (operator tooling):

- Historical-data prefetch for DSL strategies. Binding the engine's `WarmupSpec` / `IndicatorWarmer` to compiled DSL strategies belongs with the `qkt fetch` work, because pre-feeding requires historical candles that aren't available until the fetch CLI lands.

Deferred indefinitely:

- Per-rule `WARMUP` qualifiers (`WHEN ... THEN ... WARMUP 50 BARS`). YAGNI; per-stream covers the cases observed in current strategies.
- `WARMUP N <unit>` for non-bar units (seconds, minutes, ticks). The engine `WarmupSpec` supports these; the DSL doesn't need them today.
- A diagnostics surface for non-fatal warnings (e.g., a stream with WARMUP declared that no rule references). Phase 24 silently accepts; if a warning surface becomes worth building, it lands as its own phase.
- Three-valued logic for `AND`/`OR` against `Value.Undefined`. Today, undefined operands propagate as undefined; `IS NULL` is the explicit check. Keeping that semantic — adding three-valued logic would change every existing boolean expression's behavior.

## Approach

### Why surface sugar instead of a new compile path

Three of the four primitives compile to AST nodes that already exist:

| Surface | Compiled to |
| --- | --- |
| `SIZING 0.5 PCT RISK` | `SizeRiskFrac(NumLit(0.005))` |
| `FLATTEN` | `CloseAll` |
| `<expr> IS NULL` | new `IsNull` (one ExprCompiler branch) |

This means three primitives reuse end-to-end the test coverage and runtime paths already shipped in Phase 11d2 and Phase 11c3. The only genuinely new runtime is the `WarmupGate`, which is ~30 lines.

The alternative — separate compile paths per surface form, e.g., a distinct `SizePctRisk` AST node — would double the surface area without any semantic gain. Rejected.

### `WarmupGate` design

```kotlin
class WarmupGate(private val perStream: Map<String, Int>) {
    private val counts = mutableMapOf<String, Int>()
    fun onClosedCandle(alias: String) { counts.merge(alias, 1, Int::plus) }
    fun isWarm(alias: String): Boolean =
        (perStream[alias] ?: 0) <= (counts[alias] ?: 0)
    fun isWarm(aliases: Set<String>): Boolean = aliases.all(::isWarm)
}
```

Held on `DslCompiledStrategy`. Single-threaded, no concurrency primitives needed (Phase 1 invariant: no threads in production code). Each compiled rule already carries the set of stream aliases its expression references — the `MetaRef` pass collects this for snapshot and aggregate plumbing — so the gate consultation is `if (!gate.isWarm(rule.referencedAliases)) return`.

### `IS NULL` precedence

`IS NULL` binds at comparison precedence, between `Crosses`/`Between`/`InList` and the boolean connectives `AND`/`OR`. Concretely:

```
fast IS NOT NULL AND CROSSES(fast, slow) ABOVE
```

parses as `(fast IS NOT NULL) AND (CROSSES(fast, slow) ABOVE)`. The `IS` test binds tighter than `AND`, so no parentheses are needed in the common case.

### Parse-time constant folding for PCT RISK

`SIZING 0.5 PCT RISK` is folded to `SizeRiskFrac(NumLit(0.005))` at parse time rather than carrying a `pct: true` flag through to the compiler. Reasons:

- Compiler stays unchanged. Existing `SizeRiskFrac` test coverage applies as-is.
- Constant folding is safe here — the literal is required to be a numeric constant by the grammar position; no runtime expression can produce a different fraction depending on PCT vs RISK.
- Error messages stay generic ("SIZING by RISK requires a resolvable stop distance via BRACKET STOP LOSS"). Users who wrote `PCT RISK` map "RISK" to what they typed without confusion.

### Why not bind to engine `WarmupSpec` in this phase

The engine has a real warmup machinery — `WarmupSpec.Bars(window, count)`, `Warmable`, `IndicatorWarmer` — that pre-feeds historical candles into a strategy before live evaluation begins. Binding it to compiled DSL strategies would let `WARMUP N BARS` mean "fetch N historical candles, replay them, then go live", matching how hand-written `Warmable` Kotlin strategies behave today.

We're not doing that in Phase 24 because historical prefetch depends on `qkt fetch`, which is Phase 25. Phase 24's `WARMUP` is the live-only flavor: "ignore the first N closed candles on this stream". When Phase 25 lands, `WARMUP` gets upgraded transparently — the DSL surface doesn't change; the runtime starts pre-feeding instead of suppressing. The user-visible behavior (rules don't fire before N bars are in) is identical either way.

## Validation

- `WARMUP 0 BARS` → parse error.
- `WARMUP -1 BARS` → parse error.
- `WARMUP <non-int> BARS` → parse error.
- `SIZING 0 PCT RISK` → parse error.
- `SIZING N PCT RISK` without a resolvable `BRACKET STOP LOSS` → compile-time error.
- A stream with `WARMUP` declared but no rule references it → silently accepted. (See out-of-scope: diagnostics surface.)

## Testing

**Unit tests** (mirroring `src/test/kotlin/com/qkt/dsl/...`):

| Area | Tests |
| --- | --- |
| Lexer | New keywords (`WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL`) tokenize correctly; collision check against existing identifier-heavy fixtures. |
| Parser — SIZING | `0.5 PCT RISK` → `SizeRiskFrac(NumLit(0.005))`; integer percent (`1 PCT RISK`); decimal percent (`0.25 PCT RISK`); `0 PCT RISK` rejected; PCT without trailing RISK falls through to existing `% OF EQUITY/BALANCE` paths unchanged. |
| Parser — WARMUP | `EVERY 5m WARMUP 50 BARS` populates `StreamDecl.warmupBars`; non-integer rejected; `WARMUP 0 BARS` rejected; absent WARMUP leaves field `null`. |
| Parser — IS NULL | Both `IS NULL` and `IS NOT NULL` parse on every primary expression form (Ref, IndicatorCall, StreamFieldRef, AccountRef, Aggregate); precedence binds tighter than `AND`/`OR`. |
| Parser — FLATTEN | `FLATTEN` produces `CloseAll`; `CLOSE_ALL` still works; both forms accepted in the same strategy. |
| Compiler — IS NULL | `EMA(...) IS NULL` returns `Value.Bool(true)` before the EMA is ready; `false` after; `IS NOT NULL` is the inverse; nested in `AND`/`OR` short-circuits correctly; covers every ExprAst form that can yield `Value.Undefined`. |
| Runtime — WarmupGate | Gate starts cold; warms at exactly the Nth closed candle; multi-stream rule waits for all referenced streams; zero-stream rule (`NOW.hour_utc`-only) always warm; counter monotonic within a backtest run. |

**End-to-end fixture** — one `.qkt` test resource using all four primitives wired through `Backtest` + `MockBroker`, asserting trade count differs from the same strategy without WARMUP. Proves the gate actually suppresses firings end-to-end.

## Documentation

- `docs/reference/dsl/sizing.md` — `SIZING N PCT RISK` form.
- `docs/reference/dsl/streams.md` — `WARMUP N BARS` on the SYMBOLS line.
- `docs/reference/dsl/expressions.md` — `IS NULL` / `IS NOT NULL`.
- `docs/reference/dsl/actions.md` — `FLATTEN` as a `CLOSE_ALL` alias.
- `docs/reference/dsl-grammar.md` — grammar updates for all four primitives.
- KDoc on `IsNull` AST node, `WarmupGate` class, and the new `StreamDecl.warmupBars` field — per qkt skill §10.
- `editor/textmate/qkt.tmLanguage.json` — add `WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL` to the keyword scopes shipped two commits ago.
- `docs/phases/phase-24-risk-sizing-primitives.md` — phase changelog with the 7-section structure the qkt skill §6 mandates (summary / what's new / migration / cookbook / testing / limitations / refs).

## Backwards compatibility

No breaking changes. Every existing `.qkt` file parses and compiles identically. The five new keywords (`WARMUP`, `BARS`, `FLATTEN`, `IS`, `NULL`) are not used as identifiers in any current strategy fixture (verified by audit of `src/test/resources/dsl/` and `examples/`).

## Risk

**Low.** All four primitives reduce to small parser additions plus one runtime gate. No engine-architecture change. No new threading or concurrency. No breaking change to existing strategies. The `WarmupGate` is the only stateful new component, and its state is per-tick deterministic.

## References

- Issue: [#52](https://github.com/elitekaycy/qkt/issues/52)
- Backlog: `docs/backlog.md` — Phase 24
- Predecessor: `docs/superpowers/specs/2026-05-11-phase23-dsl-catalog-expansion-design.md`
- Engine warmup machinery: `src/main/kotlin/com/qkt/strategy/WarmupSpec.kt`, `src/main/kotlin/com/qkt/strategy/Warmable.kt`, `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt`
- Existing SIZING compiler: `src/main/kotlin/com/qkt/dsl/compile/SizingCompiler.kt`
- Existing `Value.Undefined`: `src/main/kotlin/com/qkt/dsl/compile/CompiledExpr.kt`
