# Trigger-Pass Allocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-tick `ArrayList` materializations in `OrderManager.evaluateTriggers` with reused scratch buffers, byte-identical output.

**Architecture:** Pre-allocated `ArrayList` fields on `OrderManager`, `clear()`-ed and refilled each tick (capacity retained → zero steady-state list allocation). `StackTracker` gains a non-copying read view so its sweep stops allocating too. Phase order and `orders[id]` lookup count are preserved exactly.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, Gradle.

## Global Constraints

- **Branch:** `refactor-trigger-pass-alloc` (already created off `dev`). Never commit to `dev`/`testing`/`main`.
- **Commit format:** Conventional Commits, subject only, no body, no footer, no AI attribution. Max 70 chars. (`refactor(execution): ...`, `test(execution): ...`.)
- **This is a refactor — zero behavior change.** No test may be modified to make it pass. If a test needs changing, that is a parity break: stop and reassess.
- **Spec:** `docs/superpowers/specs/2026-06-18-trigger-pass-allocation-design.md`. The four parity-critical invariants (phase order; lookup count; iteration order; non-reentrancy) are binding.
- **Per-task local gate (fast):** `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.sweep.SweepReplayParityTest"` must stay green. Full `./gradlew build` (incl. `BacktestLiveParityTest`) runs in CI on push — do not block locally on it.
- **No emojis, no useless comments.** Match surrounding style in `OrderManager.kt`.

---

### Task 1: Establish the byte-identical baseline

**Files:** none modified — this task captures the reference behavior the refactor must preserve.

**Interfaces:**
- Produces: a confirmed-green baseline of the focused suite, which is the characterization net for Tasks 3–7.

- [ ] **Step 1: Confirm working tree is the unmodified refactor branch**

Run: `git status --short && git log --oneline -1`
Expected: only the spec doc committed (`d51f0c65`), no production changes; HEAD on `refactor-trigger-pass-alloc`.

- [ ] **Step 2: Run the focused suite on current code**

Run: `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.sweep.SweepReplayParityTest" --tests "com.qkt.app.OrderManagerStackTest" --tests "com.qkt.app.OrderManagerTimeExitTest" --tests "com.qkt.app.OrderManagerGtdSweepTest"`
Expected: `BUILD SUCCESSFUL`, all tests pass. These exercise every phase of `evaluateTriggers` (trailing, GTD sweep, time-exit, stack deadline, trigger firing). This green run is the behavior every later task must reproduce.

- [ ] **Step 3: Check phase-order invariant coverage**

Read `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt` and `OrderManagerTimeExitTest.kt`. Confirm there is a test where a pending order is cancelled by a same-tick stack-deadline or time-exit sweep and does NOT then fire a trigger. If such a test exists, note it and skip to Task 2. If absent, do Step 4.

- [ ] **Step 4 (only if Step 3 found a gap): Add the phase-order characterization test**

