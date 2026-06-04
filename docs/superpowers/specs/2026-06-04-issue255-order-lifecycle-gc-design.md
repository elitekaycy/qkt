# #255 — Order lifecycle GC + live index — Design

**Goal:** Bound `OrderManager`'s per-tick work and retained memory so a long-running daemon doesn't slow down or leak as it trades, and so a backtest stays linear in tick count.

---

## The problem in plain terms

`OrderManager` keeps every order it has ever created in one map (`orders`). On every price tick, `evaluateTriggers` walks that whole map three times to find orders to update or fire. Finished orders (FILLED / CANCELLED / REJECTED) are never removed, so the map only grows — and the per-tick walk grows with it.

- **Speed:** per-tick cost is O(all orders ever), not O(orders that are live). Over a long live session it degrades tick latency; in a frequently-trading backtest it goes quadratic (this is why `BacktestThroughputStressTest` never completes — a thread dump pins the engine in `evaluateTriggers`).
- **Memory:** the `orders` map and a family of order-id-keyed satellite maps (`trailingHwm`, `armedTrailArmed`) accumulate one dead entry per order forever. (`riskByClientOrderId` is the exception — it's already consumed on fill.)

A finished order is **not** safe to drop the instant it finishes: it can still be referenced for a short while by bookkeeping that was paired with it — an unresolved OCO sibling, a live stack, or a pending timed-exit pointing at the order that opened the position. After that bookkeeping resolves, the finished order is dead weight.

---

## The solution: two complementary parts

Think of it as a small garbage collector for orders, plus a fast-path index. They do two different jobs and are deliberately kept separate.

### 1. Live index (the speed fix)

A `LinkedHashSet<String>` of **non-terminal order ids**, maintained beside `orders`. `evaluateTriggers` iterates `liveOrderIds.mapNotNull { orders[it] }` and keeps its existing inner filters, instead of scanning `orders.values`.

- Per-tick cost becomes O(orders live right now) — bounded, and independent of how many finished orders are lingering or when the GC last ran.
- **Determinism:** `orders` is a `LinkedHashMap` (insertion-ordered). A `LinkedHashSet` populated in the same `track()` order yields identical iteration order, so trigger-firing order is byte-identical to today.

### 2. Order GC (the memory fix)

When an order becomes terminal, its id is dropped into a **to-clean queue**. The GC drains that queue and, for each id, removes the order from `orders` (and its satellite-map entries) **only if nothing still references it**. Still-referenced ids stay queued and are re-checked later.

- GC work is O(orders that recently finished), never O(all orders) — it never re-scans the full history.
- **Reachability rule — an order id is kept iff it is non-terminal OR still referenced by an active structure:** `siblings` (OCO, as key or value), `pendingChildren` (parent key), `scaleOutLegs` (key), `timeExits` (as a `target.id`), or `stacks` (stack / layer / parent ids). The plan must audit this list against the code so the predicate is exhaustive — a missing structure is the one way to free something still in use.
- When an order is reclaimed, the GC also evicts its entries from the id-keyed satellite maps (`trailingHwm`, `armedTrailArmed`) so those stop leaking too.
- **Cadence:** drain the queue at the end of each `evaluateTriggers`. The queue holds only finished-not-yet-reclaimed orders, so this is cheap; a long-lived reference (e.g. a 5pm timed-exit holding a 9am fill) simply keeps one id queued until it clears. Per-tick GC keeps the map continuously small without a separate timer.

### Why both, not one

- GC only ⇒ between drains, finished orders pile up; if the per-tick check read `orders` it would sag until the next drain. The live index removes that coupling — tick speed never depends on GC timing.
- Index only ⇒ memory still grows.

Together: per-tick is O(live), memory is reclaimed to O(live + still-referenced-finished), and the two concerns stay independent.

### The free property

The GC only ever deletes orders that are **finished and unreferenced** — nothing reads them — so removal **cannot change any trading decision**. Backtests stay byte-for-byte reproducible regardless of GC cadence. Memory-only effect, zero behavioural effect.

---

## Where the index/queue are maintained

`orders` is mutated at four sites; index/queue upkeep goes through one private `index(managed)` / `enqueueForGc(id)` pair so it can't desync:

- `track()` — insert: add to index if non-terminal.
- `update()` — the single state-transition funnel: if the new state is terminal, drop from index and enqueue for GC; else ensure indexed.
- OCO-restore insert — add to index if non-terminal.
- OTO-expansion remove — drop from index (already removed from `orders`).

---

## Testing

- **Bounded per-tick visitation:** place N orders, fill/cancel most, drive ticks; assert the trigger scan visits only the live ones (instrument a visit counter, or assert the live index size stays O(open orders) while `orders` shrinks via GC).
- **Memory reclamation:** after orders finish and their bookkeeping resolves, assert `orders` (and `trailingHwm` / `armedTrailArmed`) shrink back — terminal-and-unreferenced orders are gone.
- **Reachability safety (the critical cases):** an OCO sibling that filled, a live stack's filled layer, and a pending timed-exit's filled target are each **retained** until their owner resolves, then reclaimed. Never reclaimed early.
- **Determinism parity:** the same tick stream produces an identical trade sequence and identical equity curve before vs after — proving the change is speed/memory-only.
- **End-to-end:** `BacktestThroughputStressTest` completes and meets its throughput floor. Greening it also requires its separate tick-generation fix (the price walk uses BigDecimal whose scale grows per tick → switch to a `Double` walk); fold that into this change.

---

## Risk & the one invariant

The whole safety of the change rests on a single invariant: **the GC removes an order only when it is terminal AND unreferenced by every active structure.** That predicate is the one place to get right and the one place to test hardest. Everything else (the index, the queue, the satellite-map eviction) is mechanical bookkeeping that cannot change behaviour.

Scope: this fixes speed and memory inside `OrderManager`. It does not change order semantics or persistence formats. `activeOrders` and `pendingOrders` are unaffected (they already return only non-terminal orders, all of which stay indexed). The one deliberate behavioural change: `getOrder(id)` on a **long-finished, already-reclaimed** order now returns null instead of the lingering record. That is the reclamation working as intended — nothing in the engine resurrects a dead-and-unreferenced order (the post-reject alert reads its order synchronously, before any GC pass; `recentTrades` and `qkt observe` reconstruct from the trades list and logs, not from `orders`). The plan must confirm no caller depends on looking up an order long after it finished.
