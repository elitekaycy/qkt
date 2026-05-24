# Phase 38 — GTD time-in-force for pending orders

**Status:** design
**Date:** 2026-05-24

## Phase

Phase 38. Engine + DSL + brokers + persistence — activation of an existing
deferred path.

## Goal

Wire `TIF GTD <expr>` end-to-end so a strategy can declare a pending order
that automatically cancels when its deadline passes. The DSL grammar already
parses `GTD <ExprAst>`; this phase plumbs the deadline through every layer the
order travels and removes the `:10`-sweep-rule workaround hedge-straddle uses
today.

```qkt
THEN BUY gold SIZING 0.10
    TIF GTD NOW + 30m
    -- alternatively a fixed wall-clock: TIF GTD 1700001800000
```

## Background

- `TifAst.Gtd(until: ExprAst)` exists since the original TIF grammar; the
  parser already produces it for `TIF GTD <expr>`.
- `TifTranslator.translate(Gtd)` currently throws with the message
  `"TIF GTD is deferred — engine TimeInForce enum has no GTD variant; revisit
  alongside engine deadline-bearing order surface"`. This phase IS that
  revisit.
- `TimeInForce` enum has `DAY / GTC / IOC / FOK` only — no `GTD` and no
  deadline carrier anywhere on `OrderRequest`.
- MT5 supports a venue-native expiration on pending orders (Unix-seconds
  timestamp in the wire payload). Bybit's public spot/linear endpoints do not
  surface a GTD time-in-force — they accept `GTC / IOC / FOK / PostOnly`.
- PaperBroker has no expiry behavior — it fills synchronously and otherwise
  holds pending orders forever.
- Motivating use case: hedge-straddle GAP 5. The strategy places a pending
  OCO at 19:55 UTC and currently runs a `:10` rule that sweeps stale pendings
  manually. GTD replaces the sweep rule with a venue/engine-managed expiry.

## Non-goals

- `TIF GTD` on Market orders. Market is instant — no expiry semantic.
  Rejected at compile time.
- Reusing `OrderRequest.TimeExit` for GTD. `TimeExit` fires an action
  (`onExpiry`) when its deadline passes; GTD just cancels. Different
  semantics, different downstream paths. They stay separate.
- Native GTD on every broker. MT5 gets the native path; PaperBroker and
  Bybit use the engine-side sweep. A broker can opt in later by flipping
  one capability flag.
- A virtual-time scheduler for sub-tick deadline precision. The engine-side
  sweep runs on the existing `TickEvent` subscription; latency is the tick
  interval (sub-second on live MT5, deterministic in backtest). Anything
  finer is YAGNI.

## Approach

### Surface

```
TIF GTD <expr>
```

`<expr>` evaluates to a numeric **epoch-milliseconds** timestamp. Most
strategies will write it as `NOW + 1h` or `NOW + 30m`, where `NOW` is the
existing `NowAccessor` and `1h`/`30m` parse as duration literals (already
millisecond-valued). Wall-clock literals work too: `TIF GTD 1700001800000`.

The keyword is grammar-existing; no parser change.

### Components

Eight files change. Most adds are small.

**`com.qkt.execution.TimeInForce`** — one new enum value:

```kotlin
/** Good-til-date — stays open until [OrderRequest.expiresAt] timestamp passes. */
GTD,
```

**`com.qkt.execution.OrderRequest`** — add `expiresAt: Long? = null` (epoch
millis) to nine variants:

`Limit`, `Stop`, `StopLimit`, `IfTouched`, `TrailingStop`, `TrailingStopLimit`,
`Bracket`, `StandaloneOCO`, `OTO`, `ScaleOut`.

Skipped variants:

- `Market` — instant fill; `TIF GTD MARKET` is a compile error in
  `ActionCompiler`.
- `TimeExit` — already deadline-bearing via `deadline: Instant` +
  `onExpiry: ExpiryAction`. Different semantic.
- `Stack` — pyramiding container; per-stack-tier sizing already runs at
  fire time, not as a pending order with an expiry.

A new `withExpiresAt(expiresAt: Long?)` extension mirrors the existing
`withStrategyId` shape — composite variants stamp into nested sub-requests
so `Bracket.entry`, `StandaloneOCO.leg1/leg2`, `OTO.parent`, `OTO.children`,
`ScaleOut.basis` inherit the parent's deadline. The pattern is identical
to Phase 39's recursion through composite shapes.

**`com.qkt.dsl.compile.TifTranslator`** — flip the deferred error to the
active mapping:

```kotlin
is Gtd -> TimeInForce.GTD
```

