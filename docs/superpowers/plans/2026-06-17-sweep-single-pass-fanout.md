# Sweep single-pass fan-out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `qkt sweep` decode and replay the shared market stream once per worker instead of once per grid combo, by fanning each tick out to N isolated per-combo replay engines.

**Architecture:** `ReplayEngine` is already a steppable "shared replay core" (`advanceUntil` pulls one tick at a time via `feed.next()` and calls `pipeline.ingest(tick)`). We extract that per-tick body into a public `ingest(tick)`, then add a `SweepReplay` driver that owns one shared decoded feed and calls `ingest` on N engines (one per combo, each with isolated broker/P&L/risk). `SweepCommand` switches from `BacktestSweep` to `SweepReplay`. A measurement spike (Task 1) gates whether we build at all.

**Tech Stack:** Kotlin, JUnit 5, Gradle, ktlint. Engine code under `src/main/kotlin/com/qkt`, tests under `src/test/kotlin/com/qkt`.

## Global Constraints

- Branch: `refactor-sweep-single-pass-fanout` (already created off `dev`); never commit to `dev`/`testing`/`main` directly.
- Commit messages: Conventional Commits, **subject line only — no body, no footer, no AI attribution**. Types: `feat`/`fix`/`refactor`/`test`/`docs`/`chore`. Scopes for this work: `backtesting` (sweep), `research` (ReplayEngine). Lowercase first word, imperative, no trailing period, ≤70 chars.
- ktlint: max line length 120; run `./gradlew ktlintCheck` before each commit.
- Local test runs use focused Gradle: `./gradlew test --tests "<FQN>"`. The full `./gradlew build` is left to CI (push and let CI verify) — do not block on a full local build.
- **Parity invariant (non-negotiable):** backtest results must stay bit-identical to the current path. No change to the live path (`liveTicks`), the fill model, or any per-combo computation. The Task 2 and Task 3 tests are the acceptance gate.
- `ask` before pushing or opening a PR.

---

### Task 1: Decode-vs-execution measurement spike (GATE)

This task decides whether Tasks 2–4 happen. It writes throwaway instrumentation, runs it on bot2's real cached `.bin` data over a **short** range (the decode/execution *ratio* is range-independent, so a 1–3 month sample gives the same split in minutes instead of hours), and reports the breakdown. **Tasks 2–4 proceed only if shared work (decode + aggregation) is a large enough fraction to justify the build; otherwise stop and report.**

**Files:**
- Create (throwaway): `src/test/kotlin/com/qkt/backtest/sweep/SweepDecodeSpikeTest.kt`

- [ ] **Step 1: Write the spike test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.candles.TimeWindow
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.time.Instant

/**
 * Throwaway: attribute sweep wall-clock to decode vs aggregation vs execution.
 * Run manually on bot2 against real cached .bin data. DELETE before the branch merges.
 * Set the env vars to a representative basket + a short range (e.g. one quarter).
 */
@Disabled("spike: run manually on bot2")
class SweepDecodeSpikeTest {
    private val dataRoot = System.getenv("SPIKE_DATA_ROOT") ?: "/root/projects/qkt-forge/run/data"
    private val symbols = (System.getenv("SPIKE_SYMBOLS") ?: "XAUUSD,XAGUSD,AUDUSD,NZDUSD").split(",")
    private val from = Instant.parse((System.getenv("SPIKE_FROM") ?: "2023-01-01") + "T00:00:00Z")
    private val to = Instant.parse((System.getenv("SPIKE_TO") ?: "2023-04-01") + "T00:00:00Z")

    private fun ms(block: () -> Unit): Long {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun split() {
        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = null) // null fetcher = offline
        val src = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = null)
        val range = TimeRange(from, to)
        val window = TimeWindow.parse("30m")

        // A: pure decode — drain raw ticks for every basket symbol.
        var decodeMs = 0L
        for (s in symbols) decodeMs += ms { var n = 0L; for (t in src.ticks(s, range)) n++ }

        // B: decode + aggregation — drain aggregated bars (aggregateFromTicks re-reads ticks).
        var aggMs = 0L
        for (s in symbols) aggMs += ms { var n = 0L; for (c in src.bars(s, window, range)) n++ }

        println("SPIKE decode_ms=$decodeMs  decode+agg_ms=$aggMs  agg_only_ms=${aggMs - decodeMs}")
        println("SPIKE basket=$symbols range=$from..$to")
    }
}
```

- [ ] **Step 2: Run the decode/aggregation split on bot2**

On bot2, against the real data:
```bash
cd /root/projects/qkt && SPIKE_SYMBOLS="XAUUSD,XAGUSD,AUDUSD,NZDUSD" SPIKE_FROM=2023-01-01 SPIKE_TO=2023-04-01 \
  ./gradlew test --tests "com.qkt.backtest.sweep.SweepDecodeSpikeTest" -Djunit.jupiter.conditions.deactivate='*' --info | grep SPIKE
