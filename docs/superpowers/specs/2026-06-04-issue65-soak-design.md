# #65 Soak Tests — Design

**Goal:** Surface slow resource growth in the live engine — the leaks that only show up over a long uptime, which unit and stress tests miss.

**Scope:** Two soak angles, both deterministic where it counts, both tagged `soak` (excluded from default CI; run on demand by an operator). One file: `src/test/kotlin/com/qkt/app/LiveSessionSoakTest.kt`.

---

## Why these two angles

The live daemon runs for days. Two distinct things grow slowly if something is wrong:

1. **Lifecycle resources across strategy churn.** `LiveSession.start()` spawns a `qkt-live-engine` thread and a `qkt-schedule-heartbeat` `ScheduledExecutorService`. `stop()` is supposed to tear both down (`LiveSession.kt:650-657`). The daemon deploys/undeploys strategies over its uptime — if `stop()` ever leaks a thread or executor, a week of churn accumulates hundreds. Thread counts are **exact**, so this is fully deterministic.

2. **Per-tick heap.** The hot path is `feed.next()` → `pipeline.ingest(tick)`. Steady-state structures must stay bounded no matter how many ticks flow: the `CandleHub` ring (`ArrayDeque` trimmed to `slot.retention`), indicators (O(1) state), position/PnL trackers (bounded by open positions). A non-trading strategy reaches true steady state — no trades accumulate — so retained heap should plateau and the candle ring should stay `<= retention`.

`trades` (`LiveSession.kt:483`) grows with trade *count*, not tick count — that's report state, not a per-tick leak, so the heap soak uses a strategy that never trades to isolate the per-tick path.

---

## Test 1 — session churn (threads)

- Baseline: count live threads named `qkt-live-engine*` / `qkt-schedule-heartbeat*` (expect 0).
- Loop N cycles (default 200): build a `LiveSession` over an `InMemoryMarketSource` seeded with one tick + a no-op strategy; `start()`; `awaitTermination` (feed drains, engine thread exits); `stop()` (tears down heartbeat executor + brokers).
- After the loop, wait for the count to settle, then assert it returned to baseline (small slack for threads mid-shutdown).

A regression in `stop()`'s teardown makes the count climb with cycles — caught deterministically.

## Test 2 — sustained ingest (heap + bounded candle ring)

- A `GeneratingLiveSource` (extends `InMemoryMarketSource`): `liveTicks` returns a feed that *generates* `total` deterministic random-walk ticks on demand and retains nothing, exposing a `produced` counter; `seedBars` covers any warmup. This keeps the seed itself from masquerading as heap growth.
- A DSL strategy whose rule never fires (`WHEN x.close > 1e9 …`) — exercises tick→candle aggregation + the hub ring with zero trades.
- Pass an explicit `CandleHub` so the test can read `historySize(key)`.
- `start()`; from the test thread, at tick-count checkpoints (`produced >= k`), `System.gc()` then sample used heap and assert `historySize(key) <= retention` (bounded throughout, not growing).
- After the feed exhausts, assert the heap plateaued: peak post-warmup sample `<= baseline * 2`. Generous because GC sampling is noisy; a real per-tick leak over millions of ticks blows far past 2x.

Default `total` keeps the run to tens of seconds; operators bump it for a true multi-hour soak.

---

## Determinism notes

- Churn test: exact thread counts, no GC dependence.
- Heap test: the **structural** assertion (`historySize <= retention`) is exact; the **plateau** assertion is best-effort with a generous bound. Soak is excluded from CI, so the rare GC-noise miss costs an operator re-run, not a red build.
- No mocks; real `LiveSession`, real pipeline, real `CandleHub`. Walk seeds make failures reproducible.
