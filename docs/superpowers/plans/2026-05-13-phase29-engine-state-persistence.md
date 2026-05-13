# Phase 29 — Engine state persistence · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the four state objects that don't survive restart today (LegBook legs, bracket-pair linkage, OrderManager pending orders, StackEngine tier-fired state) to disk via atomic JSON files. Reconcile with broker state recovery on boot. Refuse to start on unmatched broker positions by default; offer `--reconcile=ignore-mismatches` operator escape hatch.

**Architecture:** `StatePersistor` interface with `FileStatePersistor` (atomic temp+rename) and `NoopStatePersistor` (tests). Files live at `~/.qkt/state/<strategy>/`. State owners (`StrategyPositionTracker`, `OrderManager`, `StackEngine`) accept an optional persistor and save on every mutation. `LegBookReconciler` runs at deploy time, three-way merges broker + persisted state.

**Tech stack:** Kotlin 1.9, JUnit 5 + AssertJ, kotlinx.serialization.json. No new dependencies. ~1300 LOC across ~18 tasks. Estimated 3-4 focused days.

> Read the spec first: `docs/superpowers/specs/2026-05-13-phase29-engine-state-persistence-design.md`.

---

### Task 1 — Recon: read the existing state-owner surface

**Files:**
- Read: `src/main/kotlin/com/qkt/positions/LegBook.kt`
- Read: `src/main/kotlin/com/qkt/positions/PositionLeg.kt`
- Read: `src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt` (find mutate sites: `addPrimary`, `addStackLeg`, `applyFill`, `close*`)
- Read: `src/main/kotlin/com/qkt/app/OrderManager.kt` (find: `orders`, `siblings`, `pendingChildren`, `submit`, `cancel`, `onFilled`)
- Read: `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt` (find: `firedTiers`, fire-once invariant)
- Read: `src/main/kotlin/com/qkt/dsl/compile/PendingStacks.kt`
- Read: `src/main/kotlin/com/qkt/execution/OrderRequest.kt` (sealed hierarchy — needed for JSON polymorphism)
- Read: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt` (existing `~/.qkt/` root)
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt` and Bybit equivalents

- [ ] Note mutation sites in `findings/phase29-recon.md`:
  - Every place `LegBook.add` / `LegBook.close` is called from `StrategyPositionTracker`
  - Every place `OrderManager.orders` is mutated (`submit`, `track`, `update`, `onFilled`, `onRejected`, `onCancelled`)
  - Every place `OrderManager.siblings` is mutated (bracket emit, sibling close)
  - Every place `StackEngine` records a fired tier
- [ ] Baseline: `./gradlew test --no-daemon` green.

---

### Task 2 — `StateDir.stateFor` helper + skeleton

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`
- Create: `src/main/kotlin/com/qkt/persistence/.gitkeep` (or first source file)

- [ ] Add `fun StateDir.stateFor(strategyName: String, fileName: String): Path` — resolves `<root>/state/<strategyName>/<fileName>`. Creates parent dirs lazily.
- [ ] Verify with a quick unit test that the path resolves correctly for both top-level strategies and portfolio children (`<parent>/<alias>`).
- [ ] Commit: `feat(persistence): StateDir.stateFor helper for engine state files`.

---

### Task 3 — `StatePersistor` interface + `NoopStatePersistor`

**Files:**
- Create: `src/main/kotlin/com/qkt/persistence/StatePersistor.kt`
- Create: `src/main/kotlin/com/qkt/persistence/NoopStatePersistor.kt`
- Test: `src/test/kotlin/com/qkt/persistence/NoopStatePersistorTest.kt`

- [ ] Define types (per spec):

```kotlin
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

data class PersistedLeg(
    val legId: String,
    val parentLegId: String?,
    val role: LegRole,
    val side: Side,
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
)

data class PersistedLegBook(
    val strategyId: String,
    val symbol: String,
    val legs: List<PersistedLeg>,
)