Mirror the harness in `OrderManagerStackTest.kt`. Add a test in that file that: arms a stack with a pending layer order whose trigger price the next tick crosses, sets the stack `withinMillis` so the same tick is past the stack deadline, feeds the tick, and asserts the layer was cancelled by the stack sweep and `fireFallbackTrigger` did NOT run for it (assert the layer's final state is `CANCELLED`/terminal and no fill/order was emitted for it).

```kotlin
@Test
fun `stack-deadline sweep cancels a pending layer before its trigger can fire on the same tick`() {
    // setup: copy the stack + pending-layer arrangement from the existing stack tests,
    // set withinMillis so the incoming tick is past the deadline AND crosses the layer trigger.
    // feed the tick, then:
    assertThat(layerState()).isEqualTo(OrderState.CANCELLED)
    assertThat(emittedOrdersFor(layerId)).isEmpty()
}
```

- [ ] **Step 5: Commit (only if a test was added)**

```bash
git add src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt
git commit -m "test(execution): pin same-tick sweep-before-trigger order"
```

---

### Task 2: Add a non-copying stack view to StackTracker

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/StackTracker.kt:39`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerStackTest.kt` (behavior covered indirectly; no new test required — see Step 3)

**Interfaces:**
- Produces: `StackTracker.activeView(): Collection<ActiveStack>` — a live, non-copying view of active stacks. Consumed by Task 7.

- [ ] **Step 1: Add `activeView()` next to `all()`**

In `StackTracker.kt`, immediately after the existing `all()`:

```kotlin
fun all(): Collection<ActiveStack> = active.values.toList()

/**
 * Live, non-copying view of active stacks for read-only iteration. Unlike [all], this does
 * not allocate a snapshot — callers must not mutate the tracker while iterating it. Used by
 * the per-tick stack-deadline sweep, which reads this to collect expired stacks, then mutates
 * in a second pass. e.g. for (s in tracker.activeView()) { ... } reads without copying.
 */
fun activeView(): Collection<ActiveStack> = active.values
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.app.OrderManagerStackTest" --tests "com.qkt.backtest.StackBacktestTest"`
Expected: all green. `all()` is unchanged so existing callers are unaffected; `activeView()` is additive.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/StackTracker.kt
git commit -m "refactor(execution): add non-copying active-stack view"
```

---

### Task 3: Reuse a scratch buffer for this-symbol live orders

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (add field near line 91; replace `symbolLive` materialization at 1515–1521)

**Interfaces:**
- Produces: `private val symbolLiveScratch: ArrayList<ManagedOrder>` filled per tick with this symbol's live orders, in `liveBySymbol` insertion order. Consumed by Task 4.

- [ ] **Step 1: Add the scratch field**

After the `gcQueue` field (`OrderManager.kt:91`), add:

```kotlin
// Reusable per-tick scratch buffers for [evaluateTriggers]. Each is cleared and refilled every
// tick; ArrayList.clear() retains capacity, so steady-state per-tick list allocation is zero.
// Shareable only because evaluateTriggers runs on the single engine thread and is not reentrant
// (its sole caller is the TickEvent subscription, and TickEvent is feed-sourced).
private val symbolLiveScratch = ArrayList<ManagedOrder>()
```

- [ ] **Step 2: Replace the `symbolLive` materialization and trailing loop**

Replace `OrderManager.kt:1515–1521`:

```kotlin
        val symbolLive =
            liveBySymbol[tick.symbol]?.map { orders[it] ?: error("live order index desync: $it") }
                ?: emptyList()
        for (managed in symbolLive) {
            if (managed.state != OrderState.PENDING) continue
            updateTrailingHwm(managed, tick.price)
        }
```

with:

```kotlin
        symbolLiveScratch.clear()
        liveBySymbol[tick.symbol]?.let { ids ->
            for (id in ids) {
                symbolLiveScratch.add(orders[id] ?: error("live order index desync: $id"))
            }
        }
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state != OrderState.PENDING) continue
            updateTrailingHwm(managed, tick.price)
        }
```

- [ ] **Step 3: Update the trigger phase to read the scratch buffer**

At `OrderManager.kt:1556–1559`, the `triggered` list currently reads the now-removed `symbolLive`. Change its source from `symbolLive` to `symbolLiveScratch` so it compiles (it is fully rewritten in Task 4):

```kotlin
        val triggered: List<ManagedOrder> =
            symbolLiveScratch
                .filter { it.state == OrderState.PENDING }
                .filter { triggerHit(it, tick) }
```

- [ ] **Step 4: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: all green. Insertion order preserved (`LinkedHashSet` → list), so trigger order is identical; `orders[id]` looked up once per order, as before.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): reuse scratch buffer for symbol live scan"
```

---

### Task 4: Single-pass trigger collection into a scratch buffer

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (add field; replace the two-`filter` chain at 1556–1562)

**Interfaces:**
- Consumes: `symbolLiveScratch` (Task 3).
- Produces: `private val triggeredScratch: ArrayList<ManagedOrder>`.

- [ ] **Step 1: Add the scratch field**

Beside `symbolLiveScratch`:

```kotlin
private val triggeredScratch = ArrayList<ManagedOrder>()
```

- [ ] **Step 2: Replace the trigger collect-then-fire block**

Replace the `triggered` block and its fire loop (`OrderManager.kt:1556–1562`):

```kotlin
        val triggered: List<ManagedOrder> =
            symbolLiveScratch
                .filter { it.state == OrderState.PENDING }
                .filter { triggerHit(it, tick) }
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
```

with one append loop plus the fire loop. Collection stays after the time-exit and stack sweeps — a swept order is terminal here and is filtered out:

```kotlin
        triggeredScratch.clear()
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state == OrderState.PENDING && triggerHit(managed, tick)) {
                triggeredScratch.add(managed)
            }
        }
        for (i in triggeredScratch.indices) {
            fireFallbackTrigger(triggeredScratch[i], tick.price)
        }
```