The `until` expression is consumed by `ActionCompiler` (see below); the
translator itself only emits the enum.

**`com.qkt.dsl.compile.ActionCompiler`** — when `opts.tif is Gtd`,
ExprCompiler-compile its `until` expression at strategy-compile time. In
the existing emit lambda (`return { ctx -> ... }`), evaluate the compiled
expression against `ctx` to a `Long`, then stamp `expiresAt = <value>` on
the constructed `OrderRequest`. About ten lines added.

Compile-time guard: if `opts.tif is Gtd` AND `opts.orderType is Market`
(no `orderType` declared and the action would default to a Market submit),
fail compile with a clear message:

```
TIF GTD is only valid on pending order types (LIMIT/STOP/IFTOUCHED/…);
MARKET orders fill instantly and have no expiry semantic.
```

**`com.qkt.broker.Broker`** — one new property on the interface:

```kotlin
/**
 * When true, this broker submits GTD orders with a venue-side expiration and the
 * venue self-cancels at the deadline; the engine's deadline-sweep skips orders
 * routed through it. When false, the engine's [com.qkt.app.OrderManager] cancels
 * the order at the deadline on the next tick.
 *
 * MT5 returns true (the gateway accepts an expiration timestamp). Bybit and
 * PaperBroker return false (default). Other brokers can opt in by flipping the
 * flag and threading the deadline into their submit path.
 */
val supportsNativeGtd: Boolean get() = false
```