data class BracketPair(
    val entryClientOrderId: String,
    val stopLossClientOrderId: String?,
    val takeProfitClientOrderId: String?,
    val legId: String?,
)

data class PersistedTierState(
    val primaryClientOrderId: String,
    val tiers: List<PersistedTier>,
)

data class PersistedTier(
    val index: Int,
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
    val fired: Boolean,
    val firedAt: Long?,
    val firedLegId: String?,
)
```

- [ ] Implement `NoopStatePersistor` — backed by in-memory maps. Trivial getters/setters. Use case: tests that want to inspect what would have been persisted without touching disk.
- [ ] Test: round-trip a `LegBook` through `NoopStatePersistor.saveLegBook` + `loadLegBook`.
- [ ] Commit: `feat(persistence): StatePersistor interface + NoopStatePersistor in-memory impl`.

---

### Task 4 — `StateFileWriter` (atomic temp+rename)

**Files:**
- Create: `src/main/kotlin/com/qkt/persistence/StateFileWriter.kt`
- Test: `src/test/kotlin/com/qkt/persistence/StateFileWriterTest.kt`

- [ ] Implement (per spec):

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

    fun deleteStrategy(strategyName: String) {
        val dir = rootDir.resolve(strategyName)
        if (!Files.exists(dir)) return
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
```

- [ ] Tests:
  - Write then read returns identical content.
  - Concurrent reads while writing always see either old-complete or new-complete (never torn) — use a writer thread + 100 reader threads racing; assert no reader sees malformed JSON.
  - `deleteStrategy` removes the entire dir even with multiple files.
  - Read of missing file returns null (no exception).
- [ ] Commit: `feat(persistence): StateFileWriter with atomic temp+rename`.

---

### Task 5 — `FileStatePersistor` — `legbook.json` round-trip

**Files:**
- Create: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Create: `src/main/kotlin/com/qkt/persistence/PersistedLegBookDto.kt` (DTO + Json serializer)
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorLegBookTest.kt`

- [ ] Define `@Serializable` DTO with `BigDecimal` → `String` field converters (kotlinx.serialization can't `@Serializable` `BigDecimal` directly).
- [ ] `FileStatePersistor.saveLegBook` serializes via DTO; `loadLegBook` parses + maps back to domain types.
- [ ] Tests:
  - Save → load preserves all leg fields including `parentLegId = null`.
  - Save → file on disk has expected JSON shape (assert key names).
  - Load when file missing returns null.
  - Load when file contains `"version": 2` (future) returns null + logs warning.
- [ ] Commit: `feat(persistence): FileStatePersistor — legbook.json round-trip`.

---

### Task 6 — `FileStatePersistor` — `bracket-pairs.json`

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Create: `src/main/kotlin/com/qkt/persistence/BracketPairsDto.kt`
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorBracketPairsTest.kt`

- [ ] `BracketPair` is plain strings + nullable `legId`. Simple `@Serializable` shape.
- [ ] `saveBracketPairs` writes the full list (no incremental update; just rewrite on each mutation).
- [ ] Tests: round-trip with nullable fields, empty list, partial bracket (SL only, no TP), back-compat with explicit `"version": 1`.
- [ ] Commit: `feat(persistence): FileStatePersistor — bracket-pairs.json round-trip`.

---