- [ ] **Step 3: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.app.OrderManagerTier2FallbackTest" --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: all green. Same predicate (`PENDING && triggerHit`), same order, collect-then-fire preserved.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): collect triggers into reused buffer"
```

---

### Task 5: Reuse a scratch buffer for the time-exit sweep

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (add field; replace 1538–1546)

**Interfaces:**
- Produces: `private val expiredExitsScratch: ArrayList<OrderRequest.TimeExit>`.

- [ ] **Step 1: Add the scratch field**

```kotlin
private val expiredExitsScratch = ArrayList<OrderRequest.TimeExit>()
```

- [ ] **Step 2: Replace the time-exit sweep**

Replace `OrderManager.kt:1538–1546`:

```kotlin
        val now = clock.now()
        val expired =
            timeExits.values
                .filter { now >= it.deadline.toEpochMilli() }
                .toList()
        for (te in expired) {
            timeExits.remove(te.id)
            handleTimeExitExpiry(te)
        }
```

with (fill first — read-only — then remove + handle, preserving the snapshot-then-mutate safety the `.toList()` gave):

```kotlin
        val now = clock.now()
        expiredExitsScratch.clear()
        for (te in timeExits.values) {
            if (now >= te.deadline.toEpochMilli()) expiredExitsScratch.add(te)
        }
        for (i in expiredExitsScratch.indices) {
            val te = expiredExitsScratch[i]
            timeExits.remove(te.id)
            handleTimeExitExpiry(te)
        }
```

- [ ] **Step 3: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTimeExitTest" --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): reuse scratch buffer for time-exit sweep"
```

---

### Task 6: Reuse a scratch buffer for the GTD sweep

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (add field; replace 1527–1536)

**Interfaces:**
- Produces: `private val gtdSweepScratch: ArrayList<ManagedOrder>` (filled only on the `!supportsNativeGtd` branch).

- [ ] **Step 1: Add the scratch field**

```kotlin
private val gtdSweepScratch = ArrayList<ManagedOrder>()
```

- [ ] **Step 2: Replace the GTD sweep**

Replace `OrderManager.kt:1527–1536`:

```kotlin
        if (!broker.supportsNativeGtd) {
            val nowMs = clock.now()
            val allLive = liveOrderIds.map { orders[it] ?: error("live order index desync: $it") }
            for (managed in allLive) {
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                val deadline = managed.request.expiresAt ?: continue
                if (nowMs > deadline) cancel(managed.id)
            }
        }
```

with (the branch only runs for PaperBroker/Bybit/Log — on MT5 live it is skipped and this buffer is never touched):

```kotlin
        if (!broker.supportsNativeGtd) {
            val nowMs = clock.now()
            gtdSweepScratch.clear()
            for (id in liveOrderIds) {
                gtdSweepScratch.add(orders[id] ?: error("live order index desync: $id"))
            }
            for (i in gtdSweepScratch.indices) {
                val managed = gtdSweepScratch[i]
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                val deadline = managed.request.expiresAt ?: continue
                if (nowMs > deadline) cancel(managed.id)
            }
        }
```

- [ ] **Step 3: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerGtdSweepTest" --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: all green. Snapshot-then-cancel preserved (`cancel` mutates `liveOrderIds`; filling first avoids concurrent-modification).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): reuse scratch buffer for gtd sweep"
```

---

### Task 7: Reuse a scratch buffer for the stack-deadline sweep

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (add field; replace 1548–1554)

**Interfaces:**
- Consumes: `StackTracker.activeView()` (Task 2).
- Produces: `private val expiredStacksScratch: ArrayList<ActiveStack>`.

- [ ] **Step 1: Add the scratch field**

```kotlin
private val expiredStacksScratch = ArrayList<ActiveStack>()
```

- [ ] **Step 2: Replace the stack sweep**

Replace `OrderManager.kt:1548–1554`:

```kotlin
        val nowEpoch = clock.now()
        for (state in stacks.all()) {
            val deadline = state.deadlineEpochMs ?: continue
            if (nowEpoch < deadline) continue
            cancelStackPending(state.id)
            stacks.terminate(state.id)
        }
```

with (read `activeView()` to collect expired — no mutation in this loop — then cancel + terminate in the second pass):

```kotlin
        val nowEpoch = clock.now()
        expiredStacksScratch.clear()
        for (state in stacks.activeView()) {
            val deadline = state.deadlineEpochMs ?: continue
            if (nowEpoch < deadline) continue
            expiredStacksScratch.add(state)
        }
        for (i in expiredStacksScratch.indices) {
            val state = expiredStacksScratch[i]
            cancelStackPending(state.id)
            stacks.terminate(state.id)
        }