```
Expected: a line `SPIKE decode_ms=... decode+agg_ms=... agg_only_ms=...`. Record the numbers.

- [ ] **Step 3: Measure full single-combo execution for the same basket/range**

Run a real backtest of a representative strategy over the identical short range and time it:
```bash
cd /root/projects/qkt-forge && time docker run --rm -u 0:0 --entrypoint qkt \
  -v "$PWD/run/data:$PWD/run/data" -w "$PWD" ghcr.io/elitekaycy/qkt:edge \
  backtest <path-to-a-strategy.qkt> --from 2023-01-01 --to 2023-04-01 \
  --data-root "$PWD/run/data" --no-fetch --allow-incomplete --json
```
Record the wall-clock as `full_ms`. Then `execution_ms = full_ms - (decode+agg_ms)`.

- [ ] **Step 4: Decide and report (the gate)**

Compute the shared fraction: `shared = (decode+agg_ms) / full_ms`. A 16-combo sweep goes from `16 × full` to roughly `(decode+agg) + 16 × execution`, i.e. speedup `≈ full / (shared·full/16 ... )` — concretely report the projected 16-combo speedup `= (16 × full) / ((decode+agg) + 16 × (full - (decode+agg)))`.
  - If projected speedup ≥ ~1.5× → **GO**: proceed to Task 2. If `agg_only_ms` is also a large slice, flag that the deeper variant (hoist `candleHub`) is worth it; otherwise baseline (share decode only) is enough.
  - If projected speedup < ~1.5× → **STOP**: write the numbers into the spec's §5 as the recorded outcome, delete the spike file, and report to elitekaycy. Do not build.

- [ ] **Step 5: Commit the recorded outcome (only if STOP) or proceed (if GO)**

If STOP: `git rm` the spike, append the measured numbers to the spec, and:
```bash
./gradlew ktlintCheck
git add docs/superpowers/specs/2026-06-17-sweep-single-pass-fanout-design.md
git commit -m "docs: record sweep fan-out spike outcome (no build)"
```
If GO: leave the spike file uncommitted for now (it is deleted in Task 4, Step 6) and continue.

---

### Task 2: Extract `ReplayEngine.ingest(tick)`

Pure refactor: move the per-tick body of `advanceUntil` into a public method so a driver can push ticks from a shared feed. No behavior change.

**Files:**
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt:254-270`
- Test: `src/test/kotlin/com/qkt/research/ReplayEngineIngestTest.kt` (create)

**Interfaces:**
- Produces: `ReplayEngine.ingest(tick: Tick)` — applies one already-decoded tick to this engine's pipeline (updates clock, counters, runs `pipeline.ingest`). `advanceUntil` now calls it.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.strategy.Strategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReplayEngineIngestTest {
    private fun engine(feed: SequenceTickFeed) =
        ReplayEngine(
            strategies = listOf<Pair<String, Strategy>>(),
            feed = feed,
            cadence = SampleCadence.TICK,
            startingBalance = BigDecimal("10000"),
        )

    @Test
    fun `driving via ingest matches advanceToEnd tick count`() {
        val ticks = (1..5).map { Tick("BACKTEST:BTCUSDT", BigDecimal(100 + it), it.toLong()) }
        val driven = engine(SequenceTickFeed(emptySequence()))
        for (t in ticks) driven.ingest(t)
        assertEquals(5, driven.ticksIngestedForTest())
    }
}
```

(If `ticksIngested` is private with no test accessor, add `internal fun ticksIngestedForTest() = ticksIngested` to `ReplayEngine`; `internal` keeps it out of the public API.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineIngestTest"`
Expected: FAIL — `ingest`/`ticksIngestedForTest` unresolved.

- [ ] **Step 3: Extract the method**

In `ReplayEngine.kt`, replace the loop body in `advanceUntil` (lines 264-267) so the per-tick work lives in a public `ingest`:

```kotlin
/** Apply one already-decoded tick to this engine's pipeline. The driver of a fanned-out
 *  sweep pulls ticks from one shared feed and pushes them here, so the decode happens once. */
fun ingest(tick: Tick) {
    currentTimestamp = tick.timestamp
    ticksIngested++
    clock.time = tick.timestamp
    pipeline.ingest(tick)
}

/** Pull and ingest ticks until [stop] returns true after a tick, or the feed drains. */
fun advanceUntil(stop: () -> Boolean) {
    if (exhausted) return
    while (true) {
        val tick = feed.next()
        if (tick == null) {
            exhausted = true
            feed.close()
            break
        }
        ingest(tick)
        if (stop()) break
    }
}
```

