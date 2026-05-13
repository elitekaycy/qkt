# Phase 29 — Engine state persistence (MVP)

**Status:** Shipped on `main`. MVP = write side only. Read side (preload + reconcile-at-deploy) shipping next as Phase 29a.
**Spec:** [`../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`](../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md)
**Plan:** [`../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md`](../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md)

---

## Summary

Phase 29 MVP makes the qkt engine's in-memory state durable on disk. Every mutation to a `LegBook`, an `OrderManager` bracket pair, an `OrderManager` pending order, or a `StackEngine` tier-fired flag writes an atomic JSON snapshot to `~/.qkt/state/<strategyId>/`. Writes are crash-safe (temp-file + atomic rename), best-effort (failures log + skip; never crash the engine), and partitioned per-strategy via `request.strategyId`.

**Behavior change:** none. The MVP ships only the write path. State files materialize on disk, but the engine still constructs fresh `LegBook` / `OrderManager` / `StackEngine` instances on every restart — the files are written but not yet read. Phase 29a adds the read side (preload + reconcile-at-deploy + tier-restore + `--reconcile=ignore-mismatches` flag).

That deliberate split lets you review the on-disk shape and the persistor surface before the read path lands and starts gating deploys.

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
  enabled: true     # default; set false to disable persistence entirely
  dir: ~/.qkt/state # default; ~/ is expanded against $HOME
```

`Config.statePersistor()` returns `FileStatePersistor` when enabled and `NoopStatePersistor` otherwise. The factory is wired into Phase 29a (read-side).

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

### `LegBookReconciler`

Built and tested in this phase (`com.qkt.persistence.LegBookReconciler`), not yet wired into the deploy path. The 3-way merge logic is complete and verified by 8 unit tests covering all four broker/persisted combinations plus tolerance edge cases.

---

## Migration from Phase 28

**Zero migration steps for existing deployments.** Phase 29 MVP is purely additive:

- State directory is created lazily on the first mutation; if `~/.qkt/state/` doesn't exist, it's created.
- Existing daemons with `state.enabled` unset get the production default (`true`) → files start materializing on the next strategy mutation. No behavior change.
- Operators who want the strict-no-disk-write behavior of pre-Phase-29 set `state.enabled: false`.

**No DSL changes. No CLI flag changes. No schema migration.** Restart behavior is identical to pre-Phase-29 until Phase 29a lands the read-side.

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

(Once Phase 29a lands, `qkt deploy` will refuse to start if broker positions exist without matching persisted state — operators will use `--reconcile=ignore-mismatches` as the escape hatch.)

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

## Known limitations (read these before merging)

- **Read side not wired yet.** Files are written but not read at startup. Restart behavior is **identical to pre-Phase-29** — fresh `LegBook` / `OrderManager` / `StackEngine` are constructed on every deploy. The original R5 failure mode (double-fire of STACK_AT tiers on restart) is **not yet mitigated**. Phase 29a addresses this.
- **No `MfeTracker` hwm persistence.** Tick-frequency state. Acceptable loss — on restart MFE re-warms from current price. The tier-fired flag IS persisted, so even if MFE re-crosses the threshold post-restart, the tier doesn't re-fire (once Phase 29a wires the preload).
- **`OrderRequest.Bracket` is skipped from `pending-orders.json`.** Only `Market` / `Limit` / `Stop` / `IfTouched` variants persist. Bracket-pair linkage is captured separately in `bracket-pairs.json`. Engine-internal expansions (`ScaleOut`, `TimeExit`, `Stack`) are also skipped — they're tracker-derived, not directly submitted.
- **No multi-instance support.** Single-writer assumption. Two daemons writing to the same `state.dir` simultaneously will race on file writes. Out of scope indefinitely.
- **No encryption at rest.** State files contain trading metadata (leg sizes, prices) but no secrets. Encrypt the volume if your threat model requires.
- **No event-sourced audit log.** Snapshot-only. If compliance demands tamper-evident order history, Phase 30+ would add an append-only event log alongside the snapshots.

## Phase 29a (next)

Tasks deliberately deferred to a fresh-head follow-up:

1. **Thread `StatePersistor` through the deploy stack**: `DaemonCommand` → `StrategyHandle.RealFactory` → `LiveSession` → `TradingPipeline` → `StrategyPositionTracker` + `OrderManager`. Today each state owner accepts a persistor param defaulting to `NoopStatePersistor`; Phase 29a wires the real one from `Config.statePersistor()`.
2. **Preload `LegBook` at startup.** `StrategyPositionTracker.preloadFromPersistor(strategyId)` reads `~/.qkt/state/<id>/*-legbook.json` and rebuilds in-memory legs before the strategy starts processing ticks.
3. **Preload `StackEngine.firedTierIndices`.** When `StackOrchestrator.onPrimaryFilled` constructs a new engine, it consults `persistor.loadPendingStacks(strategyId)` and seeds the `firedTierIndices` set so tiers don't re-fire. **This is the bit that actually fixes R5.**
4. **`LegBookReconciler` at deploy time.** Pull broker positions via the existing `MT5StateRecovery` / `BybitSpotStateRecovery` paths, three-way merge with persisted, refuse to start on mismatch (with `--reconcile=ignore-mismatches` flag as the escape hatch).
5. **End-to-end restart integration test.** Drive a strategy, persist state, simulate restart, assert no tier re-fire and broker positions reattach correctly.

Estimate: ~1 day of focused work.

---

## References

- Spec: [`../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`](../superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md)
- Plan: [`../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md`](../superpowers/plans/2026-05-13-phase29-engine-state-persistence.md)
- R5 audit item: prod-readiness sweep, 2026-05-13.
- Existing broker recovery paths used by Phase 29a's reconciler: `com.qkt.broker.mt5.MT5StateRecovery`, `com.qkt.broker.bybit.spot.BybitSpotStateRecovery`, `com.qkt.broker.bybit.linear.BybitLinearStateRecovery`.
- LegBook + PositionLeg introduced in Phase 27.
- StackEngine + PendingStacks introduced in Phase 27.
