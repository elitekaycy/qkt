# Phase 29 — Engine state persistence

**Status:** Shipped on `main` (29 + 29a + 29b + 29c). End-to-end: write side, preload + tier-restore on startup, async wrapper, deploy-time reconciler with `--reconcile=ignore-mismatches` flag.
**Spec:** [`../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`](../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md)
**Plan:** [`../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md`](../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md)

---

## Summary

Phase 29 makes the qkt engine's in-memory state durable on disk so a daemon restart resumes correctly — no double-fire of STACK_AT tiers, no lost bracket linkages, no silent merge of unknown broker positions. Every mutation to a `LegBook`, an `OrderManager` bracket pair, an `OrderManager` pending order, or a `StackEngine` tier-fired flag writes an atomic JSON snapshot to `~/.qkt/state/<strategyId>/`. Writes are crash-safe (POSIX temp-file + atomic rename), best-effort (failures log + skip; never crash the engine), and partitioned per-strategy via `request.strategyId`.

**The four sub-phases shipped together:**

- **29 (write side):** persistor stack, four file shapes with DTOs, atomic `StateFileWriter`, integration into the three state owners (`StrategyPositionTracker`, `OrderManager`, `StackEngine`), `Config.state` block.
- **29a (read side):** persistor threaded end-to-end through `DaemonCommand` → `RealFactory` → `LiveSession` → `TradingPipeline` → `OrderManager` + `StackOrchestrator`. `LegBook` preloads from disk at boot. `StackOrchestrator` seeds new `StackEngine` instances with persisted `firedTierIndices`. **This is the bit that actually solves R5** — STACK_AT tiers can't double-fire on restart.
- **29b (operability):** `AsyncStatePersistor` decorator + `state.async` config flag. Backtest paths verified to use `NoopStatePersistor` by construction (invariant locked in test).
- **29c (deploy safety):** `Broker.getOpenPositions()`, `LegBookReconciler` wired at deploy time, `ReconcileException` → HTTP 409 on mismatch, `qkt deploy --reconcile=ignore-mismatches` operator escape hatch.

---

## What's new

### Persistor surface

- `com.qkt.persistence.StatePersistor` — interface with eight methods: `saveLegBook` / `loadLegBook`, `saveBracketPairs` / `loadBracketPairs`, `savePendingOrders` / `loadPendingOrders`, `savePendingStacks` / `loadPendingStacks`, `clearStrategy`.
- `com.qkt.persistence.NoopStatePersistor` — in-memory backing for tests and for the `state.enabled = false` config path.
- `com.qkt.persistence.FileStatePersistor` — production on-disk implementation. Writes via [`StateFileWriter`](src/main/kotlin/com/qkt/persistence/StateFileWriter.kt) (atomic temp+rename); reads via `kotlinx.serialization.json`.
- `com.qkt.persistence.StateFileWriter` — internal helper. POSIX `ATOMIC_MOVE` rename. Read returns `null` on missing file. `deleteStrategy` walks and removes the per-strategy dir. All paths catch and log `IOException` — no throws to the engine.

### DTOs

- `com.qkt.persistence.PersistedLeg` + `PersistedLegBook` — leg metadata (legId, parentLegId, role, side, quantity, entryPrice, openedAt).
- `com.qkt.persistence.BracketPair` — `(entryClientOrderId, stopLossClientOrderId?, takeProfitClientOrderId?, legId?)`.
- `com.qkt.persistence.PersistedTier` + `PersistedTierState` — per-primary tier-fired state including `firedAt` and `firedLegId` audit trail.

### Three state owners now persist on mutation

- `com.qkt.positions.StrategyPositionTracker` — `applyFill`, `addStackLeg`, `closeLeg` invoke `persistor.saveLegBook` after every `book.add` / `book.close`.
- `com.qkt.app.OrderManager` — every `track` / `update` triggers `persistAll()` which snapshots pending orders (state ∈ {PENDING, CREATED}) and bracket pairs, partitioned by `request.strategyId`.
- `com.qkt.dsl.compile.StackEngine` — every tier fire invokes `persistor.savePendingStacks(strategyId, { primaryLegId → PersistedTierState })`. The persisted tiers carry `fired`, `firedAt`, `firedLegId` so a future reconcile can rebuild the engine state.

### Configuration

