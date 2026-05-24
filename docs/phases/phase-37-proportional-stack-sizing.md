# Phase 37 — Proportional STACK_AT sizing

## Summary

Phase 37 relaxes Phase 27's compile-time-constant restriction on `STACK_AT
SIZING` so the expression can reference the parent leg's filled quantity via a
new `ENTRY_QTY` keyword. The lambda is built at compile time and resolved
once at parent-fill time, so the per-tick stack-engine path stays free of
expression evaluation. MFE thresholds and bracket distances remain
compile-time constant — the relaxation is sizing-only.

## What's new

- `ENTRY_QTY` keyword + `EntryQty` `ExprAst` leaf — references the parent
  leg's filled quantity inside a `STACK_AT SIZING` expression.
- `STACK_AT SIZING` accepts `NumLit | EntryQty | arithmetic`
  (`+`, `-`, `*`, `/`, unary `-`). Indicators, stream fields, `NOW.<field>`,
  and other dynamic refs are still rejected with the unified
  "STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only" error.
- `CompiledStackTier.resolveStackQuantity: (BigDecimal) -> BigDecimal` —
  replaces the previous `stackQuantity: BigDecimal` field on the
  compile-time tier.
- `ResolvedStackTier` — post-fill snapshot consumed by `StackEngine`. The
  per-tick path reads `tier.stackQuantity` as a plain `BigDecimal`.
- `StackOrchestrator.onPrimaryFilled` gains a required
  `parentQty: BigDecimal` parameter. The orchestrator resolves each tier
  once and constructs the engine with a `List<ResolvedStackTier>`.

## Migration from previous phase

Direct callers of `CompiledStackTier(..., stackQuantity = X, ...)` must
change to:

```kotlin
CompiledStackTier(
    ...,
    resolveStackQuantity = { _ -> X },
    ...,
)
```

Code that constructs the engine-input shape directly (engine tests,
restart-reconstruction paths) should use `ResolvedStackTier` instead. Phase
29's persistor stores already-resolved `BigDecimal` values, so the
on-disk format is unchanged — a daemon restart replays the resolved values
without re-evaluating the lambda.

Every in-tree call site has been updated in this PR; no external consumers
exist.

## Usage cookbook

### Proportional sizing (the motivating case)

Scale the stack to 30% of the main leg's filled quantity:

```qkt
RULES
    WHEN gold.close > 0
    THEN BUY gold SIZING 0.3
        STACK_AT MFE >= 5 WITHIN 30m
            SIZING 0.3 * ENTRY_QTY
            BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 }
```

If the main leg fills at 0.30 lots, the stack fires at `0.3 * 0.30 = 0.09`
lots. If the main fills at 0.10 lots, the stack fires at 0.03 lots.

### Affine sizing

Half the parent plus a 0.05 lot floor:

```qkt
STACK_AT MFE >= 5 WITHIN 30m
    SIZING 0.5 * ENTRY_QTY + 0.05
    BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 }
```

Parent qty = 0.30 → stack qty = 0.20. Parent qty = 0.10 → stack qty = 0.10.

### Pure literal (regression path)

The Phase 27 literal form keeps working unchanged:

```qkt
STACK_AT MFE >= 5 WITHIN 30m
    SIZING 0.05
    BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 }
```

Compiles to `{ _ -> 0.05 }` — the lambda ignores its argument.

## Testing patterns

Mirror the Phase 39 layout — split compile-side from end-to-end tests.

```kotlin
// Compile-side: validate the lambda with a directly-applied parentQty.
val tier = StackAtCompiler.compile(
    StackAtClause(
        mfeThreshold = NumLit(BigDecimal("5")),
        withinDuration = DurationAst(millis = 30 * 60_000L),
        sizing = SizeQty(BinaryOp(BinOp.MUL, NumLit(BigDecimal("0.3")), EntryQty)),
        bracket = BracketAst(
            stopLoss = ChildBy(NumLit(BigDecimal("2"))),
            takeProfit = ChildBy(NumLit(BigDecimal("4"))),
        ),
    ),
)
assertThat(tier.resolveStackQuantity(BigDecimal("0.40")))
    .isEqualByComparingTo(BigDecimal("0.12"))
```

Canonical test files:

- `StackAtCompilerEntryQtyTest` — field-by-field lambda resolution, unknown
  field rejection, arithmetic composition, literal regression.
- `StackOrchestratorEntryQtyTest` — orchestrator-level integration:
  proportional clause + parent fill + tick → stack signal with the right qty.
- `ExprCompilerEntryQtyTest` — `EntryQty` outside `STACK_AT SIZING` is
  rejected by `ExprCompiler` with a pointed message.
- `ParserStackAtTest` — `SIZING ENTRY_QTY * 0.3` parses to the right AST.

## Known limitations

- **Sizing only.** `ENTRY_QTY` is rejected by `evalConstant` in `MFE >=`
  thresholds and bracket SL/TP distances. Each of those fields would need
  its own fire-time-resolution path; defer until a strategy needs them.
- **Non-`SizeQty` variants still rejected.** `STACK_AT` continues to
  reject `RISK $`, `% OF EQUITY`, `% OF BALANCE`, `POSITION.<alias>`,
  etc. Phase 27's constraint stays.
- **No indicators / stream fields / `NOW`.** Sizing accepts only literals
  + `ENTRY_QTY` + arithmetic. The rejection message names the supported set
  so the operator sees what they actually have.
- **No `ENTRY_QTY` in `WHEN`/`THEN` outside STACK_AT.** `ExprCompiler`
  rejects it with `"ENTRY_QTY is only valid inside STACK_AT SIZING"`.

## References

- Spec: [`docs/superpowers/specs/2026-05-24-phase37-proportional-stack-sizing-design.md`](../superpowers/specs/2026-05-24-phase37-proportional-stack-sizing-design.md)
- Plan: [`docs/superpowers/plans/2026-05-24-phase37-proportional-stack-sizing.md`](../superpowers/plans/2026-05-24-phase37-proportional-stack-sizing.md)
- Predecessor: [Phase 27 — conditional bracketed stacks](phase-27-conditional-bracketed-stacks.md)
- Pattern source: [Phase 39 — INSTRUMENT_META DSL accessor](phase-39-instrument-meta.md)
- Merge commit: _added on merge_
