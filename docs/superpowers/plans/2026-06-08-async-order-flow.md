# Async order flow — design

**Goal:** Order sends (`submit`/`cancel`/`modify`) must never block the single engine thread on a
network round-trip, so one qkt instance can run ~30 strategies — including many concurrent OCO/OTO
placements at a session open — without a multi-second stall. Backtest==live and hedge-safety
(no one-legged "OCO") are preserved.

**The problem being solved.** Today `MT5Broker.submit` blocks on `client.placeOrder` (OkHttp
`.execute()`) on the engine thread, and `submitOco` places leg1, *blocks* for its venue ack, then
places leg2 — so a session open where many of 30 strategies fire OCOs serializes into 30×2 HTTP
round-trips on the one engine thread (~1.2–3 s of dead time at the worst possible moment). The
engine thread's *in-memory* work for 30 strategies is microseconds; only the blocking I/O makes
30-on-one-thread non-viable.

---

## Core principle: an event-driven order lifecycle

**Drive OCO/OTO sequencing off broker EVENTS (`OrderAccepted`/`OrderRejected`/`OrderFilled`), not the
synchronous `SubmitAck`.** This is the load-bearing decision, because it makes "async" a purely
broker-internal concern and keeps one code path for both modes:

- An **in-memory broker** (PaperBroker, MT5BrokerSimulator) publishes those events **inline**, on the
  calling (engine) thread, during `submit`. So the OCO state machine advances *synchronously* — leg1
  accepted → place leg2 → leg2 accepted — exactly as today. Deterministic. **Backtest and live-paper
  are unchanged**, so parity holds by construction.
- A **real HTTP broker** (MT5, Bybit) returns immediately from `submit` and publishes those events
  **later, from its I/O layer**. The §1 inbound queue already serializes them back onto the engine
  thread, so the OCO state machine advances the same way — just non-blocking.

The OCO/OTO logic is **identical** in both cases; the only difference is whether the events arrive
inline or via the queue. No `mode` flag, no broker-conditional logic in OrderManager → no parity risk.

---

## Broker behavior

**Real HTTP brokers (`MT5Broker`, Bybit):**
- `submit` does the synchronous-and-local part only — translate + `prepareForPlacement` validation.
  A hard *local* rejection still returns `SubmitAck(accepted=false)` synchronously (no network).
- The HTTP send goes async: `MT5Client.placeOrder` switches from OkHttp `.execute()` to `.enqueue()`
  (non-blocking; OkHttp's dispatcher owns the worker threads + a `maxRequestsPerHost` cap). `submit`
  returns an **optimistic `SubmitAck(accepted=true, brokerOrderId=null)`** immediately.
- The OkHttp callback (on a dispatcher thread) publishes the venue result on the bus:
  `OrderAccepted(brokerOrderId)` on success (+ `OrderFilled` for an instant-fill market, as today),
  or `OrderRejected(reason)` on a bad retcode / IO error. The §1 reroute carries it to the engine
  thread.

**In-memory brokers (`PaperBroker`, `MT5BrokerSimulator`):** unchanged — synchronous, publish
`OrderAccepted` (+ fills) **inline**. NOTE: confirm PaperBroker publishes `OrderAccepted` on submit;
the event-driven OCO depends on it (today's OCO read the sync ack, so PaperBroker may need to start
emitting `OrderAccepted` — small, and it only makes the model uniform).

`SubmitAck` keeps its meaning ("accepted for handling; the real result follows on the bus") — only the
real HTTP brokers now return it *before* the round-trip instead of after.

---

## OCO state machine (the one piece with new logic)

Today `submitOco` places both legs synchronously and relies on first-fill-wins (sibling-cancel-on-fill)
plus a sync unwind. The async version sequences leg2 *after leg1 is confirmed*, driven by events:

| state | event | action | next |
|---|---|---|---|
| (submit) | — | place leg1 (async) | `AWAIT_LEG1` |
| `AWAIT_LEG1` | `OrderAccepted(leg1)` | place leg2 (async); record sibling link | `AWAIT_LEG2` |
| `AWAIT_LEG1` | `OrderRejected(leg1)` | nothing to unwind (leg2 never sent) | `DEAD` |
| `AWAIT_LEG2` | `OrderAccepted(leg2)` | arm sibling-cancel-on-fill (existing) | `ACTIVE` |
| `AWAIT_LEG2` | `OrderRejected(leg2)` | cancel leg1 | `DEAD` |
| `ACTIVE`/`AWAIT_LEG2` | `OrderFilled(either)` | cancel the sibling (existing path) | `DEAD` |

**Why this is hedge-safe:** leg2 is sent only once leg1 is *accepted*, so a leg1 rejection means leg2
never goes out — there is no one-legged window (the failure mode plain full-async would have had).

**The one genuinely fiddly edge — deferred cancel.** leg1 is accepted, leg2 is sent, then **leg1 fills
before leg2's `OrderAccepted` arrives**. We must cancel leg2, but its venue ticket isn't known yet. So
record a **pending-cancel** for leg2's client id; when `OrderAccepted(leg2)` lands, immediately cancel
it (or, if `OrderRejected(leg2)` lands, drop the pending-cancel — already gone). This is bounded, local
state on the OCO.

---

## OTO — already event-driven

OTO already places children only on the parent's `OrderFilled` (`pendingChildren` dispatched on the
fill). So nothing changes except the parent's *send* becoming async; its fill (an event) still triggers
the children. No new state machine.