Add the test accessor if needed: `internal fun ticksIngestedForTest() = ticksIngested`.

- [ ] **Step 4: Run tests to verify pass + no regressions**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineIngestTest" --tests "com.qkt.backtest.*" --tests "com.qkt.cli.SweepCommandTest"`
Expected: PASS. The existing batch/sweep tests confirm `runToEnd` is unchanged (it delegates through `advanceToEnd` → `advanceUntil` → `ingest`).

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew ktlintCheck
git add src/main/kotlin/com/qkt/research/ReplayEngine.kt src/test/kotlin/com/qkt/research/ReplayEngineIngestTest.kt
git commit -m "refactor(research): extract ReplayEngine.ingest for shared-feed driving"
```

---

### Task 3: `SweepReplay` driver

One shared decoded feed drives N isolated engines. Driven by a bit-identical parity test (the acceptance gate): `SweepReplay` results must exactly equal the current per-combo `BacktestSweep` path.

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/sweep/SweepReplay.kt`
- Test: `src/test/kotlin/com/qkt/backtest/sweep/SweepReplayParityTest.kt`

**Interfaces:**
- Consumes: `ReplayEngine.ingest(tick)` (Task 2); the existing `BacktestContext.backtest(overrides, range)` and the shared `MarketRequest`/feed wiring it uses.
- Produces: `class SweepReplay(configs: List<Pair<String, C>>, sharedFeed: () -> TickFeed, engineFor: (label: String, config: C) -> ReplayEngine, parallelism: Int)` with `fun run(): SweepResult<C>`. Drop-in for `BacktestSweep` at the `SweepResult` boundary so `SweepCommand` ranking/printing is unchanged.

- [ ] **Step 1: Write the failing parity test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.cli.ParamGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SweepReplayParityTest {
    // Uses the same in-repo strategy + tick fixtures the existing BacktestSweep tests use.
    // Builds a small grid, runs it through BOTH the legacy per-combo path and SweepReplay,
    // and asserts the per-combo BacktestResult metrics are identical for every combo.
    @Test
    fun `SweepReplay is bit-identical to per-combo BacktestSweep`() {
        val fixture = SweepTestFixture.smallGrid()            // (see helper note below)
        val legacy = fixture.runViaBacktestSweep()            // List<SweepRun> ranked-stable by label
        val fanned = fixture.runViaSweepReplay()
        assertEquals(legacy.size, fanned.size)
        for ((a, b) in legacy.sortedBy { it.label }.zip(fanned.sortedBy { it.label })) {
            assertEquals(a.label, b.label)
            assertEquals(a.result.global.tradeCount, b.result.global.tradeCount)
            assertEquals(a.result.global.totalPnL, b.result.global.totalPnL)
            assertEquals(a.result.global.sharpeRatio, b.result.global.sharpeRatio)
            assertEquals(a.result.global.maxDrawdown, b.result.global.maxDrawdown)
            assertEquals(a.result.trades, b.result.trades)    // full tape, not just summary
        }
    }
}
```

Helper note: factor the existing sweep tests' fixture construction (strategy + `List<Tick>` + grid) into `SweepTestFixture` with `runViaBacktestSweep()` and `runViaSweepReplay()`. `runViaBacktestSweep` mirrors today's `BacktestSweep(configs, { _, c -> Backtest(strategies, ticks=fixtureTicks, ...candleWindow).also{}}).run()`. `runViaSweepReplay` shares one feed (`HistoricalTickFeed(fixtureTicks)`) across `engineFor`-built engines.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: FAIL — `SweepReplay` unresolved.

- [ ] **Step 3: Implement `SweepReplay`**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.report.RankMetricScore
import com.qkt.marketdata.TickFeed
import com.qkt.research.ReplayEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a grid sweep by decoding the market stream once per worker and fanning each tick out to
 * one [ReplayEngine] per combo. Each engine owns isolated execution state (broker, P&L, risk);
 * only the decoded tick stream is shared. Bit-identical to running each combo as its own backtest
 * (proven by SweepReplayParityTest) because every engine sees the identical tick order.
 *
 * Combos are partitioned across [parallelism] worker threads; each worker does ONE decode pass
 * (via [sharedFeed]) over its subset, so decode happens `parallelism` times, not `configs.size`.
 */
class SweepReplay<C>(
    private val configs: List<Pair<String, C>>,
    private val sharedFeed: () -> TickFeed,
    private val engineFor: (label: String, config: C) -> ReplayEngine,
    private val parallelism: Int = 1,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) { "config labels must be unique" }
    }

    fun run(): SweepResult<C> {
        val groups = partition(configs, parallelism.coerceAtMost(configs.size))
        if (groups.size == 1) return SweepResult(runGroup(groups[0]))
        val executor = Executors.newFixedThreadPool(groups.size)
        try {
            val futures = groups.map { g -> executor.submit<List<SweepRun<C>>> { runGroup(g) } }
            return SweepResult(futures.flatMap { it.get() })
        } finally {
            executor.shutdown(); executor.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    /** One worker: decode the shared feed once, fan every tick to this group's engines in fixed
     *  order (deterministic), then snapshot each. A combo that throws aborts the sweep (matches
     *  BacktestSweep fail-fast). */
    private fun runGroup(group: List<Pair<String, C>>): List<SweepRun<C>> {
        val engines = group.map { (label, cfg) -> Triple(label, cfg, engineFor(label, cfg)) }
        sharedFeed().use { feed ->
            while (true) {
                val tick = feed.next() ?: break
                for ((_, _, e) in engines) e.ingest(tick)
            }
        }
        return engines.map { (label, cfg, e) -> SweepRun(label, cfg, e.snapshot()) }
    }

    private fun <T> partition(items: List<T>, n: Int): List<List<T>> {
        val out = MutableList(n) { mutableListOf<T>() }
        items.forEachIndexed { i, it -> out[i % n].add(it) }   // round-robin keeps groups balanced
        return out
    }
}
```

