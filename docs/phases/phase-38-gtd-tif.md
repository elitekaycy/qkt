# Phase 38 — GTD time-in-force for pending orders

## Summary

Phase 38 activates `TIF GTD <expr>` end-to-end. The DSL grammar already
parsed it; this phase plumbs the deadline through the engine, brokers, and
persistence so a pending order self-cancels at its deadline — venue-side on
MT5 (native expiration field) and engine-side on PaperBroker / Bybit (a sweep
in `OrderManager.evaluateTriggers` that cancels expired pending orders on
the next tick). The hedge-straddle `:10` sweep-rule workaround the strategy
used to manually cancel stale pending orders can now be removed.

## What's new

- `TimeInForce.GTD` — engine enum value.
- `OrderRequest.expiresAt: Long?` — nullable epoch-millis deadline on nine
  pending variants (`Limit`, `Stop`, `StopLimit`, `IfTouched`, `TrailingStop`,
  `TrailingStopLimit`, `Bracket`, `StandaloneOCO`, `OTO`, `ScaleOut`).
  `Market`, `TimeExit`, and `Stack` have no GTD semantic and inherit the
  default null from the interface accessor.
- `withExpiresAt(Long?)` — extension on `OrderRequest` that stamps the
  deadline and recurses into nested sub-requests for composite shapes,
  mirroring `withStrategyId`.
- `Broker.supportsNativeGtd: Boolean` — capability flag. `MT5Broker`
  returns true; PaperBroker, Bybit, LogBroker, CompositeBroker inherit the
  default false.
- `MT5OrderTranslator` — every pending-shape translation populates the
  wire `expiration` field with `expiresAt / 1000` (milliseconds → Unix
  seconds, the gateway contract).
- `OrderManager.evaluateTriggers` — sweep loop cancels every pending
  (or working) order whose `expiresAt` has passed `clock.now()`, but only
  when `!broker.supportsNativeGtd`.
- `FileStatePersistor` — `OrderRequestDto` carries `expiresAt`; existing
  state files without the field deserialize cleanly because it defaults to
  null. Restart re-loads the deadline and the next tick's sweep keeps
  working without re-scheduling.
- `ActionCompiler` — when `opts.tif is Gtd`, compiles the `until` expression
  via `ExprCompiler` at strategy-compile time and evaluates it per emit
  against the strategy's `EvalContext`. Rejects `TIF GTD` on a MARKET action
  at compile time with a pointed error.

## Migration from previous phase

None. The change is additive:

- `expiresAt` defaults to null on every variant; existing call sites
  compile unchanged.
- `TimeInForce.GTD` is a new enum value; existing strategies and tests
  don't see it unless they opt in.
- `Broker.supportsNativeGtd` has a default getter returning false; existing
  broker implementations don't need overrides unless they want to opt in
  to native GTD.
- Persistor DTOs use a nullable defaulted field; old state files without
  `expiresAt` deserialize to `null`.

## Usage cookbook

### Relative deadline (the motivating case)

Cancel any unfilled pending entry one hour after it's placed:

```qkt
RULES
    WHEN now.minute_utc = 55
    THEN BUY gold
        SIZING 0.1
        ORDER_TYPE = LIMIT AT 4500
        TIF GTD NOW + 3600000
```

`NOW + 3600000` evaluates per emit against the strategy's clock. On live
MT5 the venue self-cancels; on PaperBroker / Bybit `OrderManager`'s
tick-driven sweep cancels at the deadline.

### Absolute deadline

A fixed epoch-millis timestamp — useful when the deadline is a
strategy-wide hard cutoff:

```qkt
THEN BUY gold
    SIZING 0.1
    ORDER_TYPE = LIMIT AT 4500
    TIF GTD 1700001800000
```

### Composite deadline propagates through legs

GTD on a `StandaloneOCO` rides into both legs, so both leg orders carry the
same `expiresAt` field that the broker / sweep enforces independently:

```qkt
THEN OCO_ENTRY {
    LIMIT AT 4500 SIZING 0.1
    STOP AT 4600 SIZING 0.1
} TIF GTD NOW + 1800000
```

`OrderRequest.withExpiresAt` propagates into `leg1` + `leg2` so both legs
self-cancel (on MT5) or get swept (on Paper/Bybit) at the same deadline.

## Testing patterns

The split between compile-side, native-broker-side, and engine-side tests
mirrors the design's three concerns.

```kotlin
// Compile-side
val opts =
    ActionOpts(
        sizing = SizeQty(NumLit(BigDecimal("0.1"))),
        orderType = Limit(NumLit(BigDecimal("1.05"))),
        tif = Gtd(NumLit(BigDecimal("1700001800000"))),
    )
val req =
    (ActionCompiler(ExprCompiler()).compile(Buy("eur", opts))(ctx).single() as Signal.Submit)
        .request as OrderRequest.Limit
assertThat(req.expiresAt).isEqualTo(1700001800000L)
```

Canonical test files:

- `ActionCompilerGtdTest` — compile-time stamping, NOW-relative evaluation,
  Market-action rejection.
- `OrderRequestWithExpiresAtTest` — composite-shape recursion.
- `MT5OrderTranslatorGtdTest` — wire-side milliseconds → seconds conversion.
- `OrderManagerGtdSweepTest` — tick-driven engine cancel for non-native
  brokers; non-touch for native brokers.
- `FileStatePersistorGtdTest` — round-trip with and without `expiresAt`.

## Known limitations

- **Sweep cadence is one tick.** Live MT5 ticks at sub-second cadence so
  expiry precision is fine in practice. Backtest mode's tick cadence is
  whatever the data feed delivers. Microsecond-precise expiry is not in
  scope.
- **MT5 venue clock skew.** A `NOW + Ns` deadline near a small `N`
  (single-digit seconds) can race the MT5 server's view of the deadline.
  The Phase 30 profile already documents `serverTzOffsetHours`; the same
  caution applies here. The engine-side sweep is a safety net only when
  the broker isn't native — for MT5, the venue is authoritative.
- **`TIF GTD` rejected on Market actions** at compile time. Market orders
  fill instantly; an expiry has no semantic. The error names the supported
  shapes (LIMIT / STOP / IFTOUCHED / …).
- **Persistor covers Market / Limit / Stop / IfTouched only.** Other
  variants (`Bracket`, `OCO`, etc.) are persisted via dedicated channels
  (`oco-legs.json`, bracket pairs) — their `expiresAt` rides on the
  decomposed leg requests, which round-trip correctly via the existing
  per-leg persistor path.
- **Bybit maps GTD → "GTC"** at the wire level (Bybit has no GTD-equivalent
  TIF code); the engine-side sweep is what cancels the order at the
  deadline. A future Bybit-native GTD upgrade is a one-line flag flip.

## References

- Spec: [`docs/superpowers/specs/2026-05-24-phase38-gtd-tif-design.md`](../superpowers/specs/2026-05-24-phase38-gtd-tif-design.md)
- Plan: [`docs/superpowers/plans/2026-05-24-phase38-gtd-tif.md`](../superpowers/plans/2026-05-24-phase38-gtd-tif.md)
- Pattern source: [Phase 39 — INSTRUMENT_META DSL accessor](phase-39-instrument-meta.md) (composite-stamp recursion)
- Pattern source: [Phase 37 — proportional STACK_AT sizing](phase-37-proportional-stack-sizing.md) (compile-then-resolve)
- Merge commit: _added on merge_
