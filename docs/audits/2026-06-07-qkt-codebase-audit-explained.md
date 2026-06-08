# qkt Codebase Audit — Explained (2026-06-07)

> Companion to `2026-06-07-qkt-codebase-audit.md` (the ranked catalog). Each confirmed finding is
> explained in plain terms with a real before/after example and the fix. Produced by parallel readers
> over the actual source; a few finder claims were corrected during verification (noted inline — e.g.
> `lastObservedPrice` is **not** dead; the unrealized-PnL formula is duplicated 2×, not 3×).
>
> Reading order: **§1 thread-safety** is the dominant production blocker (7 findings → one structural
> fix). **§2** wire & restart correctness — includes the `brokerTicket` gap that silently undoes the
> v0.30.1 close-by-ticket fix on the next restart. **§3** DSL/determinism. **§4** performance (mostly
> allocation churn, two real algorithmic items). **§5** dead code, duplication, observability, standards.

---

## 1. Live-path thread safety (the dominant issue)

qkt's core invariant is that the EventBus and OrderManager run on exactly one thread. The `EventBus` KDoc says so explicitly: *"Subscribers are invoked synchronously in registration order on the publishing thread; the bus is intentionally not thread-safe because the engine is single-threaded by design."* `publish` just walks a plain map and calls each handler inline:

```kotlin
fun publish(event: Event) {
    val stamped = stamp(event)
    subscribers[stamped::class]?.forEach { it(stamped) }   // runs on the caller's thread
}
```

