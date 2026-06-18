# Allocation-free per-tick trigger pass — design

- Date: 2026-06-18
- Status: approved, pending plan
- Scope: `com.qkt.app.OrderManager`, `com.qkt.app.StackTracker`
- Type: refactor (no behavior change; byte-identical output required)

## Context

`OrderManager.evaluateTriggers(tick)` runs on every market tick. It is the
order-side of the engine hot path: tick arrives, this method updates trailing
stops, sweeps expired GTD orders / time-exits / stacks, and fires any pending
trigger that the tick crossed. It is wired to `TickEvent` at `OrderManager.kt:175`
and has exactly one caller.

A prior performance campaign (PRs #490–#496) profiled the deployed engine with
JFR and shipped a sequence of parity-safe hot-path fixes. The recorded conclusion
was that the remaining engine cost is "genuine work" (BigDecimal decode, BigDecimal
compares, the trigger pass), and that the one unaddressed item was "a marginal
`evaluateTriggers` allocation pass". This spec is that pass.

The method currently allocates short-lived collections on every tick. At live
tick rates these are young-gen objects that die immediately and cost nothing
observable — live latency is dominated by the asynchronous broker round-trip, not
this method. The payoff is **backtest throughput and steady-state GC pressure**:
backtest replays this same code millions of times, and PaperBroker takes the
`!supportsNativeGtd` branch that allocates a list across *all* live orders every
tick. This is a throughput/GC change, not a live-latency change.

## Goal

Eliminate the per-tick collection materializations in `evaluateTriggers` — the
`ArrayList`s produced by `.map` / `.filter` / `.toList` — with byte-identical
output to the current implementation. These are the allocations a JFR profile
surfaces.

### Residual allocation (deliberately kept)

The fill passes still iterate `liveBySymbol[sym]` / `liveOrderIds` (`LinkedHashSet`)
and `timeExits.values` / the stack view (`Map.values`). Each `for`-loop over those
allocates a small iterator. Index loops over the scratch `ArrayList`s do not (the
Kotlin compiler lowers `for (i in list.indices)` to a counting loop, no `IntRange`
object). Removing the residual iterators would require replacing the live-index
data structures with array-backed ones — disproportionate scope for an object far
smaller than the lists we are removing. Out of scope. Net effect: ~6 `ArrayList`s
per tick eliminated, ~3–4 small iterators kept.

## Non-goals

- **No money-math change.** The per-tick BigDecimal arithmetic in `MfeTracker`,
  `EquityTracker`, and `CandleAggregator` stays. BigDecimal is mandated for money
  (drift compounds over a long session). Converting to fixed-point `long` is a
  separate, parity-risky decision and is explicitly out of scope.
- **No de-indirection of `orders[id]`.** Storing `ManagedOrder` references in the
  live index instead of ids was already ruled out: `ManagedOrder` is immutable and
  replaced via `.copy()` on every state change, so stored references would go
  stale. The `orders[id]` HashMap lookup stays.
- **No symbol interning.** Symbol→int was investigated and refuted in a prior
  round (symbols are already stable Strings with cached hashCode). Not revisited.
- **No loop merging.** See the phase-order invariant below.

## Current implementation

`evaluateTriggers` (`OrderManager.kt:1510–1565`) runs these phases in order:

1. **Trailing-HWM update** over this symbol's live orders.
2. **GTD sweep** over *all* live orders — only when `!broker.supportsNativeGtd`.
3. **Time-exit sweep** over `timeExits`.
4. **Stack sweep** over active stacks.
5. **Trigger collect-then-fire** over this symbol's live orders.

Six allocation sites, one per materialization:

| # | Line | Expression | Frequency | Allocates |
|---|------|-----------|-----------|-----------|
| 1 | 1516 | `liveBySymbol[sym]?.map { orders[it] }` | every tick (symbol has live orders) | `ArrayList` + iterator |
| 2 | 1529 | `liveOrderIds.map { orders[it] }` | every tick, only `!supportsNativeGtd` | `ArrayList` + iterator |
| 3 | 1541 | `timeExits.values.filter {}.toList()` | every tick (time-exits present) | `ArrayList` + iterator |
| 4 | 1549 | `stacks.all()` → `active.values.toList()` | every tick (incl. zero stacks) | `ArrayList` (inside `StackTracker`) |
| 5 | 1557 | `symbolLive.filter { PENDING }` | every tick | `ArrayList` + iterator |
| 6 | 1558 | `.filter { triggerHit }` | every tick | `ArrayList` + iterator |

Site 1's `symbolLive` list is materialized once and reused by phases 1 and 5.
Site 4 hides its allocation behind `StackTracker.all()` (`StackTracker.kt:39`),
which returns `active.values.toList()` — a fresh list every call, even with no
active stacks.

## Design

Replace each materialization with a **pre-allocated `ArrayList` field on
`OrderManager`**, `clear()`-ed and refilled each tick. `ArrayList.clear()` retains
backing-array capacity, so after warmup the steady-state per-tick allocation is
zero.

Scratch fields:

- `symbolLiveScratch: ArrayList<ManagedOrder>` — replaces site 1. Filled once by
  iterating `liveBySymbol[sym]` and looking up `orders[id]` (the same single
  lookup pass as today). Reused by the trailing-HWM phase and the trigger phase.
- `gtdSweepScratch: ArrayList<ManagedOrder>` — replaces site 2. Filled only on the
  `!supportsNativeGtd` branch; on MT5 live (`supportsNativeGtd == true`) the branch
  never runs and this list is never touched.
- `expiredExitsScratch: ArrayList<OrderRequest.TimeExit>` — replaces site 3. Filled
  from `timeExits.values`, then the second loop removes + handles.
- `expiredStacksScratch: ArrayList<ActiveStack>` — replaces site 4. Filled from a
  new non-copying view on `StackTracker` (below), then the second loop cancels +
  terminates.
- `triggeredScratch: ArrayList<ManagedOrder>` — replaces sites 5+6. A single append
  loop over `symbolLiveScratch` (`state == PENDING && triggerHit(...)`) replaces the
  two chained `.filter` calls.

### StackTracker change

`StackTracker.all()` copies (`active.values.toList()`) so callers can iterate while
the tracker mutates. Add a non-copying read view:

```kotlin
val activeView: Collection<ActiveStack> get() = active.values
```

`OrderManager` reads `activeView` to *fill* `expiredStacksScratch` (a read-only
pass — no mutation during iteration), then mutates (`cancelStackPending`,
`terminate`) in the second loop over the scratch buffer. This preserves the exact
snapshot-then-mutate safety that `all()`'s `.toList()` provides today. `all()` is
kept unchanged for any external caller that genuinely needs an owned snapshot.

## Invariants (parity-critical)

These must hold or output diverges from the current implementation:

1. **Phase order is unchanged.** Trailing-HWM → GTD sweep → time-exit sweep →
   stack sweep → trigger collect-then-fire. In particular, trigger *collection*
   stays **after** the time-exit and stack sweeps: those sweeps can cancel a
   pending order, and collecting earlier would fire a trigger on an order that was
   just swept terminal. This is why the loops are not merged.
2. **`orders[id]` lookup count per tick is unchanged.** `symbolLiveScratch` is
   retained (rather than re-iterating the id-set in both phases 1 and 5) precisely
   so the symbol's orders are looked up once, not twice — HashMap lookups were the
   dominant cost in the round-4 JFR, so trading a list allocation for extra lookups
   would be a net loss.
3. **Iteration order is unchanged.** `liveOrderIds` / `liveBySymbol` are
   `LinkedHashSet`s; iterating them into a list preserves insertion order, matching
   today's `.map`. Trigger-firing order is therefore identical.
4. **Non-reentrancy is the precondition for shared buffers.** `evaluateTriggers`
   has one caller (the `TickEvent` subscription) and is not reentrant: `TickEvent`
   is feed-sourced only, nothing inside `fireFallbackTrigger` republishes one, and
   the engine is a single-consumer queue. Shared mutable scratch fields are safe
   only while this holds. A future change that publishes `TickEvent` from inside a
   handler would break this assumption and must not share these buffers.

## Verification

1. **Byte-identical backtest.** Run a golden backtest before and after; assert the
   `--json` report is byte-identical. This is the bar every prior hot-path round
   met and is the primary correctness gate.
2. **JFR before/after** on the same `.bin` replay: confirm the `evaluateTriggers`
   `ArrayList`/iterator allocations disappear from the allocation profile and no
   new allocation or CPU regression appears elsewhere. Build from source (the
   `:edge` jlink runtime lacks `jdk.jfr`), pull `dev` first so it reads `.bin` not
   stale CSV, and measure CPU-time / hot-method fraction rather than wall-clock
   (the box is contention-bound).
3. **Unit tests** for OrderManager trigger firing, time-exit expiry, GTD sweep, and
   stack-deadline termination stay green.

## Risks

Low–medium. Contained to two files. The two ways to break parity — a phase-order
mistake or a reentrancy violation — are both pinned by the invariants above and
caught by the byte-identical gate. The StackTracker view addition is additive
(`all()` is untouched), so no external caller changes behavior.

## References

- Hot-path campaign PRs #490–#496 (per-engine SweepReplay fan-out, JFR-profiled
  plumbing fixes, EventBus Class-keyed dispatch).
- Round-4 JFR finding: HashMap lookups ~29% of hot-path CPU; round-5 conclusion
  that `evaluateTriggers` allocation was the one remaining marginal item.
- `OrderManager.evaluateTriggers` (`OrderManager.kt:1510`), `StackTracker.all()`
  (`StackTracker.kt:39`).