---

## What this does NOT change

- **Local validation / capability rejections** stay synchronous (`SubmitAck(accepted=false)`).
- **Backtest / live-paper** stay synchronous (inline events) → parity preserved; `BacktestLiveParityTest`
  must stay green.
- **The §1 single-consumer model** — all broker events still funnel through the inbound queue onto the
  engine thread; the OCO state machine runs only there (no locks).

---

## Concurrency + scale reality

- OkHttp's dispatcher fans out concurrent sends up to its per-host cap — that's the "small pool" that
  lets 30 strategies' sends issue in parallel instead of serially on the engine thread.
- **The broker account/terminal is itself a serialization point:** one MT5 terminal processes
  `order_send` serially, so async frees *qkt's* engine thread but does not make the *broker* faster —
  sends queue at the gateway at the terminal's rate. For real throughput at 30 strategies, **shard by
  account/venue** (one engine+terminal pair per account); async + event-driven OCO makes 30-on-one-thread
  *correct and non-blocking*, sharding makes it *fast*. Sharding is out of scope for this PR — see
  `2026-06-08-sharding-by-account.md` for that design.

---

## Files

- `MT5Client.kt` — `placeOrder` (+ `cancel`/`modify`) async via OkHttp `.enqueue` with a result
  callback; keep a synchronous variant where needed for non-order calls (`getAccount`, polling).
- `MT5Broker.kt` (+ Bybit) — `submit` returns optimistic ack after the local part; the OkHttp callback
  publishes `OrderAccepted`/`OrderRejected`/`OrderFilled`.
- `OrderManager.kt` — OCO becomes the event-driven state machine above (consume `OrderAccepted`/
  `OrderRejected` to sequence legs + the deferred-cancel); `onRejected` gains OCO/OTO unwind; the sync
  `submitOco` leg-by-leg block is removed.
- `PaperBroker.kt` — ensure `OrderAccepted` is published inline on submit (if not already).
- `Broker.kt` — likely no new method needed (async is internal); add only if a capability check proves necessary.

## Staged plan (each TDD, green before next)

1. **PaperBroker emits `OrderAccepted` inline** (if it doesn't) + a test; confirm existing OCO still
   green driving off the event path with a sync broker.
2. **OCO state machine in OrderManager**, still driven synchronously by a sync broker's inline events
   (no broker change yet) — proves the event-driven sequencing + deferred-cancel against PaperBroker /
   FakeBroker, backtest parity intact.
3. **MT5 async send** — `MT5Client.enqueue` + `MT5Broker` optimistic ack + callback events; integration
   test (MT5 sim / wire test) that a single send doesn't block and the result arrives as an event.
4. **OTO parent send async** (small) + verify children still fire on fill.
5. **Bybit** parity of the same pattern.
6. Full straddle (OCO_ENTRY) e2e on a FakeBroker that delays its accept events, asserting: no
   one-legged position, deferred-cancel fires correctly, first-fill-wins intact.

## Risks / invariants to hold

- **Parity:** PaperBroker stays synchronous; `BacktestLiveParityTest` green at every step.
- **No one-legged OCO:** leg2 only after leg1 accepted; the e2e delayed-accept test is the gate.
- **Deferred-cancel correctness:** leg1-fills-before-leg2-accept must cancel leg2 once its ticket is known.
- **Single-threaded engine:** the OCO state machine runs only on the engine thread (events arrive via the
  §1 queue); no new shared mutable state off-thread.
