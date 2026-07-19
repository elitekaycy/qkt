# Stop Ratchets Design

**Issue:** #677

## Goal

Add two deterministic, engine-managed bracket stop policies:

- a stepped stop that consumes ordered MFE milestones once; and
- a time-tightening stop that reduces its distance at fixed engine-clock intervals.

Both policies are monotonic, restart-safe, and use the same order-manager path in
paper/backtest and live execution.

## DSL and AST

The new forms extend `ChildBy`; they do not add a `ChildPriceAst` variant:

```qkt
STOP_LOSS BY 50
  STEP TO BREAKEVEN AFTER MFE >= 30
  STEP TO ENTRY + 40 AFTER MFE >= 70

STOP_LOSS BY 60 TIGHTEN BY 10 EVERY 15m FLOOR 20
```

`ChildBy` gains an optional closed `StopRatchetAst` policy. A stepped policy owns
one or more ordered `(mfeThreshold, profitDistance)` values. `BREAKEVEN`,
`BREAKEVEN + d`, and `ENTRY + d` normalize to a direction-relative
`profitDistance`, where zero is breakeven. A time policy owns its tightening
delta, interval, and minimum distance.

All operands are numeric literals in v1. MFE thresholds are strictly increasing,
distances are positive, step profit distances are non-negative, and the time
floor cannot exceed the initial distance.

## Domain and runtime

`StopLossSpec` gains `SteppedStop` and `TimeTighten`. At bracket fill they become
the matching `OrderRequest` leaf, anchored to the actual fill price and time.
Their initial level is the normal `BY` stop:

- long position / SELL exit: `entry - initialDistance`;
- short position / BUY exit: `entry + initialDistance`.

For a stepped stop, the order manager tracks the favorable extreme and a next-step
cursor. A tick advances only newly crossed steps. Each candidate level is
direction-relative to entry. A candidate that would widen the current stop is
skipped with a warning, but its cursor is still consumed.

For a time-tightening stop, the order manager derives the elapsed interval count
from its injected clock and fill timestamp:

`distance = max(initialDistance - intervals * tightenBy, floorDistance)`.

The cached interval count avoids repeated state work. No wall-clock access is
introduced.

Both policies use the engine-held trigger on every broker. If a live venue also
supports position modification and the owning position ticket is available, the
engine submits an asynchronous SL modification only when the stop level tightens.
The initial attached venue stop remains the offline protection floor.

## Persistence and complexity

The existing trailing-stop journal remains backward compatible. Its entries gain
defaulted `stepIndex`, `elapsedIntervals`, and `stopLevel` fields. Armed-trail
entries continue to decode unchanged.

Stepped evaluation is O(1) per tick when no milestone crosses and proportional
only to the newly crossed steps otherwise; every step is consumed once. Time
tightening is O(1). Live orders remain indexed by symbol, so no global scan is
added to the hot path.

## Verification

Coverage includes parser and compile-time validation, value invariants, milestone
crossing and monotonic skip, time accrual and floor clamping, journal restore,
paper/backtest end-to-end behavior, live position-modify routing, and unchanged
strategy parity.