(`RankMetricScore` import is illustrative; keep only imports the file uses — `ktlintCheck` flags unused imports. `TickFeed` is `AutoCloseable` via `close()`; if not, drop `.use {}` and call `feed.close()` in a `finally`.)

- [ ] **Step 4: Run the parity test to verify it passes**

Run: `./gradlew test --tests "com.qkt.backtest.sweep.SweepReplayParityTest"`
Expected: PASS — every combo's metrics and full trade tape match the legacy path.

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew ktlintCheck
git add src/main/kotlin/com/qkt/backtest/sweep/SweepReplay.kt src/test/kotlin/com/qkt/backtest/sweep/SweepReplayParityTest.kt
git commit -m "feat(backtesting): add SweepReplay single-pass fan-out sweep"
```

---

### Task 4: Rewire `SweepCommand` to `SweepReplay`

Switch the CLI sweep path to the fan-out driver. Build one shared feed + a per-combo `ReplayEngine` factory from `BacktestContext`. Keep ranking, JSON, table output, and fail-fast unchanged.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/SweepCommand.kt:59-69`
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt` (add a shared-feed + engine factory accessor)
- Test: `src/test/kotlin/com/qkt/cli/SweepCommandTest.kt` (existing — must stay green)

**Interfaces:**
- Consumes: `SweepReplay` (Task 3); `BacktestContext`.
- Produces: `BacktestContext.sweepEngines(): Pair<() -> TickFeed, (overrides: Map<String,String>) -> ReplayEngine>` — a shared-feed builder + per-combo engine factory that share one decoded source pass. (Refactor `backtest()` so the engine-construction wiring is reused, not duplicated.)

- [ ] **Step 1: Add the shared-feed + engine factory to `BacktestContext`**

Extract the wiring currently inside `backtest()` (calendar resolution + `Backtest.fromSource` engine construction) so it can build a feed once and N engines that do not each own a feed. Concretely, expose:

```kotlin
/** For fan-out sweeps: one shared decoded feed plus a factory of feed-less engines that the
 *  caller drives via ReplayEngine.ingest. Engines share the single decode this returns. */
