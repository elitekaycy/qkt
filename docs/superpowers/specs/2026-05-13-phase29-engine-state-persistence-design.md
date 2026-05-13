# Phase 29 — Engine state persistence

> Today, every restart of the qkt daemon wipes the engine's leg metadata, bracket linkages, pending orders, and STACK_AT tier-fired state. Broker positions are recoverable via existing state-recovery paths, but qkt-side semantics ("this leg is a STACK_AT spawn of that primary; tier 1 already fired") are gone. Phase 29 makes the minimum slice of in-memory state durable so deploys and crashes don't lead to duplicate stack fires, lost bracket linkages, or operator confusion.

## Goal

A daemon restarted at 14:30 with open positions resumes correctly:

- The same logical `LegBook` reattaches to the broker-side positions.
- `STACK_AT` tiers that have already fired stay fired (no duplicate spawn on next tick).
- Bracket pairs (entry → SL+TP) remain coupled, so a TP fill correctly cancels its sibling SL.
- Pending orders submitted-but-not-yet-acked are recoverable.

A daemon restarted with a mismatch between persisted state and broker state **fails closed**: refuse to attach to unknown positions, surface the discrepancy to the operator, do not auto-flatten or auto-merge.

## Motivation

Concrete failure scenario as of Phase 28a:

It's 14:30 UTC. `hedge-straddle` has a live `LegBook` for XAUUSDm:

- PRIMARY: long 0.20 lots @ 4700, opened 14:00. `MfeTracker.hwm = 20`.
- STACK_AT tier 1 fired at 14:15 when MFE hit $10 — additional 0.06 lots long @ 4710.
- Pending bracket pairs in `OrderManager`: `entry-X` → `(sl-X, tp-X)` for primary, `entry-stack1-Y` → `(sl-stack1-Y, tp-stack1-Y)` for tier 1.

Operator pushes a config change. Daemon restarts at 14:30:30. Price now 4725.

Today's recovery on boot:
- Broker rehydrates: "you have 2 positions on XAUUSDm, net long 0.26 lots, plus 4 working orders (2 SL + 2 TP)".
- `LegBook` rebuilds as a single PRIMARY of 0.26 lots (the leg-aware metadata is gone).
- `PendingStacks` for `hedge-straddle` reconstructs from the strategy's compiled clauses — empty "tiers fired" set.
- On the next tick at 4725, `MfeTracker` starts fresh, computes MFE relative to the new average entry, crosses tier-1 threshold ($10) again → **fires tier 1 a second time**.
- The previous SL/TP working orders sit unmanaged. If primary SL fires, qkt doesn't know to cancel its paired TP. Operator gets dangling working orders.

That's R5 in concrete terms.

## Scope

### In scope

**A. Persist the following state objects to disk** on every mutating event:

1. **`LegBook` entries per strategy** — each leg's `legId`, `parentLegId`, `role` (PRIMARY/STACK), `symbol`, `side`, `quantity`, `entryPrice`, `openedAt`.
2. **Bracket linkage** — per strategy, `entryClientOrderId → (slClientOrderId, tpClientOrderId)` map. Owned by `OrderManager`.
3. **Pending stack tier-fired state** — per primary `legId`, which tier indices have already fired (so they don't re-fire after restart).
4. **`OrderManager` pending orders** — `clientOrderId → OrderRequest` for orders submitted-but-not-yet-confirmed-as-filled or rejected. Cleared on terminal fill/reject/cancel events.

**B. Atomic write protocol** — each file rewritten via temp-file + rename. POSIX rename is atomic, so a crash during write loses the new version but never corrupts the old one.

**C. Reconcile on boot** — three-way merge:

1. Load persisted state from disk.
2. Trigger existing broker state recovery (`MT5StateRecovery`, `BybitSpotStateRecovery`, `BybitLinearStateRecovery`).
3. For each broker-side position, find a persisted leg with matching `(symbol, side, quantity ± tolerance, entryPrice ± tolerance)`. If found, attach the leg metadata. If not found and the broker reports a position qkt has no record of, **refuse to start the strategy** and log the discrepancy with the broker-side details.

**D. Operator override** — `qkt deploy <strategy> --reconcile=ignore-mismatches` bypasses the strict check. Logs warnings, attaches what it can, treats unknown positions as new PRIMARY legs. Off by default.

**E. Tests** — unit tests for the persistor (write/read round-trip, atomic-rename semantics) and integration tests for the reconciler (matching positions, mismatch scenarios, missing-persisted-state, missing-broker-state).

### Out of scope

- **`MfeTracker` high-water-mark.** Tick-frequency state. Resets to current price on restart. Acceptable loss: at worst, MFE has to re-rise to a tier threshold for STACK_AT to fire, but the **tier-fired flag IS persisted** (so even if MFE re-crosses, the tier doesn't re-fire). Live `STACK_AT MFE >= 1000 WITHIN 30m` may "lose time on the clock" across restart, but that's defensible — the clause is about market behavior, not a strict timer.
- **`CandleHub` history rings.** Indicators rebuild from market data on warmup; no need to persist.
- **`MarketPriceTracker` last prices.** Repopulated from the first tick of each symbol post-startup.
- **`StrategyPnL` / `RiskState` daily counters.** **Out of scope this phase but worth flagging:** if a daemon restarts mid-day with a `MaxDailyLoss` halt active, the halt state resets — operator must manually halt or the strategy resumes. We could persist this, but the realized P&L itself is recomputed from broker fill history (already done by recovery), so the halt evaluation on the next tick is correct *for the trades the broker reports*. The only true loss is in-day MFE/drawdown intermediate state. Worth a follow-up but not this phase.
- **Multi-instance failover / leader election / streaming replication.** Single-instance daemon. No need for distributed state. Out of scope indefinitely.
- **Event-sourced audit log.** Snapshot-only design. If compliance later demands a tamper-evident log, Phase 30 adds an append-only event log alongside the snapshots.
- **SQLite or external database.** JSON files only. Concurrency is single-writer (the daemon thread); no need for transactions.

## State to persist — canonical schema

### File layout

```
~/.qkt/state/
├── <strategy-name>/
│   ├── legbook.json
│   ├── bracket-pairs.json
│   ├── pending-orders.json
│   └── pending-stacks.json
```

`<strategy-name>` matches the deploy name (URL-safe per the existing `StrategyRegistry.NAME_REGEX`). Portfolio children use `<parent>/<alias>` so the on-disk path mirrors the in-memory naming.

`StateDir` (existing) gives us `~/.qkt/` as the root; we add a `stateDir.stateFor(strategyName, file)` helper.

### `legbook.json`

```json
{
  "version": 1,
  "strategyId": "hedge-straddle",
  "symbol": "XAUUSDm",
  "legs": [
    {
      "legId": "leg-001",
      "parentLegId": null,
      "role": "PRIMARY",
      "side": "BUY",
      "quantity": "0.20",
      "entryPrice": "4700.000",
      "openedAt": 1715607600000
    },
    {
      "legId": "leg-002",
      "parentLegId": "leg-001",
      "role": "STACK",
      "side": "BUY",
      "quantity": "0.06",
      "entryPrice": "4710.000",
      "openedAt": 1715608500000
    }
  ]
}
```

`BigDecimal` serialized as plain string (preserves precision). `Long` epoch ms for timestamps.

### `bracket-pairs.json`

```json
{
  "version": 1,
  "strategyId": "hedge-straddle",
  "pairs": [
    {
      "entryClientOrderId": "c-1",
      "stopLossClientOrderId": "c-1-sl",
      "takeProfitClientOrderId": "c-1-tp",
      "legId": "leg-001"
    },
    {
      "entryClientOrderId": "c-2",
      "stopLossClientOrderId": "c-2-sl",
      "takeProfitClientOrderId": "c-2-tp",
      "legId": "leg-002"
    }
  ]
}
```

`legId` cross-references `legbook.json`. Entries removed when the leg closes (TP or SL fills).

### `pending-orders.json`

```json
{
  "version": 1,
  "strategyId": "hedge-straddle",
  "orders": [
    {
      "clientOrderId": "c-3",
      "submittedAt": 1715608800000,
      "request": {
        "type": "Market",
        "symbol": "XAUUSDm",
        "side": "BUY",
        "quantity": "0.06",
        "timestamp": 1715608800000,
        "strategyId": "hedge-straddle"
      }
    }
  ]
}
```

`request` mirrors the `com.qkt.execution.OrderRequest` sealed hierarchy. One JSON discriminator per variant. Removed on terminal events (filled, rejected, cancelled).

### `pending-stacks.json`

```json
{
  "version": 1,
  "strategyId": "hedge-straddle",
  "perPrimary": {
    "leg-001": {
      "primaryClientOrderId": "c-1",
      "tiers": [
        {
          "index": 0,
          "mfeThreshold": "10",
          "withinMs": 1800000,
          "stackQuantity": "0.06",
          "slDistance": "200",
          "tpDistance": "2000",
          "fired": true,
          "firedAt": 1715608500000,
          "firedLegId": "leg-002"
        },
        {
          "index": 1,
          "mfeThreshold": "20",
          "withinMs": 3600000,
          "stackQuantity": "0.06",
          "slDistance": "200",
          "tpDistance": "2000",
          "fired": false,
          "firedAt": null,
          "firedLegId": null
        }
      ]
    }
  }
}
```

`fired = true` after a tier emits its order. Even if the order later fails (rejected, never fills), it stays `fired` — same semantics as today's in-memory `StackEngine`: each tier fires at most once per primary. The persisted `firedLegId` links to the spawned leg for the audit trail.

## Write protocol

```kotlin
internal class StateFileWriter(private val rootDir: Path) {
    fun write(strategyName: String, fileName: String, json: String) {
        val dir = rootDir.resolve(strategyName)
        Files.createDirectories(dir)
        val target = dir.resolve(fileName)
        val temp = dir.resolve("$fileName.tmp")
        Files.writeString(temp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun read(strategyName: String, fileName: String): String? {
        val target = rootDir.resolve(strategyName).resolve(fileName)
        return if (Files.exists(target)) Files.readString(target) else null
    }
}
```

Atomic rename means a crash during write is benign: either the old file is intact, or the new one is. No partial-write corruption.

**Write frequency:**

- `legbook.json` rewritten on every `addPrimaryLeg` / `addStackLeg` / `closeLeg` / `applyFillToLeg`. Typical rate: a few writes per hour for hedge-straddle (one per trade event).
- `pending-orders.json` rewritten on every `OrderManager.submit` and every terminal broker event. Higher rate, but still bounded — order events are not in the tick stream.
- `bracket-pairs.json` rewritten on every bracket emit / bracket leg close. Same rate as legbook.
- `pending-stacks.json` rewritten on every tier fire. At most N writes per primary across its lifetime (N = number of tiers).

No file is rewritten on every tick. The expensive path is the tick handler, which DOES update `MfeTracker.hwm` — but we deliberately do not persist that.

**File size:** A typical hedge-straddle deploy has ≤ 5 legs, ≤ 5 bracket pairs, ≤ 3 pending orders, ≤ 3 tiers per primary. Each file ≤ 2 KB. Writes are O(milliseconds) on any modern disk.

## Public API

### New types

```kotlin
// com.qkt.persistence.StatePersistor
interface StatePersistor {
    fun saveLegBook(strategyId: String, symbol: String, legBook: LegBook)
    fun loadLegBook(strategyId: String, symbol: String): PersistedLegBook?

    fun saveBracketPairs(strategyId: String, pairs: List<BracketPair>)
    fun loadBracketPairs(strategyId: String): List<BracketPair>

    fun savePendingOrders(strategyId: String, orders: Map<String, OrderRequest>)
    fun loadPendingOrders(strategyId: String): Map<String, OrderRequest>

    fun savePendingStacks(strategyId: String, perPrimary: Map<String, PersistedTierState>)
    fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState>

    fun clearStrategy(strategyId: String)
}

class FileStatePersistor(private val stateDir: StateDir) : StatePersistor
class NoopStatePersistor : StatePersistor   // for tests; in-memory map
```

### Reconciliation API

```kotlin
// com.qkt.app.recovery.LegBookReconciler
class LegBookReconciler(
    private val persistor: StatePersistor,
    private val brokerView: () -> Map<String, BrokerPosition>,
) {
    sealed class Outcome {
        data class Attached(val legBook: LegBook) : Outcome()
        data class Mismatch(val symbol: String, val brokerSide: BrokerPosition, val persistedSide: PersistedLeg?) : Outcome()
        data object NothingPersisted : Outcome()
    }

    fun reconcile(strategyId: String, symbol: String): Outcome
}
```

`Mismatch` surfaces both the broker view and the persisted view so the operator can debug. The deploy path refuses to start the strategy on `Mismatch` unless `--reconcile=ignore-mismatches` is set.

### Integration with existing components

- **`StrategyPositionTracker`** gains a constructor param `persistor: StatePersistor = NoopStatePersistor()`. Every leg mutation invokes `persistor.saveLegBook(...)`. Tests retain their no-persistence behavior by default.
- **`OrderManager`** gains the same param; bracket-pair updates + pending-order updates invoke the persistor.
- **`StackEngine`** invokes `persistor.savePendingStacks(...)` after each tier fire.
- **`DslStrategy` boot path** invokes `LegBookReconciler.reconcile(...)` before subscribing to the bus. On `Mismatch`, throws `ReconcileException` which the daemon catches and surfaces via the deploy response.

### Configuration

A new top-level field in `qkt.config.yaml`:

```yaml
state:
  enabled: true   # default true; set false to disable persistence entirely
  dir: ~/.qkt/state   # default; override for non-standard installs
```

`Config.statePersistor()` returns the right `StatePersistor` instance based on the config. When `enabled: false`, returns `NoopStatePersistor` and the daemon behaves exactly as today (Phase 28 baseline).

## Reconcile-on-boot detail

For each `(strategy, symbol)` pair at deploy time:

1. **Pre-condition check.** Persistor file exists? Broker reports positions on this symbol?
2. **Four cases:**

   | Broker has positions? | Persisted state? | Outcome |
   | --- | --- | --- |
   | No | No | Clean state. Start fresh. |
   | No | Yes | Stale persisted state. **Wipe persisted state with a warning log.** Start fresh. (Broker is the source of truth.) |
   | Yes | No | Unknown positions. **Refuse to start unless `--reconcile=ignore-mismatches`.** With ignore, attach as a fresh PRIMARY leg (one new leg per broker position). |
   | Yes | Yes | Reconcile. See below. |

3. **Reconciliation (broker + persisted both present):**

   For each broker position, find a persisted leg with matching `(side, quantity, entryPrice)` within tolerance (e.g. quantity ± 0.001, entryPrice ± 1 point). Attach the leg metadata. If any broker position is unmatched, treat as a mismatch (case 3).

   Tolerance is needed because the broker may report fractionally different quantities (e.g. partial fills) or average entry prices that drifted over time.

## Failure modes & their handling

| Failure | Today's behavior | Phase 29 behavior |
| --- | --- | --- |
| Daemon crashes mid-trade | All in-memory state lost; broker keeps positions | State persisted to disk on every mutation; on restart, reconciler reattaches |
| Operator deploys updated `.qkt` for a running strategy | Mid-trade state lost (same as crash) | Same recovery path; new compiled strategy attaches to existing legs by symbol |
| Disk full when writing state | Throws IOException, but the in-memory state was already mutated; out-of-sync until next write | **Defensive logging on IOException, but do not crash the daemon.** Next mutation retries. (Acceptable: at worst we lose one write's worth of staleness.) |
| Corrupted JSON on read | Throws on `Json.parse`, no fallback | **On parse failure, log + treat as "no persisted state".** Operator must reconcile manually via `--reconcile=ignore-mismatches`. Safer than crashing. |
| State file written by older qkt version | Schema mismatch | Each file carries `"version": 1`. Reader checks; on mismatch logs + treats as "no persisted state". Manual operator intervention required. |
| Broker reports a position we never persisted | Today: silently merged into the rebuilt LegBook as a PRIMARY | **Refuse to start.** Surfaces operator decision: was this opened by a manual broker action? A different qkt instance? Investigate before resuming. |

## Testing strategy

### `StatePersistor` round-trip tests

```kotlin
@Test
fun `legbook write and read round-trip preserves all fields`(@TempDir tmp: Path) {
    val persistor = FileStatePersistor(StateDir.of(tmp))
    val legBook = LegBook().apply {
        addPrimary(...)
        addStack(...)
    }
    persistor.saveLegBook("hedge-straddle", "XAUUSDm", legBook)
    val loaded = persistor.loadLegBook("hedge-straddle", "XAUUSDm")
    assertThat(loaded).isNotNull
    assertThat(loaded!!.legs).hasSize(2)
    assertThat(loaded.legs[0].role).isEqualTo(LegRole.PRIMARY)
    assertThat(loaded.legs[1].parentLegId).isEqualTo(loaded.legs[0].legId)
}
```

### Atomic write tests

```kotlin
@Test
fun `concurrent reads while writing always see a complete file`() {
    // Race a writer thread against many reader threads. Every read must see either
    // the old complete file OR the new complete file. Never a torn write.
}
```

### Reconciler tests

```kotlin
@Test
fun `mismatch when broker has unknown position refuses to start`()
@Test
fun `broker positions match persisted legs by side+qty+price within tolerance`()
@Test
fun `stale persisted state with no broker positions is wiped with warning`()
@Test
fun `partial fill changes broker qty by 1 contract — reconciles within tolerance`()
```

### Integration tests

- Restart a `LiveSession` mid-trade with `FileStatePersistor` and `FakeBroker`. Persist state on tier fire. Stop the session. Start a new session. Verify the new session attaches to the same `LegBook` legs and does not re-fire the tier.
- Same scenario with `ignore-mismatches` flag and an unmatched broker position — verify it attaches as a new PRIMARY.

## Migration & backwards compatibility

- **Existing deployments without persisted state**: case 1 (no broker positions) or case 3 (broker positions, refuse to start). Operator must either:
  - Flatten manually before first Phase 29 startup, OR
  - Use `--reconcile=ignore-mismatches` on first startup to attach as new PRIMARY legs.
- **Phase 29 changelog must document the breaking startup behavior prominently.**
- No backwards-compatibility shims. Per qkt §7: "We do not maintain compatibility shims, deprecated aliases, or transitional code paths."

## Open questions

- **Persist `MfeTracker` hwm to reduce post-restart MFE re-warming?** Adds frequent writes (every tick that advances hwm). Probably ~1 write/sec/symbol during active moves. Pushing to Phase 30 unless the loss-of-hwm proves problematic in prod.
- **Should `pending-orders.json` deduplicate against broker state?** Today the broker recovery already lists working orders. If we reconcile pending-orders against broker working orders, we can drop any that the broker shows as gone. Worth doing as part of Phase 29 to keep the file clean.
- **Encryption / at-rest secrets?** No secrets in these files — only strategy state. No encryption needed.

## Effort estimate

| Component | LOC | Days |
| --- | --- | --- |
| `StatePersistor` interface + `FileStatePersistor` + `NoopStatePersistor` | ~250 | 0.5 |
| JSON serializers for the 4 state shapes (`LegBook`, `BracketPair[]`, `OrderRequest map`, `PendingTierState`) | ~200 | 0.5 |
| `LegBookReconciler` + reconcile-on-boot wiring in deploy path | ~150 | 0.5 |
| Integration into `StrategyPositionTracker`, `OrderManager`, `StackEngine` | ~80 | 0.25 |
| Config schema extension + `Config.statePersistor()` | ~50 | 0.25 |
| Deploy-time mismatch handling + `--reconcile=ignore-mismatches` flag | ~80 | 0.25 |
| Tests (round-trip, reconciler, restart integration, mismatch refuse-to-start) | ~500 | 1 |
| Phase 29 changelog | n/a | 0.25 |
| **Total** | **~1300 LOC** | **~3.5 days** |

## References

- R5 ("engine state in-memory only") from the prod-readiness audit, raised 2026-05-13 during the Phase 28 push.
- Existing recovery paths: `com.qkt.broker.mt5.MT5StateRecovery`, `com.qkt.broker.bybit.spot.BybitSpotStateRecovery`, `com.qkt.broker.bybit.linear.BybitLinearStateRecovery`.
- `LegBook` + `PositionLeg` introduced in Phase 27. State to persist mirrors their shape.
- `OrderManager` bracket-pair tracking introduced in Phase 13a.
- `StackEngine` + `PendingStacks` introduced in Phase 27.