### Task 7 — `FileStatePersistor` — `pending-orders.json` with OrderRequest polymorphism

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Create: `src/main/kotlin/com/qkt/persistence/PendingOrdersDto.kt`
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorPendingOrdersTest.kt`

- [ ] `OrderRequest` is a sealed class with variants: `Market`, `Limit`, `Stop`, `Bracket`, `IfTouched`, `ScaleOut`, `TimeExit`, `Stack`, etc.

  Two implementation options:
  - **(A)** Annotate the production `OrderRequest` types `@Serializable` with `@SerialName("type")` discriminator. Cleanest but invasive.
  - **(B)** Define a parallel `OrderRequestDto` mirror with a `type` discriminator. Map back and forth manually. Keeps prod code untouched.

  Plan recommends **(B)** because the `OrderRequest` types include domain fields (`BigDecimal`, time-typed instants) that kotlinx doesn't handle out of the box; the DTO can use strings throughout.

- [ ] Implement DTO per variant. For Phase 29 MVP, persist only the variants the deploy path actually submits: `Market`, `Limit`, `Stop`, `IfTouched`, `Bracket`. Skip `ScaleOut`/`TimeExit`/`Stack` — those are tracker-internal expansions, not directly persisted at submit time.
- [ ] Tests: round-trip each supported variant. Verify the `type` discriminator is in the JSON. Verify unsupported variant on load returns warning + empty map.
- [ ] Commit: `feat(persistence): FileStatePersistor — pending-orders.json with OrderRequest DTO`.

---

### Task 8 — `FileStatePersistor` — `pending-stacks.json`

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Create: `src/main/kotlin/com/qkt/persistence/PendingStacksDto.kt`
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorPendingStacksTest.kt`

- [ ] DTO mirrors `PersistedTierState`. All `BigDecimal` as plain string.
- [ ] Tests: tier flipped to `fired = true`, multiple primaries, empty map, version mismatch.
- [ ] Commit: `feat(persistence): FileStatePersistor — pending-stacks.json round-trip`.

---

### Task 9 — `FileStatePersistor` error paths

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorErrorsTest.kt`

- [ ] On `IOException` during save (disk full, permission denied): log at warn, do not throw. Next mutation retries.
- [ ] On `kotlinx.serialization.SerializationException` during load (corrupted file): log at warn, return null. Treat as "no persisted state".
- [ ] On schema version mismatch: log at warn, return null.
- [ ] Tests for each error path. Use `Files.setPosixFilePermissions` (chmod) to simulate permission denied on a temp dir.
- [ ] Commit: `feat(persistence): defensive error handling for save/load failures`.

---

### Task 10 — `LegBookReconciler`

**Files:**
- Create: `src/main/kotlin/com/qkt/persistence/LegBookReconciler.kt`
- Test: `src/test/kotlin/com/qkt/persistence/LegBookReconcilerTest.kt`

- [ ] Per spec: three-way merge across (broker positions, persisted legs, nothing).

```kotlin
class LegBookReconciler(
    private val persistor: StatePersistor,
    private val quantityToleranceLot: BigDecimal = BigDecimal("0.001"),
    private val priceToleranceFraction: BigDecimal = BigDecimal("0.0001"),  // 1 bp
) {
    sealed class Outcome {
        data class Attached(val legBook: LegBook) : Outcome()
        data class Mismatch(val details: String) : Outcome()
        data object NothingPersisted : Outcome()
    }

    fun reconcile(strategyId: String, symbol: String, brokerPositions: List<BrokerPosition>): Outcome
}
```

- [ ] Reconciliation algorithm:
  1. Load persisted `PersistedLegBook` for `(strategyId, symbol)`.
  2. If no broker positions AND no persisted state → `NothingPersisted`.
  3. If broker positions but no persisted state → `Mismatch("broker has N positions, no persisted state")`.
  4. If persisted state but no broker positions → wipe persisted state with warning; return `NothingPersisted`.
  5. If both present: greedy match each broker position to a persisted leg by `(side, quantity ± tolerance, entryPrice ± tolerance)`. If every broker position matches, return `Attached(rebuilt LegBook)`. If any unmatched → `Mismatch(...)`.

- [ ] Tests:
  - All four reconciliation cases (broker yes/no × persisted yes/no).
  - Tolerance: 0.0001 qty diff still matches; 0.01 qty diff does not.
  - Multiple legs: 1 PRIMARY + 1 STACK, broker reports both, reconciler attaches both with correct roles.
  - Partial fill: broker qty is 0.199 instead of 0.20 — within tolerance, match.
- [ ] Commit: `feat(persistence): LegBookReconciler — three-way merge of broker + persisted`.

---

### Task 11 — Integration: `StrategyPositionTracker` persists `LegBook` on mutation

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt`
- Test: `src/test/kotlin/com/qkt/positions/StrategyPositionTrackerPersistenceTest.kt`