```yaml
state:
  enabled: true       # default; set false to disable persistence entirely
  dir: ~/.qkt/state   # default; ~/ is expanded against $HOME
  async: false        # default; set true to wrap the file persistor in AsyncStatePersistor
```

`Config.statePersistor()` returns:
- `NoopStatePersistor` if `state.enabled = false`
- `FileStatePersistor` if `enabled = true` and `async = false`
- `AsyncStatePersistor(FileStatePersistor(...))` if both `enabled` and `async` are true

The chosen persistor is threaded end-to-end through `DaemonCommand` → `StrategyHandle.RealFactory` → `LiveSession` → `TradingPipeline` → `OrderManager` + `StackOrchestrator`.

### File layout

```
~/.qkt/state/
└── <strategyId>/
    ├── <symbol>-legbook.json
    ├── bracket-pairs.json
    ├── pending-orders.json
    └── pending-stacks.json
```

LegBook files are per-symbol within a strategy (multi-symbol strategies write multiple files). The other three are strategy-global. All files carry `"version": 1` for forward-compat parsing.

### Read side — preload + tier-restore (29a)

- `StrategyPositionTracker.preloadFromPersistor(strategyId, symbol)` — called from `LiveSession.start()` reconcile step. Reads `~/.qkt/state/<id>/<sym>-legbook.json` and rebuilds the in-memory `LegBook` (PRIMARY + STACK legs with `parentLegId` linkage intact) before the engine takes its first tick.
- `StackOrchestrator.onPrimaryFilled(...)` — when constructing a new `StackEngine`, loads `persistor.loadPendingStacks(strategyId)` and seeds `initialFiredTierIndices` from any persisted `fired = true` entries. **A STACK_AT tier that fired pre-restart cannot re-fire on the next tick.**
- `StackEngine` ctor accepts `initialFiredTierIndices: Set<Int>` and `initialFiredLegIds: Map<Int, String>` for the seeding.

### Reconciler-at-deploy (29c)

- `Broker.getOpenPositions(): Map<String, Position>` — new default-empty method on the `Broker` interface. `MT5Broker` overrides to query `MT5Client.getPositions(magic)` and net positions per qkt-side symbol. `PaperBroker` and other in-process brokers return empty.
- `LiveSession.reconcileOrPreload(strategyPositions, broker)` runs once after broker construction, before the engine thread starts. For each `(strategyId, symbol)`:
  - `Outcome.Attached` → `preloadFromPersistor` rehydrates the LegBook.
  - `Outcome.Mismatch` + `ignoreMismatches = false` → throws `ReconcileException` → control plane returns **HTTP 409 Conflict** with `kind: reconcile-mismatch` and the details.
  - `Outcome.Mismatch` + `ignoreMismatches = true` → warns and attaches broker positions as fresh PRIMARY legs.
  - `Outcome.NothingPersisted` → proceeds as today.
- CLI: `qkt deploy <strategy.qkt> --reconcile=ignore-mismatches` → `?reconcile=ignore-mismatches` query param on `/deploy` → `RealFactory.create(name, file, ignoreMismatches=true)`.

### Async wrapper (29b)

- `AsyncStatePersistor(delegate, queueCapacity, shutdownTimeoutMs)` — decorator. Snapshots inputs synchronously in the caller thread (cheap — small copies of immutable data), submits I/O to a single-threaded daemon executor. Reads stay synchronous.
- Bounded queue with `CallerRunsPolicy` back-pressure — if the queue fills, the producing thread runs the write itself rather than dropping silently.
- `close()` drains pending writes within `shutdownTimeoutMs`; remaining writes are abandoned with a warning.
- Trade-off: a crash between mutation and queue drain loses ≤ `queueCapacity` writes. For typical trade-event rates that window is < 1 second.

---

## Migration from Phase 28

**Phase 29c (reconciler-at-deploy) is a behavior change for live deployments with open broker positions.** First startup with persistence enabled on a daemon whose broker already holds positions will:

1. Query `Broker.getOpenPositions()` for each `(strategyId, symbol)` pair.
2. Compare against `~/.qkt/state/<strategyId>/<symbol>-legbook.json` (if it exists).
3. If broker reports positions qkt has no persisted record of → **`HTTP 409 Conflict` from `/deploy`, strategy refuses to start**.

Operators must either:
- Flatten broker positions manually before the first Phase 29 deploy, OR
- Pass `qkt deploy <strategy.qkt> --reconcile=ignore-mismatches` to attach broker positions as fresh PRIMARY legs.