```

- [ ] **Step 3: Run the focused suite**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerStackTest" --tests "com.qkt.app.OrderManager*" --tests "com.qkt.backtest.StackBacktestTest" --tests "com.qkt.app.StackPnlSanityTest"`
Expected: all green. The full decision set is collected before any `terminate`, matching the original snapshot iteration.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): reuse scratch buffer for stack sweep"
```

---

### Task 8: Verify byte-identical output, profile allocation, push

**Files:** none modified.

**Interfaces:**
- Consumes: the complete refactor (Tasks 2–7).

- [ ] **Step 1: Full local build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. This runs the full suite including `com.qkt.parity.BacktestLiveParityTest` and `SweepReplayParityTest` — the byte-identical correctness gate.

- [ ] **Step 2: Byte-identical golden diff (exercises stacks)**

The latch-stack example drives the stack-deadline path. Run it on `dev` and on the branch and diff the JSON report:

```bash
git stash list >/dev/null
git checkout dev   && ./gradlew -q run --args="backtest examples/latch-stack/latch-stack.qkt --json" > /tmp/golden-dev.json
git checkout refactor-trigger-pass-alloc && ./gradlew -q run --args="backtest examples/latch-stack/latch-stack.qkt --json" > /tmp/golden-branch.json
diff /tmp/golden-dev.json /tmp/golden-branch.json && echo "BYTE-IDENTICAL"
```
Expected: `diff` prints nothing, then `BYTE-IDENTICAL`. Any diff is a parity break — stop and find which task introduced it (the per-task commits localize it).

- [ ] **Step 3: Confirm the allocations are gone (JFR)**

Build from source (the `:edge` jlink runtime lacks `jdk.jfr`). Record a flight over the same backtest, before (`dev`) and after (branch), and compare the allocation profile for `evaluateTriggers`:

```bash
./gradlew -q run --args="backtest examples/latch-stack/latch-stack.qkt --json" \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=/tmp/after.jfr,settings=profile"
jfr print --events jdk.ObjectAllocationSample /tmp/after.jfr | grep -c "evaluateTriggers"
```
Expected: the `ArrayList`/iterator allocation frames attributed to `evaluateTriggers` drop versus the `dev` recording; no new allocation or CPU regression elsewhere. Measure CPU-time / hot-method fraction, not wall-clock (the box is contention-bound).

- [ ] **Step 4: Pre-push checklist**

Run: `git log --oneline dev..HEAD` and read every message — all `refactor(execution):` / `test(execution):`, no emoji, no footer. `grep -rEn 'TODO|FIXME|XXX' src/` — no new ones.

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin refactor-trigger-pass-alloc
```
Open a PR into `dev` using the qkt PR template. Phase: refactor (no phase). Risk: Low–medium. Link the spec. State the byte-identical result from Step 2 and the JFR delta from Step 3 in the Tests section. CI runs the full integration suite.

---

## Self-Review

**Spec coverage:**
- Sites 1 (`symbolLive`) → Task 3. Sites 5+6 (trigger filters) → Task 4. Site 3 (time-exit) → Task 5. Site 2 (GTD) → Task 6. Site 4 (`stacks.all()`) → Tasks 2 + 7. ✓ All six sites covered.
- StackTracker `activeView()` → Task 2. ✓
- Invariant 1 (phase order) → preserved in every task; characterization test in Task 1. ✓
- Invariant 2 (lookup count) → Task 3 keeps `symbolLiveScratch` rather than re-iterating. ✓
- Invariant 3 (iteration order) → `LinkedHashSet`→list, noted in Tasks 3/6. ✓
- Invariant 4 (non-reentrancy) → documented on the field comment in Task 3. ✓
- Verification (byte-identical + JFR) → Task 8. ✓

**Placeholder scan:** Task 1 Step 4 is conditional (only if a coverage gap is found) and carries concrete assertions + the harness to mirror — not a placeholder. All other steps have exact code and commands.

**Type consistency:** `symbolLiveScratch`, `triggeredScratch`, `expiredExitsScratch`, `gtdSweepScratch`, `expiredStacksScratch` (`ArrayList<ManagedOrder>` / `ArrayList<OrderRequest.TimeExit>` / `ArrayList<ActiveStack>`); `StackTracker.activeView(): Collection<ActiveStack>`. Names and types are consistent across the tasks that define and consume them.