- [ ] Add `persistor: StatePersistor = NoopStatePersistor()` constructor param.
- [ ] On every `addPrimary`, `addStackLeg`, `closeLeg`, `applyFill` that mutates `LegBook`: invoke `persistor.saveLegBook(strategyId, symbol, legBook)`.
- [ ] Test: with a `NoopStatePersistor` (in-memory), saving + retrieving captures the same legs.
- [ ] Verify existing `StrategyPositionTrackerTest` still passes unchanged (default `NoopStatePersistor`).
- [ ] Commit: `feat(persistence): StrategyPositionTracker persists LegBook on mutation`.

---

### Task 12 — Integration: `OrderManager` persists bracket pairs + pending orders

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerPersistenceTest.kt`

- [ ] Add `persistor: StatePersistor = NoopStatePersistor()` + `strategyId: String` constructor params.
- [ ] Define `BracketPair` extraction from internal `siblings` map. On every change to `siblings` (bracket emit, sibling close), call `persistor.saveBracketPairs(strategyId, currentPairs)`.
- [ ] On every `submit` / `track`: also call `persistor.savePendingOrders(strategyId, currentPending)`. On every terminal event (`onFilled`, `onRejected`, `onCancelled`): re-emit the (smaller) pending map.
- [ ] Test: emit a bracket order, assert persistor sees three orders + one bracket pair. Fire the SL, assert pending shrinks and pair entry is removed.
- [ ] Commit: `feat(persistence): OrderManager persists brackets + pending orders`.

---

### Task 13 — Integration: `StackEngine` persists tier-fired state

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/StackOrchestrator.kt` (pass persistor + strategyId through)
- Test: `src/test/kotlin/com/qkt/dsl/compile/StackEnginePersistenceTest.kt`

- [ ] Add `persistor: StatePersistor = NoopStatePersistor()` + `strategyId: String` to `StackEngine`.
- [ ] On every tier fire: build the `PersistedTierState` for this primary's engine and call `persistor.savePendingStacks(strategyId, mapOf(primaryLegId to state))`.
- [ ] On primary close: include in the next save with all tiers preserved (so an in-flight reconcile sees the historical fire state).
- [ ] Test: mock MFE rising past tier 1 threshold, assert tier is recorded as fired in the persistor. Confirm second pass-over the threshold does not re-fire.
- [ ] Commit: `feat(persistence): StackEngine persists tier-fired state per primary`.

---

### Task 14 — Config: `state` block + `Config.statePersistor()`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt`
- Test: `src/test/kotlin/com/qkt/cli/ConfigStateTest.kt`

- [ ] Extend `Config`:

```kotlin
data class StateConfig(
    val enabled: Boolean = true,
    val dir: String = "~/.qkt/state",
)

val state: StateConfig

fun statePersistor(): StatePersistor =
    if (!state.enabled) NoopStatePersistor()
    else FileStatePersistor(resolveStateDir(state.dir))
```

- [ ] Tests:
  - Config without `state:` block → defaults: `enabled = true`, `dir = "~/.qkt/state"`, returns `FileStatePersistor`.
  - `state.enabled = false` → returns `NoopStatePersistor`.
  - `state.dir = /tmp/custom` → expanded path used.
  - `~/` expansion handled correctly.
- [ ] Commit: `feat(cli): Config.state — engine state persistence config`.

---

