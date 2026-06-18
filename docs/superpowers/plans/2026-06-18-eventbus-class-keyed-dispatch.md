# EventBus class-keyed dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the per-publish Kotlin-reflection type retrieval from `EventBus` by keying subscribers on `Class` and dispatching off `stamped.javaClass`.

**Architecture:** Single-file change in `EventBus.kt`. The subscriber map is re-keyed from `KClass<out Event>` to `Class<out Event>`; `subscribe`/`subscribeFirst` register under `T::class.java`; `publish` looks up `subscribers[stamped.javaClass]`. Dispatch order, `subscribeFirst` head-insertion, per-subscriber exception isolation, the off-thread sink reroute, and event stamping are all unchanged. This is a behavior-preserving refactor: the gate is the existing `EventBus` suite staying green plus a bit-identical backtest.

**Tech Stack:** Kotlin, JUnit 5, Gradle, ktlint.

## Global Constraints

- Branch: `refactor-eventbus-dispatch` (already created off `dev`); never commit to `dev`/`testing`/`main` directly.
- Commit messages: Conventional Commits, **subject line only — no body, no footer, no AI attribution**. Scope `engine` or none.
- ktlint: max line length 120; run `./gradlew ktlintCheck` before each commit; remove now-unused imports (ktlint fails on them).
- Local focused tests: `./gradlew test --tests "<FQN>"`. Full build is left to CI.
- **Parity invariant (non-negotiable):** the change must be bit-identical. No change to event stamping, dispatch order, `subscribeFirst` ordering, exception isolation, or the live engine-loop binding. The existing `EventBusTest`/`EventBusEngineLoopTest` suites and a byte-identical backtest are the acceptance gate.
- All subscriptions in the codebase are to concrete leaf event types (verified) — no sealed-parent subscriptions exist, so concrete-class dispatch is unchanged in meaning.
- `ask` before pushing or opening a PR.

---

### Task 1: Re-key EventBus to `Class`-keyed dispatch

**Files:**
- Modify: `src/main/kotlin/com/qkt/bus/EventBus.kt` (the `subscribers` field decl ~line 37-38, `subscribe`/`subscribeFirst` ~line 83-103, `publish` lookup ~line 135, and the now-unused `import kotlin.reflect.KClass` ~line 15)
- Gate (no edit): `src/test/kotlin/com/qkt/bus/EventBusTest.kt`, `src/test/kotlin/com/qkt/bus/EventBusEngineLoopTest.kt`

This is a behavior-preserving refactor, so there is no new failing test — `EventBusTest` already pins the dispatch contract (registration order, `subscribeFirst` ahead-of, per-type isolation, exception propagation + first-failure rethrow, stamping). It must pass unchanged after the edit. A new test would be vacuous; do not add one.

- [ ] **Step 1: Run the existing EventBus suite to confirm the green baseline**

Run: `./gradlew test --tests "com.qkt.bus.*"`
Expected: PASS (baseline before the change).

- [ ] **Step 2: Re-key the subscriber map**

In `EventBus.kt`, change the field type from `KClass` to `Class`:

```kotlin
@PublishedApi
internal val subscribers = mutableMapOf<Class<out Event>, MutableList<(Event) -> Unit>>()
```

- [ ] **Step 3: Register under the concrete `Class` in `subscribe` / `subscribeFirst`**

```kotlin
inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    subscribers
        .getOrPut(T::class.java) { mutableListOf() }
        .add { event -> handler(event as T) }
}

inline fun <reified T : Event> subscribeFirst(noinline handler: (T) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    subscribers
        .getOrPut(T::class.java) { mutableListOf() }
        .add(0) { event -> handler(event as T) }
}
```

(Only the `getOrPut(T::class)` → `getOrPut(T::class.java)` changes; keep the KDoc and the `add`/`add(0)` bodies.)

- [ ] **Step 4: Dispatch off `javaClass` in `publish`**

Change the lookup line in `publish`:

```kotlin
val handlers = subscribers[stamped.javaClass] ?: return
```

Leave the rest of `publish` exactly as-is (the off-thread `sink` reroute, `stamp(event)`, the guarded `log.trace`, the index loop with try/catch isolation, and the first-failure rethrow).