The `--reconcile=ignore-mismatches` flag is intentional opt-in to make the unsafe-state attachment a deliberate operator decision rather than silent merge behavior.

**No DSL changes. No schema migration on the config file.** Operators who want the strict-no-disk-write behavior of pre-Phase-29 (no state files, no reconciler) set `state.enabled: false`.

---

## Usage cookbook

### 1. Default behavior — state writes happen automatically

```yaml
# qkt.config.yaml
brokers:
  exness:
    type: mt5
    gateway_url: ${MT5_GATEWAY_URL}
# state: defaults to enabled at ~/.qkt/state
```

```sh
qkt daemon start
qkt deploy hedge-straddle examples/hedge-straddle/hedge-straddle.qkt
```

As soon as a strategy fires its first signal, files appear:

```
~/.qkt/state/hedge-straddle/
├── XAUUSDm-legbook.json
├── bracket-pairs.json
└── pending-orders.json
```

Inspect any one with `cat ~/.qkt/state/hedge-straddle/XAUUSDm-legbook.json | jq .`.

### 2. Disable persistence in dev

```yaml
state:
  enabled: false
```

`Config.statePersistor()` returns `NoopStatePersistor`. No disk I/O happens. Restart behavior is identical to pre-Phase-29.

### 3. Custom state directory

```yaml
state:
  enabled: true
  dir: /var/lib/qkt-prod/state
```

Useful for prod deploys where `~` is `/root` but you want state under `/var/lib/...` for filesystem-permissions or backup purposes.

### 4. Inspecting persisted state

```sh
# What does qkt think this strategy holds?
$ cat ~/.qkt/state/hedge-straddle/XAUUSDm-legbook.json | jq .
{
  "version": 1,
  "strategyId": "hedge-straddle",
  "symbol": "XAUUSDm",
  "legs": [
    {
      "legId": "hedge-straddle-XAUUSDm-primary-1",
      "role": "PRIMARY",
      "side": "BUY",
      "quantity": "0.20",
      "entryPrice": "4700.000",
      "openedAt": 1715607600000
    }
  ]
}

# Which STACK_AT tiers have already fired?
$ cat ~/.qkt/state/hedge-straddle/pending-stacks.json | jq '.perPrimary[].tiers[] | select(.fired)'
```

### 5. Clearing state (operator-driven reset)

If you flatten manually and want a clean slate for the next deploy:

```sh
qkt stop hedge-straddle
rm -rf ~/.qkt/state/hedge-straddle
qkt deploy hedge-straddle examples/hedge-straddle/hedge-straddle.qkt
```

After Phase 29c (shipped), `qkt deploy` refuses to start if broker positions exist without matching persisted state. The above flatten-and-clear flow is one operator option; the other is `--reconcile=ignore-mismatches` to attach the broker positions as fresh PRIMARY legs.

### 6. Recovering from a `409 Conflict` on deploy

```sh
$ qkt deploy hedge-straddle hedge-straddle.qkt
qkt: error: deploy failed (409): {"error":"hedge-straddle/XAUUSDm: broker reports 1 position(s) for hedge-straddle/XAUUSDm, no persisted state. Pass --reconcile=ignore-mismatches to attach broker positions as PRIMARY.","kind":"reconcile-mismatch"}

# Operator decides: trust the broker positions, attach as PRIMARY.
$ qkt deploy hedge-straddle hedge-straddle.qkt --reconcile=ignore-mismatches
NAME           PORT     STATE     STARTED
hedge-straddle 38274    running   2026-05-13T...
```

### 7. Enabling the async wrapper for high-frequency strategies

```yaml
state:
  enabled: true
  async: true
```

`Config.statePersistor()` returns `AsyncStatePersistor(FileStatePersistor(...))`. Persist calls return in microseconds (just snapshot + queue submit); the actual I/O runs on a daemon thread. Use when your strategy generates >100 trade events/day or sub-second order cadence.

---

## Testing patterns

### Round-trip tests with `FileStatePersistor`

```kotlin
val persistor = FileStatePersistor(tempDir)
val book = LegBook("XAUUSDm").apply {
    add(PositionLeg(/* ... */, role = LegRole.PRIMARY))
}
persistor.saveLegBook("hedge", "XAUUSDm", book)
val loaded = persistor.loadLegBook("hedge", "XAUUSDm")
assertThat(loaded!!.legs).hasSize(1)
```

