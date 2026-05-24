# Phase 39 — INSTRUMENT_META DSL accessor

**Status:** design
**Date:** 2026-05-24

## Phase

Phase 39. DSL surface over Phase 30 (`InstrumentRegistry` / `InstrumentMeta`).

## Goal

Expose four per-instrument metadata fields to DSL strategies as
`<stream>.<field>` accessors, the same shape Phase 32 used for `bid/ask/spread`:

- `<stream>.tick_size` → `InstrumentMeta.pointSize`
- `<stream>.contract_size` → `InstrumentMeta.contractSize`
- `<stream>.volume_step` → `InstrumentMeta.volumeStep`
- `<stream>.volume_min` → `InstrumentMeta.volumeMin`

A strategy can then introspect the instrument instead of hard-coding tick size
or contract size — making the same strategy file portable across instruments
without per-symbol edits.

## Background

- `InstrumentMeta` (Phase 30) carries 8 fields per instrument: `qktSymbol`,
  `contractSize`, `volumeStep`, `volumeMin`, `volumeMax`, `pointSize`, `digits`,
  `tradeStopsLevelPoints`.
- `InstrumentRegistry` is populated by `MT5InstrumentRegistry` (live, from MT5
  `/symbol_info`) or `YamlInstrumentRegistry` (backtest manifest). The
  `StrategyContext.instruments` field passes the registry through to evaluation.
- Phase 32 established the per-stream accessor pattern (`gold.bid`, `.ask`,
  `.spread`) via the `StreamFieldRef(stream, field)` AST node, with
  `ExprCompiler.compileStreamField` validating the field name set and resolving
  each access against the closed `Candle`.
- Motivating use case: hedge-straddle currently hard-codes XAUUSD-specific
  numbers (tick size = 0.01, contract size = 100). Re-pointing the same
  strategy at XAGUSD or another metal needs an editor pass over those literals.

## Non-goals

- Exposing `volumeMax`, `digits`, `tradeStopsLevelPoints`. They are venue
  plumbing, not strategy logic. Easy to add later if a real strategy needs
  them; harder to remove once shipped.
- Compile-time folding (resolve once, embed as constant). Runtime lookup is a
  single `HashMap.get` on a per-strategy registry — orders of magnitude under
  the candle-close budget. Folding can come later if profiling shows it
  matters.
- A separate `meta.<stream>.<field>` namespace. Adds parser + AST surface for
  no real semantic win — meta names are distinct from candle names, no
  collisions today or visibly imminent.

## Approach

### Surface

Four new identifiers, recognised as `StreamFieldRef.field` strings:

```
tick_size      contract_size      volume_step      volume_min
```

Resolution rule: `<stream>.tick_size` means "the InstrumentMeta of the
instrument bound to <stream>'s `HubKey.qktSymbol`, look up `.pointSize`".

Example uses:

```qkt
RULES
    -- Spread gate scaled to the instrument's tick size, not a fixed literal.
    WHEN gold.spread > gold.tick_size * 2
    THEN LOG "wide spread" actual=gold.spread floor=gold.tick_size

    -- Size guard against the venue's minimum order.
    WHEN gold.close > 0
     AND gold.volume_min <= 0.01
    THEN BUY gold SIZING 0.01
```

### Components

Three source files change. No new AST nodes, no parser rule changes, no
`InstrumentRegistry` API additions.

**`com.qkt.dsl.compile.ExprCompiler` — extend `compileStreamField`.**
Roughly 25 lines added:

```kotlin
private val META_FIELDS = setOf("tick_size", "contract_size", "volume_step", "volume_min")
private val CANDLE_FIELDS = setOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread")

private fun compileStreamField(ref: StreamFieldRef): CompiledExpr {
    require(ref.field in CANDLE_FIELDS || ref.field in META_FIELDS) {
        "Unknown stream field for ${ref.stream}: ${ref.field}"
    }
    if (ref.field in META_FIELDS) return compileMetaField(ref)
    return /* existing candle-field path, unchanged */
}

private fun compileMetaField(ref: StreamFieldRef): CompiledExpr =
    CompiledExpr { ctx ->
        val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
        val meta = ctx.strategyContext.instruments.metaFor(key.qktSymbol)
            ?: error("InstrumentMeta missing for ${key.qktSymbol} (covered by AstCompiler startup validation)")
        val value = when (ref.field) {
            "tick_size" -> meta.pointSize
            "contract_size" -> meta.contractSize
            "volume_step" -> meta.volumeStep
            "volume_min" -> meta.volumeMin
            else -> error("unreachable")
        }
        Value.Num(value)
    }
```

The inner `error(...)` is a defence-in-depth check — the `AstCompiler` startup
pass guarantees the registry is populated by the time the first eval runs.

**`com.qkt.dsl.compile.AstCompiler` — startup validation pass.**
Roughly 15 lines added at the end of `compile(ast)`:

- Walk every `Expr` reachable from each `Rule.when` condition, `Rule.then`
  action arguments (sizing expressions, LOG fields), and any `LET` expression
  bound at strategy level. Collect `StreamFieldRef` nodes whose `field in
  META_FIELDS`.
- Group the collected refs by `ref.stream` alias.
- For each alias, look up its `HubKey.qktSymbol` from the compiled strategy's
  stream map and call `instruments.metaFor(qktSymbol)`.
- If any returns `null`, throw with a single pointed message naming the
  strategy, the offending reference, and the symbol that needs metadata.

Error message shape:

```
Strategy '<name>' references '<alias>.<field>' but no InstrumentMeta is
registered for <qktSymbol>. Populate it via the MT5 broker connection (live)
or a YAML manifest in qkt.config.yaml (backtest).
```

The validation runs after standard `AstCompiler.compile` returns, so a strategy
that doesn't touch any meta field is never affected (regression guard).

**`com.qkt.dsl.kdsl.StreamRef` — Kotlin DSL surface.**
Roughly 10 lines added — mirror Phase 32's `.bid` / `.ask` / `.spread` for the
four new fields. Each returns `StreamFieldRef(alias, "<name>")`.

### Data flow

```
Parse:        gold.tick_size                 -> StreamFieldRef("gold", "tick_size")
AstCompiler:  collect meta refs              -> [("gold", "tick_size")]
              for each alias -> qktSymbol    -> ("gold", "EXNESS:XAUUSD")
              instruments.metaFor(qktSymbol) -> InstrumentMeta(...) | throw
ExprCompiler: compileMetaField                -> CompiledExpr { ctx -> ... }

Eval (per closed candle):
  ctx.streams["gold"]              -> HubKey("EXNESS", "XAUUSD", "1m")
  ctx.strategyContext.instruments.metaFor("EXNESS:XAUUSD")
                                    -> InstrumentMeta (registered at startup)
  .pointSize                        -> BigDecimal("0.01")
  -> Value.Num(0.01)
```

### Error handling

Three error surfaces, all caught at strategy compile or start, never at
runtime, never silent.

| Surface | Trigger | Message | Stage |
|---|---|---|---|
| Unknown meta field | `gold.tick_sze` (typo) | `Unknown stream field for gold: tick_sze` | `compileStreamField` `require(...)` |
| Missing `InstrumentMeta` | Registry has no entry for the stream's `qktSymbol` | (see message above) | `AstCompiler` startup pass |
| Unknown stream alias | `foo.tick_size` where `foo` isn't declared | `Unknown stream alias: foo` | Existing `compileStreamField` runtime check (unchanged from Phase 32) |

No new `try/catch` introduced. The new error paths use `require(...)` and
`error(...)` to fail loud at the right moment.

### Testing

Mirror Phase 32's test layout.

**`ExprCompilerInstrumentMetaTest.kt`** (new, ~80 lines, 6 tests)

- `tick_size resolves to InstrumentMeta.pointSize`
- `contract_size resolves to InstrumentMeta.contractSize`
- `volume_step resolves to InstrumentMeta.volumeStep`
- `volume_min resolves to InstrumentMeta.volumeMin`
- `unknown meta field fails at compile time with the unified error`
- `meta accessor composes with arithmetic (gold.tick_size * 2)`

Fixture: a tiny `MapBackedInstrumentRegistry : InstrumentRegistry` (~6 lines)
seeded with one `InstrumentMeta` for `EXNESS:XAUUSD`. No new test infrastructure.

**`AstCompilerMetaValidationTest.kt`** (new, ~50 lines, 3 tests)

- `strategy compiles when InstrumentMeta is registered for every meta-ref stream`
- `strategy compile fails with pointed message when meta-ref instrument is absent`
- `strategy with no meta refs compiles successfully against an empty registry`

**`StreamRefTest.kt`** (extend) — 4 assertions: each new property produces the
correct `StreamFieldRef` AST.

Total ~13 new tests across three files.

## Risks

- **Namespace collision.** Future per-candle fields (`mid`, `vwap`, ...) and
  future meta fields share the same flat namespace. Mitigation: keep the
  field sets in two named `setOf(...)` constants in `ExprCompiler`, fail at
  compile time on any name in both, and add a `ExprCompilerFieldSetsTest`
  asserting `CANDLE_FIELDS.intersect(META_FIELDS).isEmpty()` so any future
  collision fails CI rather than running silently.
- **Missing meta in backtest.** A strategy upgraded to use meta accessors
  fails to start in backtest if the YAML manifest doesn't cover the instrument.
  Mitigation is operator-side, not engine-side: error message points to
  qkt.config.yaml. Documented in the changelog and the Phase 30 cross-link.

## Open questions

None for this phase. Design space is narrow because Phase 32 already
established the precedent.

## References

- Issue: <https://github.com/elitekaycy/qkt/issues/51>
- Predecessor: [Phase 30 — instrument metadata](../../phases/phase-30-instrument-metadata.md)
- Pattern source: [Phase 32 — bid/ask DSL exposure spec](2026-05-21-phase32-bid-ask-dsl-design.md)