**`com.qkt.broker.mt5.MT5Broker`** — overrides `supportsNativeGtd = true`.
**`com.qkt.broker.mt5.MT5OrderTranslator`** — for any wire request whose
source `OrderRequest.expiresAt != null`, populate the wire request's
`expiration: Long` field with `expiresAt / 1000` (milliseconds → seconds,
which is the MT5 gateway's contract). The exact wire field name is checked
in the plan against `MT5OrderRequest.kt`.

**`com.qkt.app.OrderManager`** — augment the existing `evaluateTriggers(tick)`
function (already subscribed to `TickEvent` for trailing-stop updates) with
a GTD sweep:

```kotlin
val now = clock.now()
for (managed in orders.values.toList()) {
    if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
    if (broker.supportsNativeGtd) continue
    val deadline = managed.request.expiresAtOrNull() ?: continue
    if (now > deadline) cancel(managed.id)
}
```

`expiresAtOrNull()` is a one-liner extension over `OrderRequest` that reads
the field from any variant that has it.

Cancellation flows through the existing `OrderManager.cancel` →
`broker.cancel` path, so the resulting `BrokerEvent.OrderCancelled` is
emitted with `reason = "user cancel"` (the existing reason). A focused
follow-up could distinguish `reason = "gtd-expired"` if observability needs
it; that's a one-line cosmetic change and not part of this spec.

**`com.qkt.persistence.FileStatePersistor` (and `OrderRequestDto`)** — add
`expiresAt: Long? = null` to the DTO for each affected variant. Existing
state files (with no field) deserialize cleanly because the field defaults
null. Restart fidelity: the deadline is re-loaded with the pending order,
and the engine-side sweep keeps working from `OrderManager.restore`.

### Data flow

```
DSL:        THEN BUY gold SIZING 0.10 TIF GTD NOW + 30m
Parse:      ActionOpts.tif = Gtd(BinaryOp(ADD, NowAccessor(EPOCH_MS), NumLit(1_800_000)))
Compile:    tif = TimeInForce.GTD
            deadlineExpr = ExprCompiler().compile(gtd.until)
            // guard: if orderType is Market, fail compile
Runtime:    deadline = deadlineExpr.evaluate(ctx).asLong()  // 1_700_001_800_000
            OrderRequest.Limit(..., timeInForce = GTD, expiresAt = deadline, ...)

Submit:     OrderManager.submit → broker.submit
            MT5 path: translator writes wire.expiration = expiresAt / 1000
                      venue self-cancels at deadline → OrderCancelled
            Paper/Bybit path: broker holds the order; deadline is in OrderRequest

Sweep:      OrderManager.evaluateTriggers(tick):
                if !broker.supportsNativeGtd && now > pending.expiresAt:
                    cancel(pending.id)
                    → BrokerEvent.OrderCancelled with reason="user cancel"

Restart:    persistor.loadPendingOrders rehydrates expiresAt with each order.
            OrderManager.restore re-tracks them; next tick's sweep
            cancels any deadline already in the past.
```

### Error handling

Three surfaces, all caught at compile or submit; none silently mis-behave.

| Trigger | Message | Stage |
|---|---|---|
| `TIF GTD` on a Market action | `TIF GTD is only valid on pending order types (LIMIT/STOP/IFTOUCHED/…); MARKET orders fill instantly and have no expiry semantic.` | `ActionCompiler` |
| Deadline expression evaluates to non-numeric | `TIF GTD expression evaluated to ${value}; expected a numeric epoch-millis timestamp` | submit-time, in the emit lambda |
| Deadline already in the past at submit | No special handling — the order is dispatched, then the very next tick's sweep cancels it. MT5 native path may reject a past-dated order; the broker's reject reason surfaces through `OrderRejected`. | tolerated |

### Testing

Six new test files plus light extensions to existing tests. About 25 new
tests total.

**`TifTranslatorTest`** (extend) — one test: `Gtd → TimeInForce.GTD`
(replaces the existing "GTD is deferred" assertion).

**`ActionCompilerGtdTest`** (new, 3 tests):

- `TIF GTD NOW + 1h on a LIMIT compiles to Limit(expiresAt = now+3_600_000)`
- `TIF GTD <constant literal> stamps the literal as expiresAt`
- `TIF GTD on a MARKET action fails compile with the pointed message`

**`OrderRequestWithExpiresAtTest`** (new, 4 tests):

- `withExpiresAt on a Limit stamps the field`
- `withExpiresAt on a Bracket propagates into entry`
- `withExpiresAt on a StandaloneOCO propagates into both legs`
- `withExpiresAt on an OTO propagates into parent and every child`

**`OrderManagerGtdSweepTest`** (new, 4 tests) using a stub Broker:

- `pending with expired deadline is cancelled on the next tick (broker.supportsNativeGtd=false)`
- `pending with future deadline is not cancelled (broker.supportsNativeGtd=false)`
- `pending with expired deadline is NOT touched by the sweep (broker.supportsNativeGtd=true)`
- `order without an expiresAt is never touched by the sweep`

**`MT5OrderTranslatorGtdTest`** (new, 2 tests):

- `OrderRequest with expiresAt = X produces wire payload with expiration = X / 1000`
- `OrderRequest with expiresAt = null produces wire payload with no expiration field`

**`FileStatePersistorGtdTest`** (new, 1 test, parameterised over variants):
round-trip `expiresAt` through save → load for each affected variant.

Regression: every existing test that constructs an `OrderRequest.*` keeps
compiling because `expiresAt` defaults to null.

## Risks

- **Time skew between qkt host and MT5 venue.** MT5 uses the server's clock
  to decide expiration. If qkt's clock is ahead of MT5's by even seconds,
  a `NOW + 5s` deadline might submit but never expire (because MT5 sees the
  deadline as still in the future when its own clock catches up). Phase 30's
  MT5 profile already documents `serverTzOffsetHours` for placement-window
  math; the same offset applies here. Mitigation: document the assumption
  in the changelog. A future enhancement could add a "submission-time" skew
  probe.
- **Compile-time vs evaluation-time NOW.** `NOW` evaluated at strategy-compile
  time is wrong (the strategy compiles once at daemon start, but the rule
  fires every tick). The design captures `until` as a `CompiledExpr` and
  evaluates per emit, so `TIF GTD NOW + 1h` correctly means "an hour from
  *now-at-emit*", not "an hour from strategy-startup". Tested in
  `ActionCompilerGtdTest`.
- **MT5 wire-format drift.** The `expiration` field name is what the
  gateway expects today. If a future gateway version renames it, the
  translator change is one line; the rest of this spec is unaffected.
- **Engine-side sweep vs cancel race.** A native-GTD broker (MT5) might
  emit `OrderCancelled` from the venue side at the exact moment the engine
  would sweep. Mitigation: the sweep is gated on `!broker.supportsNativeGtd`,
  so MT5 orders are skipped. PaperBroker can't have a race (no async venue).
  Bybit is the only broker where a future native-GTD upgrade would matter,
  and flipping the flag is one line.

## Open questions

None for this phase. The design space is bounded by the existing TIF
grammar and the existing `evaluateTriggers` subscription point.

## References

- Issue: <https://github.com/elitekaycy/qkt/issues/50>
- Predecessor: Phase 26-ish TIF grammar (in-tree, no changelog filename).
- Pattern source: [Phase 39 — INSTRUMENT_META DSL accessor spec](2026-05-24-phase39-instrument-meta-dsl-design.md) (composite-stamp recursion).
- Pattern source: [Phase 37 — proportional STACK_AT sizing spec](2026-05-24-phase37-proportional-stack-sizing-design.md) (compile-then-resolve).