### Atomic write under concurrent reads

`StateFileWriterTest.concurrent reads while writing never see a torn file` races 16 reader threads against a 200-iteration writer. Every read must yield one of two valid documents — torn reads count as failures. This is the contract that makes the persistor safe for the engine to call on every mutation without a lock.

### Engine integration tests

`StrategyPositionTrackerPersistenceTest` / `StackEnginePersistenceTest` / (no dedicated `OrderManagerPersistenceTest` — coverage is via the existing OrderManager suite plus the `Bracket variant is silently skipped` case in `FileStatePersistorPendingOrdersTest`).

Each integration test constructs a `NoopStatePersistor`, drives the engine through its public API, and asserts `persistor.load*(...)` reflects the mutation.

---

## Known limitations

- **No `MfeTracker` hwm persistence.** Tick-frequency state. Acceptable loss — on restart MFE re-warms from current price. The tier-fired flag IS persisted, so even if MFE re-crosses the threshold post-restart, the tier doesn't re-fire (the seed-from-persistor path in `StackOrchestrator` skips already-fired tiers).
- **`OrderRequest.Bracket` is skipped from `pending-orders.json`.** Only `Market` / `Limit` / `Stop` / `IfTouched` variants persist. Bracket-pair linkage is captured separately in `bracket-pairs.json`. Engine-internal expansions (`ScaleOut`, `TimeExit`, `Stack`) are also skipped — they're tracker-derived, not directly submitted.
- **Multi-position-per-symbol netting on `Broker.getOpenPositions()`.** MT5 reports one row per ticket; `MT5Broker` nets them into a signed-quantity `Position` via weighted-average entry. For multi-position brokers (Bybit linear), the current netting collapses concurrent longs+shorts that should be tracked separately. Bybit linear's override isn't implemented yet — returns empty, so reconcile is a no-op there.
- **No multi-instance support.** Single-writer assumption. Two daemons writing to the same `state.dir` simultaneously will race on file writes. Out of scope indefinitely.
- **No encryption at rest.** State files contain trading metadata (leg sizes, prices) but no secrets. Encrypt the volume if your threat model requires.
- **`AsyncStatePersistor` crash window.** Up to `queueCapacity` writes (default 1024) can be in-flight between mutation and disk flush. For typical trade-event rates that window is < 1 second; high-frequency strategies should size the queue and shutdown timeout to their throughput.
- **No event-sourced audit log.** Snapshot-only. If compliance demands tamper-evident order history, Phase 30+ would add an append-only event log alongside the snapshots.

## Future work (Phase 30+)

Items intentionally not in this phase but worth tracking:

- **Bybit `getOpenPositions` override.** `BybitSpotBroker` and `BybitLinearBroker` should query their REST positions endpoint and return per-symbol `Position` snapshots. Without this, deploys against Bybit-broker daemons fall through to the empty-broker case (reconcile is a no-op, mismatch can't be detected).
- **`MfeTracker` hwm persistence.** Adds tick-frequency writes; only valuable if you care about sub-second MFE accuracy post-restart. Not currently a problem in practice.
- **End-to-end restart integration test.** Drive a strategy through a `FakeBroker` and `FileStatePersistor` (real temp-dir), persist state, stop the session, construct a new session pointed at the same temp-dir, assert legs reattach and tiers don't re-fire. Covered piecemeal today by unit tests but no single test runs the full lifecycle.
- **Multi-position-per-symbol netting fix.** When MT5 reports several tickets for the same symbol with opposite sides, current netting averages them into one signed `Position`. For prop-style accounts that hedge same-symbol both ways, this loses information. The reconciler should compare list-of-positions not net-position.
- **Bytes-on-disk benchmark / observability.** No metric today for "how often is the persistor write path slow?". Adding a counter + histogram around `Files.move` would let operators see disk pressure before it bites.

---

## References

- Spec: [`../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`](../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md)
- Plan: [`../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md`](../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md)
- R5 audit item: prod-readiness sweep, 2026-05-13.
- Existing broker recovery paths used by Phase 29a's reconciler: `com.qkt.broker.mt5.MT5StateRecovery`, `com.qkt.broker.bybit.spot.BybitSpotStateRecovery`, `com.qkt.broker.bybit.linear.BybitLinearStateRecovery`.
- LegBook + PositionLeg introduced in Phase 27.
- StackEngine + PendingStacks introduced in Phase 27.