That is sound in backtest, where one replay loop drives everything. The problem: **live mode has ≥4 threads that all call into this single-threaded machinery** — the `qkt-live-engine` tick loop, a `qkt-schedule-heartbeat` timer, broker poller threads (`qkt-mt5-poller-*`, Bybit's reconciler), and broker WebSocket reader threads. Because subscribers run *on the publishing thread*, whichever thread calls `publish` is the thread that mutates the OrderManager maps and the position tracker. No lock anywhere. The six findings below are this one structural mismatch seen from six call sites.

### CRITICAL — Heartbeat timer drives the bus concurrently with the engine — `LiveSession.kt:614-624`

**What it is:** Live mode spins up a second thread — a `ScheduledExecutorService` named `qkt-schedule-heartbeat` — firing once/sec so time-based `SCHEDULE` rules fire even on a quiet market. Its callback runs `pipeline.scheduleHeartbeat(now)` → `scheduleRunner.tick(now)` → a schedule's `emit` → `bus.publish` / `orderManager.submit`. Meanwhile the engine thread does the same off every tick.

**Why it matters:** A schedule firing from the heartbeat thread and a tick on the engine thread both run `EventBus.publish` (over a plain `MutableList`) and `OrderManager.track`/`update` (`orders[id]=…` on a plain `mutableMapOf`). Concurrent structural mutation of a `LinkedHashMap`/`LinkedHashSet` from two threads can throw CME, corrupt the bucket structure (lost/duplicated entries), or interleave a half-built `ManagedOrder` — on a real account. Backtest never starts this thread (parity break: a concurrency bug that only exists live).

**Fix:** the heartbeat timer should post a "heartbeat" message onto the engine's single inbound queue (see *The one structural fix*); `tick()` then runs only on `qkt-live-engine`.

### HIGH — Unsynchronized OrderManager index from three threads — `OrderManager.kt:62-100`, `:1315-1367`

**What it is:** State lives in plain collections (`orders`, `liveOrderIds: LinkedHashSet`, `gcQueue`, `siblings`); the class KDoc itself says `not thread-safe`. In live mode they're written from the engine thread, the poller threads (a synthesized `OrderFilled` runs `onFilled`→`update`→`indexLive`→`gcQueue.addLast`), and the heartbeat. The per-tick scan trusts the invariant: `liveOrderIds.map { orders[it] ?: error("live order index desync: $it") }`.

**Why it matters:** if a poller fill flips an order terminal and `reclaim` removes it from `orders` *after* the engine read its id from `liveOrderIds` but *before* dereferencing `orders[it]`, the `error(...)` fires and **kills the tick loop live**. Concurrent `LinkedHashSet`/`LinkedHashMap` mutation with no barrier can also drop a live pending stop (its trigger never fires) or leave a terminal order re-evaluated forever.

**Fix:** funnel poller publishes through the single-consumer queue drained on the engine thread; the plain collections become correct with zero locking.

### HIGH — Non-atomic read-modify-write in position averaging/flip — `StrategyPositionTracker.kt:339-413`, `:275-311`

**What it is:** `apply()` updates a position by `primary() → close() → add()` — a 3-step RMW with no lock. The backing `byStrategy` is a `ConcurrentHashMap`, but that only makes single `get`/`put` safe, not the sequence. `applyFill` runs synchronously from `bus.publish(OrderFilled)` — so on the engine thread for an engine exit, or a **poller thread** for a venue-detected fill.

**Why it matters:** two fills for the same `(strategy,symbol)` — one engine, one poller — can run `apply()` concurrently. Both read the same `primary`; one's write is silently discarded (wrong qty), or in the opposite-direction branch **both realize the same closed quantity into PnL twice** — a phantom number that never reconciles against the broker.

```kotlin
// opposite-direction branch: realized computed from `primary`, then close, then add — not atomic
val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)
book.close(primary.legId)            // thread B may have already replaced this leg
book.add(/* reduced/flipped remainder */)
return realized                      // two threads can both return it
```

**Fix:** route all fills (engine + poller) through the inbound queue so `apply` only runs on `qkt-live-engine`. The RMW is then naturally serialized.

### HIGH — Bybit WS/reconciler/reconnect threads publish into the bus — `BybitLinearBroker.kt:313` (+ `BybitSpotBroker.kt:289`)

**What it is:** WS frame handlers and a periodic reconciler publish straight onto the bus from the transport's reader thread:

```kotlin
private fun onExecutionFrame(frame: JsonObject) {
    ...
    if (!seenExecIds.add(exec.execId)) continue
    bus.publish(BrokerEvent.OrderFilled(...))   // on WS reader thread, not qkt-live-engine
}
```

**Why it matters:** a fill over the socket runs `OrderManager.onFilled` + `StrategyPositionTracker.applyFill` + risk/notify subscribers **on the WS thread**, simultaneously with the engine thread on the same subscriber lists and order maps — re-triggering findings 2 and 3 from yet another thread. (`seenExecIds` dedups the *same* exec, not concurrent processing of *different* ones.)

**Fix:** WS handlers translate the frame to a `BrokerEvent` and post it to the inbound queue; the engine drains and publishes.

### HIGH — `flatten()` mutates OrderManager from an HTTP worker thread — `LiveSession.kt:703-737`

**What it is:** `flatten()` (admin/HTTP control surface, runs on an HTTP worker thread) cancels pendings and publishes market closes:

```kotlin
override fun flatten() {
    for ((symbol, pos) in positions.allPositions()) {
        if (pos.quantity.signum() == 0) continue
        pipeline.orderManager.cancelPendingForSymbol(symbol)   // mutates OrderManager maps
        bus.publish(OrderEvent(/* market close */))            // re-enters single-threaded bus
    }
}
```

**Why it matters:** same non-thread-safe mutation as finding 2, now reachable by an operator clicking "flatten" at an arbitrary instant — the most dangerous possible moment for a corrupted order book, because they're trying to *reduce* risk. (`halt`/`resume` only flip an `AtomicBoolean`, so those are safe; `flatten` is the dangerous one.)

**Fix:** `flatten()` posts a "flatten" command to the queue and returns; the engine performs cancels/closes serially.

### HIGH — `ScheduleRunner` registrations mutated from two threads → double-emit — `ScheduleRunner.kt:52`, `:125-145`

**What it is:** triggers live in a plain list; each registration's watermark is advanced in place with no sync. In live, `tick` is called from **two** threads (engine + heartbeat). `reg.nextFireMs` is the *only* dedup against double-firing.

```kotlin
while (reg.nextFireMs <= nowMs) { ...; reg.nextFireMs = next; fired = true }   // no sync
if (fired) reg.emit()   // emit builds an order with a FRESH id — no downstream idempotency
```

**Why it matters:** if both threads observe `nowMs >= reg.nextFireMs` before either advances it, both call `emit()` → a single `SCHEDULE AT 09:00 THEN BUY` places the order **twice**, doubling size on a real account. Lost in-place writes and `regs` iteration vs `unregister` can also CME.

**Fix:** only `qkt-live-engine` calls `scheduleRunner.tick`; the heartbeat posts to the queue. Single caller restores the watermark dedup. (Headline "CME" claim in the raw finding was a misread — the real bug is the double-emit.)

### The one structural fix

These are not six bugs — they're one, from six call sites: a thread that isn't `qkt-live-engine` calls `bus.publish` or mutates order/position state directly, racing the engine on collections built non-thread-safe *because* "the engine is single-threaded." Make that true again with a **single-consumer inbound queue drained on `qkt-live-engine`**:

```kotlin
sealed interface Inbound {
    data class Tick(val tick: com.qkt.common.Tick) : Inbound
    data class Broker(val event: BrokerEvent) : Inbound   // MT5 poller + Bybit WS/reconciler
    data class Heartbeat(val nowMs: Long) : Inbound        // qkt-schedule-heartbeat timer
    object Flatten : Inbound                               // HTTP worker
}
private val inbound = java.util.concurrent.LinkedBlockingQueue<Inbound>()

// qkt-live-engine — the ONLY thread touching bus / OrderManager / ScheduleRunner / positions:
while (running.get()) when (val msg = inbound.take()) {
    is Inbound.Tick      -> pipeline.ingest(msg.tick)
    is Inbound.Broker    -> bus.publish(msg.event)        // poller/WS fills, serialized
    is Inbound.Heartbeat -> pipeline.scheduleHeartbeat(msg.nowMs)
    Inbound.Flatten      -> doFlatten()
}
```

Off-thread sites become one-liners (`inbound.put(...)`). Every finding's precondition — *two threads in the same mutator* — can no longer occur, with no per-collection locks. It preserves backtest==live because backtest already runs one thread draining in order; the queue makes live behave identically.

---

## 2. Broker wire & restart-recovery correctness

Two facts to keep in mind: (a) **hedging account, close-by-ticket** — qkt doesn't net; to flatten a position you tell MT5 "close ticket N"; lose the ticket and a close degrades into an opposite order that opens a *counter*-position (the hedge-accumulation bug). (b) **`supportsNativeGtd` suppresses the engine's expiry sweep** — only safe if the deadline actually reached the venue.

### CRITICAL — GTD pending orders placed GTC-forever — `MT5Client.kt:323-347` (`encodeOrder`)

**What it is:** the translator puts the deadline on every pending request (`expiration = req.expiresAt?.let { it/1000 }`), but `encodeOrder` — which serializes the request to the gateway JSON — never writes `expiration`/`typeTime`. The deadline is computed, carried, then dropped at the wire boundary. The *modification* path proves the field is understood:

```kotlin
// encodeModification — CORRECT:
if (m.expiration != null) fields += "\"expiration\":${m.expiration}"
// encodeOrder — field simply absent (… deviation, magic, comment, then stops)
```

**Why it matters:** MT5 defaults a no-expiry pending to GTC, so a 5-minute `BUY_STOP` rests forever — and `supportsNativeGtd=true` tells the engine to *skip* its own sweep, so neither side cancels it. It fills hours/days later on a stale level. On a stop-entry straddle that's a phantom fill with a live bracket attached.

**Fix:** in `encodeOrder`, emit `expiration` + `type_time=SPECIFIED` (mirroring `encodeModification`); add a wire-JSON test. Until then `supportsNativeGtd` is a false claim — arguably flip it to `false` so the engine sweep resumes.

### CRITICAL — CompositeBroker silently no-ops every stateful capability — `CompositeBroker.kt:21-94`

**What it is:** `Broker` gives no-op defaults for `getOpenPositions` (empty), `recoverPendingOrders` (nothing), `modifyPosition` (accepted-but-nothing), `supportsNativeGtd` (false). MT5 overrides all four. `CompositeBroker` overrides only `submit`/`cancel`/`modify`/`capabilitiesFor`/`shutdown` — **not** the four stateful ones — so behind the multi-venue router they hit the interface no-ops and the real MT5 impl is never reached.

**Why it matters, each fall-through:** reconciliation sees no positions (live positions invisible); OCO/bracket restart recovery dead (defeats a8523df); a trailing stop's tightened level never mirrored to the venue (offline = stale/zero stop). Nothing throws — the composite returns "success" while doing nothing, silent on every multi-venue deploy.

**Fix:** delegate each to the matching leaf — `getOpenPositions` merges leaf maps; `recoverPendingOrders` groups by symbol's leaf; `modifyPosition(ticket,…)` routes by the position's symbol; `supportsNativeGtd` → `false` (or per-symbol).

### HIGH — `brokerTicket` lost across restart → close becomes a counter — `PositionLeg.kt:54` + `PersistedLeg`/`FileStatePersistor`

**What it is:** `PositionLeg.brokerTicket` (the venue ticket from the opening fill) is the basis of close-by-ticket, but the persisted `PersistedLeg` has no such field — `fromPositionLeg` drops it, `toPositionLeg` rebuilds without it. After a restart it's `null`.

**Why it matters:** a venue close arrives under the entry id, so `applyFill` falls to `closeLegByTicket`, which matches by `it.brokerTicket == ticket`:

```kotlin
val leg = book.all().firstOrNull { it.brokerTicket == ticket } ?: return null  // null after restart → no match
```

After restart every leg has `brokerTicket == null` → no match → fall through to netting `apply()` → on a hedging account a subsequent flatten opens a counter. **This silently undoes the v0.30.1 close-by-ticket fix on the first restart.**

**Fix:** add `val brokerTicket: String?` to `PersistedLeg`, copy it in `fromPositionLeg`/`toPositionLeg`, serialize it. (Reconciliation can re-attach from `getOpenPositions()` as a backstop, but persistence shouldn't throw it away.)

### HIGH — A primary-less book is dropped on restart → unmanaged exposure — `LiveSession.kt:204-211` + `StrategyPositionTracker.kt:42-51`

**What it is:** `preloadFromPersistor` restores the whole book, but it's only *called* inside a guard requiring a PRIMARY leg:

```kotlin
for (leg in outcome.legBook.all()) {
    if (leg.role == LegRole.PRIMARY) strategyPositions.preloadFromPersistor(strategyId, symbol)
}
```

If the persisted book has no PRIMARY (primary already closed, surviving STACK/INDEPENDENT legs), the `if` is never true and the surviving legs vanish from the tracker.

**Why it matters:** those legs are real open venue positions — after restart the engine has no record: no MFE, no close-by-ticket, no exit management. Live exposure qkt won't flatten or account for in risk.

**Fix:** drop the per-leg PRIMARY guard; call `preloadFromPersistor` once per Attached outcome (it loads the whole book, idempotently).

### MEDIUM — Non-exhaustive `when` drops three pending order types — `FileStatePersistor.kt:416,485` (`fromDomain`)

**What it is:** `fromDomain` handles Market/Limit/Stop/IfTouched/ArmedTrailingStop then `else -> null`. The comment lists composite shapes (Bracket/ScaleOut/TimeExit/Stack) — but `StopLimit`/`TrailingStop`/`TrailingStopLimit` are real single-leg working orders and fall into the same `else -> null`, dropped with no log.

**Why it matters:** such an order resting at restart disappears from recovered state, with no signal — a returned `null` is indistinguishable from "intentionally not persisted."

**Fix:** add explicit branches (+ symmetric `toDomain`); if a shape is deliberately non-persistable, `log.warn` the dropped type so the gap is observable.

### MEDIUM — `persistAll` mislabels OCO/stack exit pairs as BracketPairs — `OrderManager.kt:1163-1181`

**What it is:** `persistAll` treats *every* `siblings` entry as a bracket entry and picks the SL by an `it.contains("-sl")` substring. But `siblings` is bidirectional — stack/OCO exit pairs store both directions — so SL keys, TP keys, and standalone-OCO fill keys all get force-fit into `BracketPair`s.

```kotlin
for ((entryId, siblingIds) in siblings) {              // iterates SL/TP/OCO keys, not just entries
    val sl = siblingIds.firstOrNull { it.contains("-sl") || orders[it]?.request is OrderRequest.Stop }
    val tp = siblingIds.firstOrNull { it != sl }       // "everything that isn't the SL is the TP"
    ...BracketPair(entryClientOrderId = entryId, ...)
}
```

**Why it matters:** corrupt recovery state — a stop-loss recorded as an entry, OCO peers mislinked — producing wrong sibling-cancel wiring on restart (a leg cancels the wrong peer).

**Fix:** only emit a BracketPair when iterating the actual bracket *entry* id (track entries explicitly, or skip synthetic `-sl`/`-tp`/`-oco` ids). OCO exit pairs already have their own recovery channel.

### MEDIUM — `recentlyFilledTickets` grows unbounded without `/orders` polling — `MT5Broker.kt:734,793-817`

**What it is:** every opened position writes `recentlyFilledTickets[ticket]`; the only reaper is `onPendingDisappeared` (fired when a ticket leaves `/orders`). A market order never passes through `/orders`, and on a gateway without `/orders` polling nothing reaps — keyed by monotonic ticket, it only grows.

**Fix:** make eviction time-driven — reap `now - value >= ttlMs` at the top of every position-poller tick (which always runs), or use a TTL/size-bounded structure.

### MEDIUM — Cancelled OCO sibling's intent leaks every bracket round-trip — `StrategyPositionTracker.kt:79-163`

**What it is:** `pendingStackOpens/Closes/IndependentOpens` are pre-registered intents drained only by a *matching fill*. A bracket exit registers BOTH `-tp` and `-sl` as close intents, but it's an OCO — one fills, the other is **cancelled**, and `OrderCancelled` clears nothing here. So one stale close intent leaks per bracket round-trip.

**Why it matters:** slow unbounded growth, plus a trap — if a future order ever resolves to the stale id, `applyFill` matches it and closes the wrong leg by id.

**Fix:** evict sibling intents on `OrderCancelled`/`OrderRejected` (subscribe the tracker, or have OrderManager notify it).

---

## 3. DSL correctness & determinism

### HIGH — FOR EACH drops STACK / STACK_AT on every expanded leg — `IterVarSubstitution.kt:117-128`

**What it is:** FOR EACH clones the rule body per stream, rebuilding `ActionOpts` via an explicit constructor that lists only 5 of 7 fields — `stack` and `stackAts` are omitted and silently default to "no stack" (defaults make it not a compile error):

```kotlin
ActionOpts(sizing = …, orderType = …, tif = …, bracket = …, oco = …)
// stack / stackAts never named → null / emptyList()
```

**Why it matters:** `FOR EACH s IN gold, silver { … BUY s … STACK {…} }` compiles and runs with the STACK clause **dropped from every leg** — no error. The author thinks they have pyramiding across N symbols; they have N plain entries. Backtest and live agree (both wrong), so it hides until someone reconciles fills by hand.

**Fix:** use `opts.copy(...)` (carries unlisted fields forward + future-proof) and add `subst` for `StackAst`/`StackAtClause`.

### MEDIUM — FOR EACH renames a latch's stream but not the aliases inside it — `IterVarSubstitution.kt:113`

**What it is:** `is Latch -> action.copy(stream = alias)` fixes the top-level alias but leaves `action.sensor` and `action.entries` untouched, so a buried `FROM s.high` keeps literal `s` → "Unknown stream alias". A FOR EACH'd latch is dead on arrival.

**Fix:** recurse into `Latch.sensor` (`BreakOffset.reference/offset`) and each entry's `DirRel` distances.

### HIGH — Latch timestamps + GTD expiry stamped from wall-clock — `ActionCompiler.kt:43`

**What it is:** `ActionCompiler`'s ctor defaults `clock = SystemClock()` and `AstCompiler` doesn't pass one, so `LatchCompiler` calls `clock.now()` (wall-clock) inside the fire-time lambda to stamp the order timestamp and `expiresAt`. Every *other* action path correctly uses `ctx.strategyContext.clock.now()` — only latch leaks.

**Why it matters:** **non-reproducible backtest** (two runs over identical history get different timestamps → breaks backtest==live) **and a live bug**: a backtested `… EXPIRE 5m` computes `expiresAt = systemNow(today) + 5m`, years ahead of the simulated clock → the order never expires.

```kotlin
val now = clock.now()                       // SystemClock — wall time, even in backtest
//                ↓ fix
val now = ec.strategyContext.clock.now()    // FixedClock in backtest, real in live
```

**Fix:** drop the ctor `clock` param from `ActionCompiler`/`LatchCompiler`; read `ec.strategyContext.clock.now()` in the builder lambda.

### MEDIUM — Live MT5 ticks re-stamped with wall-clock, discarding broker time — `Mt5TickFeedSource.kt:78`

**What it is:** the poller dedups on `tick.brokerTimeMs` but then builds the downstream `Tick` with `timestamp = clock.now()` — throwing away broker time for the field the candle aggregator bins on.

**Why it matters:** candle boundaries are decided by tick `timestamp`; the local poll moment (+ latency) can bin a `:59.998` broker tick into the next minute. MT5 replay and the Bybit live path use event time, so this source alone produces candles that disagree with both → undermines backtest==live for MT5 live.

**Fix:** `timestamp = tick.brokerTimeMs` (keep wall-clock only for the session gate / `capturedAtMs`).

### MEDIUM — `parseStrategy` lets a StrategyAst init failure escape — `Parser.kt:328-341`

**What it is:** `StrategyAst.init` uses `require(...)` (throws `IllegalArgumentException`) for dup PARAM / PARAM-LET collisions, but `parseStrategy` constructs the AST outside any catch (its `try/catch` only wraps the header and catches `ParseException`). So a duplicate-PARAM file throws straight out of a function contracted to return `Success`/`Failure`.

**Why it matters:** a user typo crashes the CLI/loader instead of surfacing a clean parse error. `parsePortfolio` already does it right.

**Fix:** wrap `StrategyAst(...)` in `try { Success(...) } catch (e: IllegalArgumentException) { Failure(...) }`.

### MEDIUM — VWAP `error()`s on a type-legal null tick volume — `VWAP.kt:45-47`

**What it is:** `Tick.volume` is `BigDecimal? = null` (FX/metals have no traded volume; the MT5 source sets it null). `VWAP.update` runs per-tick and `error()`s on null → `IllegalStateException` on the hot path can tear down the live session on the first volumeless tick.

**Fix:** `val volume = input.volume ?: return` (a volumeless tick contributes nothing; `value()` keeps its last reading). A hard requirement belongs at strategy-load validation, not the per-tick path.

### MEDIUM — Candle BigDecimal scale diverges across vendors — `LocalBarStore.kt:104-114` (+ Bybit/MT5 clients)

**What it is:** `Candle` is a `data class`, so `equals`/`hashCode` use scale-sensitive `BigDecimal.equals` (`"42500.0" != "42500.00"`). The TradingView path normalizes every field to `Money.SCALE`; Bybit, MT5, and `LocalBarStore.readDay` feed raw `toBigDecimal()`.

**Why it matters:** the same bar from two vendors is unequal under `==` and hashes differently → any dedup/diff/set-compare across vendors (parity checks, replay reconciliation, cache lookups) silently misbehaves.

**Fix:** normalize scale in one place — ideally canonicalize in the `Candle` constructor (`setScale(Money.SCALE, …)` per field) so scale is a property of the type, not each call site.

---

## 4. Performance (hot-path data structures & allocation)

The hot path is `EventBus.publish → Engine → TradingPipeline → OrderManager.evaluateTriggers → StrategyPositionTracker → broker`, run every tick and per order/fill. **Only finding 4.1 is a true space/algorithmic blowup**; 4.8/4.9 are real O(period)/O(n) rescans; everything else is **constant-factor allocation churn** — fixed extra garbage per tick that raises GC frequency but not the run's Big-O.

### HIGH — Unbounded per-tick equity sampling — `EquityCurveCollector.kt:37-43`

**What it is:** under the default `TICK` cadence, `sample()` runs every tick and appends an `EquitySample` (+ BigDecimals) per strategy to lists that are **never capped**. Retained objects = `tickCount × (1 + strategyCount)`, and the whole curve is serialized one-object-per-sample.

**Why it matters:** a long MT5 tick run is tens of millions of ticks → tens of millions of retained objects + a report that scales the same. The one genuine OOM risk.

```kotlin
// fix — fold drawdown/Sharpe exactly & incrementally, then store at a fixed stride
drawdownAccumulator.observe(globalEquity)        // O(1), no retention
if (seen % stride == 0L) { globalCurve.add(EquitySample(timestamp, globalEquity)); … }
seen++
```

**Fix:** decimate the stored curve to a fixed point budget; compute metrics on the full stream before decimation.

### MEDIUM — `persistAll` rebuilds 3 maps + sync disk write per fill — `OrderManager.kt:1144-1207`

**What it is:** called after *every* `track`/`update`/`recordSiblings`; each call allocates 3 maps, scans all orders + `siblings` twice, then writes JSON to disk synchronously. Order events cluster (an entry spawns SL/TP), so one tick triggers several full rebuild-and-flush cycles. The disk write is the real cost.

**Fix:** dirty-flag on mutation + one coalesced flush at end-of-tick.

### MEDIUM — `forEach` allocates an iterator per publish — `EventBus.kt:63`

**What it is:** `subscribers[…]?.forEach { … }` allocates an `Iterator` per published event — the single most-traversed line in the engine. (The `stamp()` copy is load-bearing for replay determinism; leave it.)

```kotlin
val handlers = subscribers[stamped::class] ?: return
for (i in handlers.indices) handlers[i](stamped)   // zero iterator allocation
```

### MEDIUM — Latch distance re-compiled on every fire — `LatchCompiler.kt:118`

**What it is:** the fire-time lambda calls `exprCompiler.compile(rel.dist)` every fire, recompiling a compile-time-constant distance (AST walk + object graph) on a path that fires often in choppy markets.

**Fix:** hoist `compile(...)` into `compileEntry`; capture the `CompiledExpr`; only `evaluate(ec)` at fire time.

### MEDIUM — `tickFedForAlias` filters the whole binding list per stream per tick — `IndicatorBinding.kt:254`

**What it is:** `bindings.filter { it.isTickFed() && it.rootAlias == alias }` — O(bindings) scan + a fresh list per stream per tick, for a set that's fixed after compilation.

**Fix:** precompute `Map<alias, List<IndicatorBinding>>` once; the per-tick call becomes a lookup.

### MEDIUM — Monte Carlo materializes a full simulations×trades matrix — `MonteCarlo.kt:23,48-59`

**What it is:** allocates the whole `Array(simulations){Array(n){…}}` then re-extracts + sorts each column (~1M transient BigDecimals at 1000×1000). Research-time, not hot-path — a transient memory spike, hence MEDIUM.

**Fix:** process one column at a time into a reused buffer; free as you go.

### MEDIUM — `StackTracker.all()` copies values per call — `StackTracker.kt:39`

**What it is:** `active.values.toList()` allocates a fresh list per call — and it's hit **per queued id inside `runGc`** (`isReferenced` → `for (s in stacks.all())`), so draining K terminal orders allocates K copies.

**Fix:** return `Collections.unmodifiableCollection(active.values)` (callers only read).

### LOW (real algorithmic) — Rolling high/low rescan the window per `value()` — `RollingHigh.kt:35-40` / `RollingLow.kt:31-37`

**What it is:** `value()` loops the whole window for max/min — O(period) per read; Donchian periods are 50–200, read per bar/tick. A genuine algorithmic inefficiency (not allocation), LOW because the window is small.

```kotlin
// monotonic deque → amortized O(1) update, O(1) read; front is the window max
while (mono.isNotEmpty() && mono.last() < input) mono.removeLast(); mono.addLast(input)
if (window.size > period) { val ev = window.removeFirst(); if (mono.first() == ev) mono.removeFirst() }
override fun value() = if (isReady) mono.first() else null
```

### LOW — Per-tick `liveOrderIds.map` + 3-filter chain — `OrderManager.kt:1319,1357-1361`

**What it is:** per tick, a snapshot of all live orders + three chained `.filter`s (state, symbol, triggerHit), each allocating. **A naive single-pass inline is unsafe** — the `triggered` snapshot is load-bearing because `fireFallbackTrigger` mutates `liveOrderIds` mid-iteration.

**Fix:** a `Map<symbol, LinkedHashSet<id>>` live index so the scan touches only this tick's symbol, folded into one pass that still produces the snapshot.

### LOW — `makeChildEmit` allocates an identity lambda per child per tick — `PortfolioStrategy.kt:118-122`

**What it is:** returns `{ sig -> upstream(sig) }` — a no-op forwarding closure allocated per active child per tick.

**Fix:** pass `emit`/`upstream` directly; delete `makeChildEmit` (reintroduce a real wrapper only if per-child tagging is ever needed — YAGNI now).

---

## 5. Dead code, duplication, observability & standards

Every claim grep-verified against `src/main` + `src/test`. Where the raw finding was wrong, the correction is stated instead of a deletion.

### Dead code (referenced only by their own tests, or written-never-read)

- **`Rule.kt:13-109`** — a sealed `Rule` + `gt`/`lt`/`eq` infix algebra, zero callers (the live DSL uses its own `Cmp`). Delete the file.
- **`Reductions.kt:8-27`** — 9 `Sequence` extensions, only `ReductionsTest`. Delete.
- **`IndicatorMap.kt:8-24`** — only `IndicatorMapTest`. Delete.
- **`ManagedOrder.kt:21` + `OrderManager.kt:979,991`** — `groupId` set twice, never read (the `MT5Broker:495` read is a different `Composite.groupId`). Drop the field + writes.
- **`TimeWindow.kt:40` `windowEndFor`** — only its test (`windowStartFor` is the live one). Delete.
- **`SvgChart.kt:27-51` `lineChart`** — only its test. Delete.
- **`StrategyPositionTracker.kt:435-447 closeLeg` / `:508-518 driftFor`** — only tracker tests; the live path uses private `closeLegByTicket`. Delete *unless* reconciliation/STACK-bracket close is on the roadmap.
- **`TradeHistory.kt:207-209 MONEY_ZERO`** — `@Suppress`-annotated, unused. Delete.
- **`RunCommand.kt:260-261`** — `trades.fold(ZERO){acc,_->acc}` ignores every element → always ZERO (the standalone twin of the `/status` ZERO bug). Replace with a real sum or a literal.
- **`OrderManager.kt:328`** — unreachable `else -> error(…"7d-b")` over a `when` already covering all 14 `OrderRequest` subtypes; it **defeats compiler exhaustiveness** (a 15th type routes to a runtime crash instead of a compile error). Delete the `else` (house-rule: no `else->error` over sealed types).
- **`HtmlReportConfig.kt:16,18,20`** — `drawdownThresholdPct`, `monteCarloSimulations`, `monteCarloSeed` never read (ReportBuilder hardcodes 1000/42L/-0.01). (Correction: `tradeTableHead/Tail`, `minTradesForMonteCarlo` ARE read — keep those.) Wire the three or drop them.
- **`StrategyAst.constants`** — populated only by the Kotlin builder; the text Parser always sets `emptyList()` and no compiler reads it. Compile it or remove it.
- **Correction — NOT dead: `OrderManager.kt:88 lastObservedPrice`.** The raw finding called it a write-only duplicate; it's actually read at `:775,1041` as a fallback seed when the price provider has no last price yet. Keep it.
- Plus the redundant dead `when` arms over sealed types at `ExprCompiler.kt:65`, `PaperBroker.kt:175-176`, `MT5BrokerSimulator.kt:286-287`; `ProfitFactor.kt:10-13` (both arms return null); the dead doc-links at `LocalBarStore.kt:22`, `MT5InstrumentRegistry.kt:11`, `RiskState.kt:25`, `AuditTicksCommand.kt:180-185`; and `DaemonCommand.kt:277-306` duplicated teardown.

### Dangerous duplication (can silently diverge — nothing forces sync)

- **`PnLProvider.kt:42-51` and `StrategyPnL.kt:40-52` — unrealized-PnL formula copied 2×** (global vs per-strategy book; correction: 2×, not 3× — `TradingPipeline:238-276` is the *realized* path). Byte-identical:

  ```kotlin
  price.subtract(pos.avgEntryPrice).multiply(pos.quantity).multiply(cs).setScale(Money.SCALE, Money.ROUNDING)
  ```

  Add a fee/sign fix to one and forget the other → per-strategy P&L (what operators watch) diverges from global P&L (what risk halts on), no test/compile failure. Extract one `unrealized(pos, price, contractSize)` helper. (Related: the realized `multiply(cs)` block is itself copy-pasted into the partial-fill branch at `TradingPipeline:274-282`.)
- **`PositionProvider.kt:31-75` and `StrategyPositionTracker.kt:313-414` — averaging/flip state machine duplicated** (signed-qty model vs leg model). The weighted-average + realized-on-close math is mirrored; these feed global risk vs per-strategy status, so a divergence is a "global flat, strategy long" bug. Extract the price math into shared pure functions.
- **`LocalMarketSource.kt:98-145` — `aggregateFromTicks` reimplements `CandleAggregator` OHLC bucketing AND drops bid/ask.** A strategy reading bid/ask off a candle behaves differently in backtest vs live — a correctness gap, not just dup. Feed the tick stream through `CandleAggregator.standalone(window)`.

### Observability / PnL (report wrong numbers — worse, the system looks healthy)

- **`StrategyHandleJson.kt:71-74` — equity/balance/realized/unrealized hardcoded to ZERO** for every daemon `/status`. The `qkt observe` gate checks `Σ(fills.realized) ≈ statusRealized`; with `statusRealized=0` it only reports "consistent" when fills net ~0, and flags a mismatch on any genuinely profitable/losing run. The provider already holds the live session.

  ```kotlin
  realized = strategyPnL.realizedFor(id), unrealized = strategyPnL.unrealizedTotalFor(id),
  equity = strategyPnL.equityFor(id), balance = strategyPnL.balanceFor(id),
  positions = strategyPositions.positionsFor(id).map { (sym, p) -> PositionDto(sym, p.quantity, p.avgEntryPrice) },
  ```

- **`EquityTracker.kt:35` — per-strategy equity/peak refreshed only on fill** (`updateStrategy` is called only from `RiskState.onFill`). Since equity includes *unrealized* P&L, it freezes between trades → stale `ACCOUNT.*` accessors + lagging drawdown. Drive `updateStrategy` from the tick path (or read `strategyPnL.equityFor` live, tracker only for the peak).
- **`StrategyHandle.kt:40` — `tradeCount = ring.size()`** counts both `trade` and `signal` appends and caps at the ring capacity (1000). Exported as `qkt_strategy_trades_total` — a "counter" that double-counts signals then flatlines. Use a dedicated monotonic `AtomicLong` incremented in `onTrade`.

### Standards smells

- **`BollingerBands.kt:56-68` — `MathContext.DECIMAL128` instead of `Money.CONTEXT` (DECIMAL64).** House rule is one money context; mixing precisions can produce values that don't round-trip. (`VWAP.kt` has the same — fix both, or document a deliberate indicator-internal precision policy.)
- **`Position.kt:11` — temporal "legacy" comment** (forbidden wording), and slightly false (`BybitLinearBroker:186` constructs without `openedAt` today). Reword to describe *what* a null `openedAt` means.
- **`RangeAggregateIndicator.kt:50-71` — `currentRefreshKey()` is side-effecting** (the `EveryNTicks` branch mutates `ticksSinceRefresh`). A "get" that advances a counter breaks command-query separation. Rename to `advanceRefreshKey()` (or split the read from the advance).
- **`Parser.kt:429-430` — bare `=` silently parsed as equality.** Both `==` and `=` map to `Cmp.EQ` in comparison position, so `rsi = 30` in a condition is a silent equality check instead of a parse error. Drop the `TokenKind.EQ -> Cmp.EQ` mapping.