### Task 15 — Deploy-time reconcile wiring + `--reconcile=ignore-mismatches`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` (in `RealFactory.create`)
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt` (parse the `?reconcile=ignore-mismatches` query param)
- Modify: `src/main/kotlin/com/qkt/cli/DeployCommand.kt` (CLI flag → query param)
- Test: `src/test/kotlin/com/qkt/cli/daemon/StrategyHandleReconcileTest.kt`

- [ ] In `StrategyHandle.RealFactory.create`:
  1. After loading the AST + brokers, fetch broker-side positions for each stream symbol via the broker's state recovery.
  2. Pass them into `LegBookReconciler.reconcile(strategyId, symbol, brokerPositions)`.
  3. If `Outcome.Attached`, install the rebuilt `LegBook` into `StrategyPositionTracker`.
  4. If `Outcome.Mismatch` and `ignoreMismatches = false`, throw `ReconcileException`. The deploy returns 409 Conflict with the details.
  5. If `Outcome.Mismatch` and `ignoreMismatches = true`, log + create fresh PRIMARY legs from broker positions.
  6. If `Outcome.NothingPersisted`, proceed as today.
- [ ] CLI flag: `qkt deploy <strategy> --reconcile=ignore-mismatches` propagates via the control-plane `/deploy` body or query param.
- [ ] Tests:
  - Deploy with no broker positions and no persisted state succeeds normally.
  - Deploy with matching broker + persisted state attaches the LegBook.
  - Deploy with mismatch + no flag returns 409 Conflict.
  - Deploy with mismatch + flag warns and proceeds.
- [ ] Commit: `feat(cli): deploy-time LegBookReconciler with --reconcile=ignore-mismatches escape hatch`.

---

### Task 16 — End-to-end restart integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/app/RestartIntegrationTest.kt`

- [ ] Spin up a `LiveSession` with `FakeBroker` (predictable fills) and `FileStatePersistor` (real disk, temp dir).
- [ ] Submit a primary BUY with STACK_AT. Drive ticks until tier 1 fires. Capture the resulting persisted state.
- [ ] Stop the session.
- [ ] Construct a fresh `LiveSession` with the same persistor pointed at the same temp dir.
- [ ] Assert: the new session's `StrategyPositionTracker` shows 2 legs (PRIMARY + STACK). `StackEngine` for the primary shows tier 1 `fired = true`. Driving ticks past tier 1's threshold again does NOT re-fire it.
- [ ] Assert: broker has the original 2 positions; pending orders are reattached.
- [ ] Commit: `test(app): end-to-end restart preserves leg metadata and tier-fired state`.

---

### Task 17 — Phase 29 changelog

**Files:**
- Create: `docs/phases/phase-29-engine-state-persistence.md`

- [ ] Per qkt §6, contains: Summary, What's new, Migration (prominent breaking-startup-behavior callout), Usage cookbook (5+ examples), Testing patterns, Known limitations, References.
- [ ] Usage cookbook examples:
  - Default behavior — state directory layout, what's persisted, what's not.
  - First-time deploy with existing broker positions — using `--reconcile=ignore-mismatches`.
  - Restart after crash — what the operator should see.
  - Disabling persistence in dev — `state.enabled = false`.
  - Inspecting persisted state via filesystem.
- [ ] Migration: prominently document that **first startup with persistence enabled on a daemon with open broker positions will refuse to start** unless the operator passes `--reconcile=ignore-mismatches`.
- [ ] Commit: `docs: phase 29 changelog — engine state persistence`.

---

### Task 18 — Pre-merge verification

- [ ] `./gradlew build --no-daemon` green.
- [ ] `./gradlew ktlintCheck --no-daemon` green.
- [ ] Manual smoke test: deploy a simple strategy locally, kill `-9` the daemon, restart, verify the strategy reattaches.
- [ ] Open PR per qkt §5 with the description template. Title: `[phase 29] feat: engine state persistence — restart preserves leg/bracket/tier state`.

---

## Out-of-band items the plan does NOT cover

- **`MfeTracker` hwm persistence.** Tick-frequency state. Phase 30+ if proves necessary.
- **`StrategyPnL` / `RiskState` daily-counters persistence.** Recoverable from broker trade history; phase 30 if the loss of intra-day state proves problematic.
- **Multi-instance failover / leader election.** Out of scope.
- **Event-sourced audit log.** Phase 30 if compliance demands.
- **External database (Postgres/SQLite).** Phase 30+ if scale demands.