fun sweepEngines(): Pair<() -> com.qkt.marketdata.TickFeed,
                         (Map<String, String>) -> com.qkt.research.ReplayEngine> {
    val range = TimeRange(from, to)
    val calendar = symbols.firstOrNull()
        ?.let { defaultCalendars().calendarFor(it.substringAfter(':')) }
        ?: com.qkt.common.TradingCalendar.crypto()
    val feedBuilder = {
        val ls = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = barStore)
        mergedFeed(ls, symbols, range, candleWindow)   // same merge Backtest.fromSource uses
    }
    val engineFor = { overrides: Map<String, String> ->
        val strategy = AstCompiler().compile(ast, overrides)
        ReplayEngine(
            strategies = listOf(ast.name to strategy),
            haltRules = haltRules,
            feed = EmptyTickFeed,          // driver pushes ticks via ingest; engine never pulls
            candleWindow = candleWindow,
            initialTimestamp = from.toEpochMilli(),
            source = NullMarketSource,     // baseline: no per-engine warmup decode; see note
            calendar = calendar,
            symbols = symbols,
            startingBalance = startingBalance,
            instruments = instruments,
            brokerKind = brokerKind,
        )
    }
    return feedBuilder to engineFor
}
```

Note: if the spike (Task 1) showed strategies rely on warmup, pass the real `source`/`warmupSpec` here so warmup parity holds; the parity test in Task 3 must use the same warmup config as the legacy path. Reuse the existing per-symbol merge helper from `Backtest.fromSource` rather than duplicating it (extract it to a shared internal function if it is currently private to `Backtest`).

- [ ] **Step 2: Rewire `SweepCommand.run` to use `SweepReplay`**

Replace the `BacktestSweep(...)` block (lines 59-69) with:

```kotlin
val (sharedFeed, engineFor) = ctx.sweepEngines()
val ranked: List<SweepRun<ParamGrid.Combo>> =
    try {
        SweepReplay(
            configs = combos.map { it.label to it },
            sharedFeed = sharedFeed,
            engineFor = { _, combo -> engineFor(combo.overrides) },
            parallelism = parallelism,
        ).run().rankedBy { rank.score(it) }
    } catch (e: IllegalArgumentException) {
        System.err.println("qkt: error: ${e.message}")
        return ExitCodes.USER_ERROR
    }
```

- [ ] **Step 3: Run the CLI sweep tests**

Run: `./gradlew test --tests "com.qkt.cli.SweepCommandTest" --tests "com.qkt.backtest.sweep.*"`
Expected: PASS — JSON shape, ranking, validation, and fail-fast behaviors unchanged; parity test still green.

- [ ] **Step 4: Verify end-to-end on bot2 against real data**

Run one real sweep through the new path and confirm identical ranked output vs the pre-change image on a short range:
```bash
cd /root/projects/qkt-forge && docker run --rm -u 0:0 --entrypoint qkt \
  -v "$PWD/run/data:$PWD/run/data" -w "$PWD" ghcr.io/elitekaycy/qkt:edge \
  sweep <strategy.qkt> --from 2023-01-01 --to 2023-04-01 --data-root "$PWD/run/data" \
  --param <axis>=<v1>,<v2> --no-fetch --allow-incomplete --parallelism 3 --json
```
Expected: same ranked combos/metrics as the legacy build, faster wall-clock. (This validates after the image is rebuilt from the branch; until then, run via local `./gradlew run` against a small local fixture.)

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew ktlintCheck
git add src/main/kotlin/com/qkt/cli/SweepCommand.kt src/main/kotlin/com/qkt/cli/BacktestContext.kt
git commit -m "refactor(backtesting): drive qkt sweep through SweepReplay fan-out"
```

- [ ] **Step 6: Delete the spike and push for CI**

```bash
git rm src/test/kotlin/com/qkt/backtest/sweep/SweepDecodeSpikeTest.kt
git commit -m "chore(backtesting): remove sweep decode spike"
```
Then (after `ask`) push the branch and open a PR into `dev`; let CI run the full build + integration suite.

---

## Self-Review

**Spec coverage:**
- §3/§4 decode-once fan-out → Tasks 2 (`ingest`) + 3 (`SweepReplay`) + 4 (rewire). ✓
- §5 spike gate → Task 1, with explicit GO/STOP. ✓
- §6 concurrency/memory (partition across `parallelism`, decode passes = parallelism) → `SweepReplay.partition`/`runGroup`. ✓
- §7 parity gate (bit-identical, fail-fast, existing suite green) → Task 3 parity test + Task 2/4 regression runs. ✓
- §8 non-goals (no live/fill change, no cross-fold dedup, no qkt-forge change) → respected; SweepCommand-only on the engine side. ✓
- §9 risks (ingest order, fail-fast, warmup) → Task 2 regression run, `runGroup` fail-fast, Task 4 Step 1 warmup note. ✓

**Placeholder scan:** Code shown for every code step; the one deliberately deferred decision (baseline vs deeper sharing, and warmup `source` wiring) is gated on the spike and called out explicitly in Task 1 Step 4 and Task 4 Step 1 — not a hidden TODO.

**Type consistency:** `ingest(tick: Tick)` (Task 2) is called by `SweepReplay.runGroup` (Task 3) and reached via `engineFor` (Task 4). `SweepReplay`/`SweepResult`/`SweepRun` names match the existing `backtest/sweep` package. `sweepEngines()` return type matches `SweepReplay`'s `sharedFeed`/`engineFor` params.
