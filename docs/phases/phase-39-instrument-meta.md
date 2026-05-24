# Phase 39 — INSTRUMENT_META DSL accessor

## Summary

Phase 39 makes per-instrument metadata reachable from a strategy. `InstrumentMeta`
(Phase 30) already carried `pointSize`, `contractSize`, `volumeStep`, and
`volumeMin` on every registered instrument, but a DSL strategy had no way to
read them. Strategies hard-coded those values per symbol — making the same
strategy file non-portable across instruments.

This phase exposes four new per-stream accessors:

- `<stream>.tick_size` → `InstrumentMeta.pointSize`
- `<stream>.contract_size` → `InstrumentMeta.contractSize`
- `<stream>.volume_step` → `InstrumentMeta.volumeStep`
- `<stream>.volume_min` → `InstrumentMeta.volumeMin`

Validation is loud and early: a strategy that references `gold.tick_size`
against a registry that has no entry for `EXNESS:XAUUSD` fails to bind with a
single pointed error message, instead of silently misbehaving at first eval.

## What's new

- DSL stream fields `<stream>.tick_size`, `.contract_size`, `.volume_step`,
  `.volume_min` — resolve to the registered `InstrumentMeta` field for the
  stream's `qktSymbol`.
- `StreamRef.tick_size` / `.contract_size` / `.volume_step` / `.volume_min` —
  the same accessors on the Kotlin DSL, producing the matching `StreamFieldRef`
  AST nodes.
- `ExprCompiler.CANDLE_FIELDS` and `ExprCompiler.META_FIELDS` — public
  field-name sets on the companion. The two are guarded as disjoint by
  `ExprCompilerFieldSetsTest`, so any future overlap fails CI.
- `CompiledStrategy.bindToHub` now runs a single-pass meta-ref validation
  before subscribing to the candle hub. Any meta reference whose
  `InstrumentRegistry.lookup(qktSymbol)` returns `null` throws with a clear
  diagnostic naming the strategy, the offending `<stream>.<field>` reference,
  and the symbol that needs metadata.

## Migration from previous phase

None. The change is additive:

- Existing strategies that don't touch the new accessors are untouched and
  bind against any registry, including the `NoopInstrumentRegistry` default.
- The two new accessor sets are non-overlapping with the Phase 32 candle
  fields (compile-time guard).
- `ExprCompiler.compileStreamField` was refactored into a `require` + branch +
  two helpers (`compileCandleField`, `compileMetaField`); the candle path is
  unchanged behavior, and the existing `ExprCompilerStreamFieldTest` continues
  to pass without modification.

## Usage cookbook

### Spread gate scaled to tick size

The motivating case — gate the entry on a spread expressed in *tick units*,
not a literal `5.0` that has to change every time you point the strategy at a
different instrument.

```qkt
RULES
    WHEN now.minute_utc = 55
     AND gold.spread > gold.tick_size * 2
    THEN OCO_ENTRY { ... }
```

`gold.tick_size` resolves to `pointSize` from the registered meta — `0.01` for
XAUUSD on Exness, `0.00001` for EURUSD, and so on. The same rule moves
between instruments without an editor pass.

### Portable order sizing

Use the instrument's minimum order size as the floor:

```qkt
RULES
    WHEN gold.close > 0
    THEN BUY gold SIZING gold.volume_min
```

A future addition that bumps `volumeMin` on the venue side (or migrates the
strategy to a different broker with a different floor) needs no DSL change.

### Composing across phases

Combine Phase 32 (`gold.spread`) with Phase 39 (`gold.tick_size`) without
either knowing about the other — both are flat per-stream accessors:

```qkt
RULES
    -- Only enter when the spread is at most one full tick wide.
    WHEN gold.spread <= gold.tick_size
    THEN BUY gold SIZING 0.01
```

### Cross-instrument guard

Different futures contracts have wildly different `contract_size` values
(100 oz for gold, 1000 barrels for crude). A strategy can guard its own
behavior on the contract shape it was deployed against:

```qkt
RULES
    WHEN gold.close > 0 AND gold.contract_size = 100
    THEN BUY gold SIZING 0.10

    WHEN gold.close > 0 AND gold.contract_size = 1
    THEN BUY gold SIZING 10
```

## Testing patterns

Mirror the Phase 32 layout — one test file per surface, fixtures built from
a stub `InstrumentRegistry` and the shared `testStrategyContext` helper.

```kotlin
val gold =
    InstrumentMeta(
        qktSymbol = "EXNESS:XAUUSD",
        contractSize = BigDecimal("100"),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
    )
val reg =
    object : InstrumentRegistry {
        override fun lookup(qktSymbol: String): InstrumentMeta? =
            if (qktSymbol == gold.qktSymbol) gold else null
    }
val ctx = testStrategyContext(instruments = reg)
val v = ExprCompiler().compile(StreamFieldRef("gold", "tick_size")).evaluate(
    EvalContext(
        candle = ...,
        streams = mapOf("gold" to HubKey("EXNESS", "XAUUSD", "1m")),
        lets = emptyMap(),
        strategyContext = ctx,
    ),
)
// v == Value.Num(BigDecimal("0.01"))
```

Canonical test files:

- `ExprCompilerInstrumentMetaTest` — field-by-field resolution, unknown-field
  rejection, arithmetic composition.
- `ExprCompilerFieldSetsTest` — disjointness guard for
  `CANDLE_FIELDS ∩ META_FIELDS`.
- `AstCompilerMetaValidationTest` — bindToHub fails loud on missing meta,
  binds when present, no-ops for strategies with no meta refs.
- `StreamRefTest` — Kotlin DSL emits the right `StreamFieldRef`.

## Known limitations

- **`volumeMax`, `digits`, `tradeStopsLevelPoints` not exposed.** Venue
  plumbing, rarely useful in strategy logic, easy to add later when a real
  strategy needs them.
- **Backtest needs a YAML manifest.** A backtest strategy using meta accessors
  fails to bind against the default `NoopInstrumentRegistry`. The error
  message points at the qkt.config.yaml `instruments:` section. Documented in
  the Phase 30 cross-link.
- **Runtime, not compile-time, fold.** Each access does a `HashMap.get` on
  the registry. Cost is single-digit microseconds per candle and was measured
  to be negligible; a compile-time fold (resolve once, embed as constant) is
  available as a future optimisation if profiling shows it matters.

## References

- Spec: [`docs/superpowers/specs/2026-05-24-phase39-instrument-meta-dsl-design.md`](../superpowers/specs/2026-05-24-phase39-instrument-meta-dsl-design.md)
- Plan: [`docs/superpowers/plans/2026-05-24-phase39-instrument-meta-dsl.md`](../superpowers/plans/2026-05-24-phase39-instrument-meta-dsl.md)
- Predecessor: [Phase 30 — instrument metadata](phase-30-instrument-metadata.md)
- Pattern source: [Phase 32 — bid/ask DSL exposure](phase-32-bid-ask.md)
- Merge commit: _added on merge_