- [ ] **Step 5: Remove the now-unused KClass import**

Delete `import kotlin.reflect.KClass` (line ~15). Nothing else references `KClass` after Steps 2-4.

- [ ] **Step 6: Run the EventBus suite + the bit-identical backtest gate**

Run: `./gradlew test --tests "com.qkt.bus.*" --tests "com.qkt.backtest.*"`
Expected: PASS — `EventBusTest`/`EventBusEngineLoopTest` green (dispatch semantics preserved) and the backtest determinism/e2e suites green (bit-identical).

- [ ] **Step 7: ktlint**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL (in particular, no unused-import failure for `KClass`).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/bus/EventBus.kt
git commit -m "refactor(engine): key EventBus dispatch on Class, not KClass"
```

---

### Task 2: Ship + verify on `:edge`, re-profile

**Files:** none (deploy + verification).

- [ ] **Step 1: Push + open PR to `dev`**

```bash
git push -u origin refactor-eventbus-dispatch
gh pr create --base dev --head refactor-eventbus-dispatch --title "refactor(engine): key EventBus dispatch on Class, not KClass" --body "<summary + parity note + spec link>"
```

- [ ] **Step 2: Pass CI, merge to `dev`**

Poll `gh pr checks <n>` until `build` + `build-windows` pass (the `gh pr checks --watch`/`gh run watch` commands exit early while pending — poll `grep -c pending`==0 instead), then `gh pr merge <n> --merge`. The push to `dev` auto-fast-forwards `testing`, whose push rebuilds `:edge` (poll the `testing` `docker` run's `--json status`==completed).

- [ ] **Step 3: Re-profile on bot2 (build-from-source — `:edge` jlink runtime lacks `jdk.jfr`)**

On bot2: `cd /root/projects/qkt && git checkout dev && git pull && ./gradlew installDist`, then run a JFR-profiled s#31 backtest (`JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=settings=profile,filename=/root/prof_eb.jfr,dumponexit=true" build/install/qkt/bin/qkt backtest run/strategies/risk-bloc-confirmed-nzd-breakout-continuation-31.qkt --from 2023-01-02 --to 2023-02-01 --data-root run/data --no-fetch --allow-incomplete --json`) against `/root/projects/qkt-forge/run/data`.
Expected: `jfr view hot-methods /root/prof_eb.jfr` shows the `::class`/reflection retrieval gone from `EventBus.publish`. If a residual `Class`-map lookup is still material, log it as the trigger to consider Approach B in a follow-up — do not act now.

- [ ] **Step 4: Confirm byte-identical on the deployed `:edge`**

On bot2: `docker pull ghcr.io/elitekaycy/qkt:edge`; run the s#31 sweep (1-week, `--parallelism 3`, `--no-fetch --allow-incomplete --rank sharpe --json`) on the new `:edge` and on the pre-change image (cached by digest), and `diff` the JSON.
Expected: `BYTE-IDENTICAL`. Confirm the forge loop is advancing on the new digest. Restore bot2's `/root/projects/qkt` checkout to `dev` and clean `/root/prof_eb.*`.

---

## Self-Review

**Spec coverage:**
- §3 design (re-key map, `T::class.java`, `stamped.javaClass`, keep everything else) → Task 1 Steps 2-5. ✓
- §4 parity (concrete-identity, order, isolation) → Task 1 Steps 1/6 (existing suite + backtest gate). ✓
- §5 non-goals (no symbol-map / OrderManager / typeId-B / stamping change) → not touched; Task 2 Step 3 explicitly defers B. ✓
- §6 testing (EventBus suite, bit-identical backtest, ktlint, JFR re-profile) → Task 1 Steps 6-7 + Task 2 Steps 3-4. ✓

**Placeholder scan:** code shown for every code step; the PR body summary is the only free-text and is a standard PR description, not a deferred decision.

**Type consistency:** `subscribers: MutableMap<Class<out Event>, MutableList<(Event) -> Unit>>` is used consistently — `getOrPut(T::class.java)` (Class key) in Task 1 Step 3 and `subscribers[stamped.javaClass]` (Class key) in Step 4 match the field type in Step 2.
