# Backtest decode-sharing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop re-decoding the same tick data on every run — share one decode across a gate's many backtests (fan-out) and read decoded longs straight from the shared OS page cache (mmap) — without changing any backtest result.

**Architecture:** `SweepReplay` already decodes a tick stream once and fans each tick to N isolated `ReplayEngine`s, bit-identical to N standalone backtests. WS1 widens *what* can differ per engine (today only params; add broker / instruments / starting-balance / a per-scenario strategy variant) so qkt-forge's G3 (param configs) and G6 (commission + sizing scenarios) collapse from N `docker run`s into one shared-decode sweep. WS2 changes `BinaryTickFeed` to read longs on demand from a memory-mapped file instead of copying the whole file into a heap `byte[]` then a `long[]`. WS3 (post-deploy) points forge at the new `--scenarios` surface. A JFR measurement then gates whether a Phase-2 daemon is worth building.

**Tech Stack:** Kotlin, JDK 21 toolchain, JUnit 5 + AssertJ, Gradle, ktlint. qkt-forge is Python (uv, pytest) on bot2.

**Spec:** `docs/superpowers/specs/2026-06-22-backtest-decode-sharing-design.md`.

## Global Constraints

- **Toolchain is JDK 21** (`build.gradle.kts`: `jvmToolchain(21)`; CI uses temurin 21). Not 23.
- **Backtest=Live byte-identical parity is sacred.** Every change must produce identical trade lists *and* metrics to the current decode path. This is the acceptance bar for every task.
- Tests: JUnit 5 (Jupiter) + AssertJ. Money/`BigDecimal` equality uses `isEqualByComparingTo` (scale-agnostic); counts use `isEqualTo`. Run one class: `./gradlew test --tests 'FQCN'`. Default `test` excludes tags `e2e,e2e-live,dockerSmoke,stress,soak`; the tests here are untagged and run by default.
- **A scenario MUST NOT change the symbol set or the timeframes.** The single shared feed is keyed to symbols + time window + store + candleWindow. The scenario loader asserts this and hard-errors on mismatch.
- ktlint enforced; **120-char line limit**. `ktlintFormat` wraps argument lists but not long comma-joined `when` lines — wrap those by hand.
- Commits: conventional, **subject line only, no body, no AI footer / Co-Authored-By / emoji**. Never `git add -A` without a prior `git status`. **Ask permission before committing.**
- qkt PRs target **`dev`**. Push and let CI (`check.yml`) verify; do not block on a full local `./gradlew build` (~6.5 min).

---

## Task 1 (WS2): Memory-map the binary tick store

Behavior-preserving perf refactor. The existing `BinaryTickRoundTripTest` is the parity guard — it must stay green before *and* after. We first strengthen it (more columns, more ticks, an explicit little-endian check), confirm it passes against the current `readAllBytes` implementation, then switch the implementation to an on-demand memory-mapped read and confirm it still passes.

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickRoundTripTest.kt`

**Interfaces:**
- Consumes: `BinaryTickFormat` (`readHeader`, `COL_*`, `COL_COUNT`, `isPresent`, `NULL_SENTINEL`, `Header.{tickCount,scale,symbol,presenceFlags}`), `BinaryTickWriter().write(path, symbol, ticks)`, `TickAssembler.assemble(...)`.
- Produces: `BinaryTickFeed(path)` with unchanged public surface (`next(): Tick?`, `close()`), byte-identical output.

- [ ] **Step 1: Strengthen the parity guard (green against current impl).** Add to `BinaryTickRoundTripTest.kt` a test with all six columns populated, interleaved nulls, and enough ticks to span more than one mapped page, asserting an exact round-trip.

```kotlin
    @Test
    fun `all columns and nulls round-trip exactly across many ticks`(
        @TempDir dir: Path,
    ) {
        val ticks =
            (0 until 5000).map { i ->
                TickAssembler.assemble(
                    "EURUSD",
                    1_712_000_000_000L + i,
                    if (i % 7 == 0) null else bd("1.1${(1000 + i % 900)}0000"),
                    if (i % 5 == 0) null else bd("${i % 50}.00000000"),
                    bd("1.10${(100 + i % 800)}0000"),
                    bd("1.10${(200 + i % 800)}0000"),
                    if (i % 3 == 0) null else bd("0.0001${i % 9}000"),
                    if (i % 4 == 0) null else bd("0.0002${i % 9}000"),
                    { "t:$i" },
                )
            }
        val file = dir.resolve("2024-02-01.bin")
        BinaryTickWriter().write(file, "EURUSD", ticks)
        assertEquals(ticks, drain(BinaryTickFeed(file)))
    }
```

- [ ] **Step 2: Run it against the current implementation.**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickRoundTripTest'`
Expected: PASS (proves the strengthened guard is correct before the refactor).

- [ ] **Step 3: Switch `BinaryTickFeed` to an on-demand memory-mapped read.** Replace the `init` block (which did `Files.readAllBytes` + per-column `LongArray` fill) with a mapped buffer + precomputed per-column int offsets, and read each `long` on demand in `decode()`. Keep `LITTLE_ENDIAN`. Add `close()` to drop references.

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Streams [Tick]s from a binary day-file ([BinaryTickFormat]) by memory-mapping the file and reading
 * each value's `long` straight from the mapped buffer with `BigDecimal.valueOf(stored, scale)` (no
 * string parsing, no intermediate `byte[]`/`long[]` copy), routed through [TickAssembler] — so it
 * yields the exact same `Tick` sequence as [CsvTickFeed] over the same data. Mapping the file lets
 * several processes share the same OS page-cache pages; the per-tick `BigDecimal` is still allocated
 * here. Enforces the monotonic-timestamp contract and fails loud on a corrupt/truncated file.
 */
class BinaryTickFeed(
    private val path: Path,
) : TickFeed {
    private val header: BinaryTickFormat.Header
    private var buf: MappedByteBuffer?
    private val tsBase: Int

    // Byte offset of each column's int64 block, indexed by column id; -1 = column absent.
    private val colBase: IntArray
    private var index: Int = 0
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val mapped =
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            }
        header = BinaryTickFormat.readHeader(mapped)
        val n = header.tickCount
        tsBase = mapped.position() // readHeader leaves position at the timestamps block
        val bases = IntArray(BinaryTickFormat.COL_COUNT) { -1 }
        var slot = 0 // present columns precede absent ones in id order; timestamps occupy slot 0
        for (col in 0 until BinaryTickFormat.COL_COUNT) {
            if (BinaryTickFormat.isPresent(header.presenceFlags, col)) {
                bases[col] = tsBase + (1 + slot) * n * Long.SIZE_BYTES
                slot++
            }
        }
        colBase = bases
        buf = mapped
    }

    override fun next(): Tick? {
        val b = buf ?: return null
        if (index >= header.tickCount) return null
        val i = index++
        val ts = b.getLong(tsBase + i * Long.SIZE_BYTES)
        check(ts >= lastTimestamp) {
            "$path:${i + 1}: non-decreasing timestamps required (got $ts, last $lastTimestamp)"
        }
        lastTimestamp = ts
        return TickAssembler.assemble(
            symbol = header.symbol,
            timestamp = ts,
            price = decode(BinaryTickFormat.COL_PRICE, i),
            volume = decode(BinaryTickFormat.COL_VOLUME, i),
            bid = decode(BinaryTickFormat.COL_BID, i),
            ask = decode(BinaryTickFormat.COL_ASK, i),
            bidVolume = decode(BinaryTickFormat.COL_BID_VOLUME, i),
            askVolume = decode(BinaryTickFormat.COL_ASK_VOLUME, i),
            location = { "$path:${i + 1}" },
        )
    }

    override fun close() {
        buf = null // release the mapping reference; the FileChannel was already closed after map()
    }

    private fun decode(
        col: Int,
        i: Int,
    ): BigDecimal? {
        val base = colBase[col]
        if (base < 0) return null
        val v = buf!!.getLong(base + i * Long.SIZE_BYTES)
        return if (v == BinaryTickFormat.NULL_SENTINEL) null else BigDecimal.valueOf(v, header.scale)
    }
}
```

Note: this assumes `BinaryTickWriter` writes present columns in ascending column-id order (confirmed: timestamps block, then each present column ascending). If `readHeader` does not leave the buffer positioned at the timestamps block, set `tsBase` from the known header size (`28 + symLenBytes`) instead — verify against `BinaryTickFormat.readHeader` while implementing.

- [ ] **Step 4: Run the full round-trip suite (parity guard, must stay green).**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickRoundTripTest'`
Expected: PASS (all tests, including the empty-file and non-monotonic cases, and the new many-tick case).

- [ ] **Step 5: Commit** (after asking permission).

```bash
git add src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt src/test/kotlin/com/qkt/marketdata/BinaryTickRoundTripTest.kt
git commit -m "perf(marketdata): memory-map binary tick reads, drop per-file byte[]/long[] copy"
```

---

## Task 2 (WS1a): Per-scenario overrides in `BacktestContext.backtest()`

Let one `BacktestContext` build a backtest that varies broker / instruments / starting-balance / strategy-AST per call, each defaulting to today's value, sharing the same data window. Halt rules are currently derived once in `build()` from the starting balance; move the *inputs* onto the context and derive in `backtest()` so an overridden balance re-derives consistently (and the default call reproduces today's rules exactly).

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt`
- Test: `src/test/kotlin/com/qkt/cli/BacktestContextScenarioParityTest.kt` (create)

**Interfaces:**
- Consumes: `Backtest.fromStore(...)`, `BrokerKind`, `InstrumentRegistry`, `StrategyAst` (`.streams[].qktSymbol`), `HaltRules.standard(maxDailyLoss, maxDrawdownPct, maxDailyDrawdownPct, totalDdBasis, startingBalance)`, `Config`.
- Produces: `BacktestContext.backtest(overrides, range, ast, brokerKind, instruments, startingBalance)` — all but `overrides` defaulting to the context's value; byte-identical to a standalone `Backtest.fromStore` built with the same effective values.

- [ ] **Step 1: Write the parity test (the acceptance bar).** A scenario-style `backtest(...)` with an overridden starting balance and broker must equal a standalone `Backtest.fromStore` built with those same values. Build the context via `BacktestContext.build` against a tiny on-disk `.bin` store (mirror how existing `BacktestContext`/sweep tests seed data — reuse their `@TempDir` store fixture helper; if none is shared, seed two days of ticks with `BinaryTickWriter` and a manifest as the existing store tests do).

```kotlin
package com.qkt.cli

import com.qkt.backtest.BrokerKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestContextScenarioParityTest {
    @Test
    fun `backtest with overridden balance and broker matches a standalone build`() {
        val ctx = newContextOverTwoDays() // @TempDir-backed store + a simple bracketed strategy
        val viaScenario =
            ctx
                .backtest(
                    overrides = emptyMap(),
                    startingBalance = java.math.BigDecimal("25000"),
                    brokerKind = BrokerKind.MT5_SIM,
                ).run()
        val standalone = ctx.standaloneForTest(startingBalance = "25000", broker = BrokerKind.MT5_SIM).run()

        assertThat(viaScenario.global.tradeCount).isEqualTo(standalone.global.tradeCount)
        assertThat(viaScenario.global.totalPnL).isEqualByComparingTo(standalone.global.totalPnL)
        assertThat(viaScenario.global.maxDrawdown).isEqualByComparingTo(standalone.global.maxDrawdown)
        assertThat(viaScenario.global.maxDailyDrawdown).isEqualByComparingTo(standalone.global.maxDailyDrawdown)
        assertThat(viaScenario.trades).isEqualTo(standalone.trades)
    }
}
```

(`standaloneForTest` is a thin test helper that calls `Backtest.fromStore` with the same `store/request/candleWindow/instruments/calendar` the context uses but the explicit balance+broker, plus `HaltRules.standard(...)` re-derived from that balance — i.e. the "old" path, proving the new `backtest()` matches it.)

- [ ] **Step 2: Run it — expect a compile failure / FAIL** (the new `backtest()` parameters don't exist yet).

Run: `./gradlew test --tests 'com.qkt.cli.BacktestContextScenarioParityTest'`
Expected: FAIL (unresolved `startingBalance`/`brokerKind` named args on `backtest`, or unresolved `standaloneForTest`).

- [ ] **Step 3: Implement the override parameters + halt-rule derivation.** Replace the stored `haltRules` field with the halt inputs, and widen `backtest(...)`.

In the constructor, replace `private val haltRules: List<com.qkt.risk.HaltRule>` with the inputs:

```kotlin
    private val haltConfig: HaltConfig,
```
where (companion or top-level):
```kotlin
    data class HaltConfig(
        val maxDailyLoss: java.math.BigDecimal,
        val maxDrawdownPct: java.math.BigDecimal?,
        val maxDailyDrawdownPct: java.math.BigDecimal?,
        val totalDdBasis: com.qkt.risk.DrawdownBasis,
    )
```

Widen `backtest`:

```kotlin
    fun backtest(
        overrides: Map<String, String>,
        range: TimeRange = TimeRange(from, to),
        ast: StrategyAst = this.ast,
        brokerKind: BrokerKind = this.brokerKind,
        instruments: InstrumentRegistry = this.instruments,
        startingBalance: BigDecimal = this.startingBalance,
    ): Backtest {
        require(ast.streams.map { it.qktSymbol }.distinct().toSet() == symbols.toSet()) {
            "scenario strategy declares streams ${ast.streams.map { it.qktSymbol }} != context symbols $symbols; " +
                "the shared feed is keyed to the symbol set"
        }
        val strategy = AstCompiler().compile(ast, overrides)
        val calendar =
            symbols.firstOrNull()?.let { defaultCalendars().calendarFor(it.substringAfter(':')) }
                ?: com.qkt.common.TradingCalendar.crypto()
        val haltRules =
            com.qkt.risk.HaltRules.standard(
                maxDailyLoss = haltConfig.maxDailyLoss,
                maxDrawdownPct = haltConfig.maxDrawdownPct,
                maxDailyDrawdownPct = haltConfig.maxDailyDrawdownPct,
                totalDdBasis = haltConfig.totalDdBasis,
                startingBalance = startingBalance,
            )
        return Backtest.fromStore(
            strategies = listOf(ast.name to strategy),
            haltRules = haltRules,
            calendar = calendar,
            store = store,
            request = MarketRequest(symbols = symbols, from = range.from, to = range.to),
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            instruments = instruments,
            barStore = barStore,
            brokerKind = brokerKind,
        )
    }
```

In `build(...)`, replace the `haltRules = HaltRules.standard(...)` block and the `haltRules = haltRules` constructor arg with:

```kotlin
            val cfg = Config.load(Paths.get(args.option("config") ?: "./qkt.config.yaml"))
            val haltConfig =
                HaltConfig(
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                )
```
and pass `haltConfig = haltConfig` to the `BacktestContext(...)` constructor. Add the test helper `standaloneForTest(...)` as an `internal fun` on `BacktestContext` (or in the test) that re-derives halt rules and calls `Backtest.fromStore` directly with the explicit balance/broker.

- [ ] **Step 4: Run the parity test.**

Run: `./gradlew test --tests 'com.qkt.cli.BacktestContextScenarioParityTest'`
Expected: PASS.

- [ ] **Step 5: Run the existing backtest/sweep suites (default path unchanged).**

Run: `./gradlew test --tests 'com.qkt.backtest.*' --tests 'com.qkt.cli.*'`
Expected: PASS (halt-rule move is byte-identical for the default balance).

- [ ] **Step 6: Commit** (after asking permission).

```bash
git add src/main/kotlin/com/qkt/cli/BacktestContext.kt src/test/kotlin/com/qkt/cli/BacktestContextScenarioParityTest.kt
git commit -m "feat(backtest): per-scenario broker/instruments/balance/strategy overrides in BacktestContext"
```

---

## Task 3 (WS1b): `ScenarioSpec` + `scenarioEngines()` + `qkt sweep --scenarios`

Introduce the per-engine scenario type, route the existing `--param` grid through it (no behavior change), and add a `--scenarios <file.yaml>` surface that runs arbitrary scenarios sharing one decode.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/ScenarioSpec.kt`
- Create: `src/main/kotlin/com/qkt/cli/ScenarioFile.kt` (YAML loader)
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt` (add `scenarioEngines()`)
- Modify: `src/main/kotlin/com/qkt/cli/SweepCommand.kt`
- Test: `src/test/kotlin/com/qkt/cli/ScenarioSweepParityTest.kt` (create)

**Interfaces:**
- Consumes: `Task 2` `backtest(...)` overrides; `SweepReplay<C>`; `Backtest.detachFeed()`; `SequenceTickFeed`; the YAML parser used by `YamlInstrumentRegistry` (verify the lib while implementing — reuse it, do not add a new dependency).
- Produces:
  - `data class ScenarioSpec(label, params: Map<String,String>, ast: StrategyAst?, brokerKind: BrokerKind?, instruments: InstrumentRegistry?, startingBalance: BigDecimal?)`
  - `BacktestContext.scenarioEngines(): Pair<() -> TickFeed, (ScenarioSpec) -> ReplayEngine>`
  - `ScenarioFile.load(path, defaultAst, dataRoot): List<ScenarioSpec>`

- [ ] **Step 1: Write the scenario-sweep parity test.** A 3-scenario sweep that varies starting balance must, per scenario, equal the standalone backtest for that balance — and the scenarios must genuinely differ.

```kotlin
package com.qkt.cli

import com.qkt.backtest.sweep.SweepReplay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScenarioSweepParityTest {
    @Test
    fun `scenario sweep varying balance is bit-identical to standalone backtests`() {
        val ctx = newContextOverTwoDays()
        val scenarios =
            listOf(
                ScenarioSpec(label = "b1k", startingBalance = java.math.BigDecimal("1000")),
                ScenarioSpec(label = "b10k", startingBalance = java.math.BigDecimal("10000")),
                ScenarioSpec(label = "b200k", startingBalance = java.math.BigDecimal("200000")),
            )
        val (sharedFeed, engineFor) = ctx.scenarioEngines()
        val fan =
            SweepReplay(
                configs = scenarios.map { it.label to it },
                sharedFeed = sharedFeed,
                engineFor = { _, s -> engineFor(s) },
                parallelism = 1,
            ).run().runs

        for (s in scenarios) {
            val standalone = ctx.backtest(emptyMap(), startingBalance = s.startingBalance!!).run()
            val combo = fan.first { it.label == s.label }.result
            assertThat(combo.global.totalPnL).isEqualByComparingTo(standalone.global.totalPnL)
            assertThat(combo.global.maxDailyDrawdown).isEqualByComparingTo(standalone.global.maxDailyDrawdown)
            assertThat(combo.trades).isEqualTo(standalone.trades)
        }
        assertThat(fan.map { it.result.global.totalPnL.toPlainString() }.distinct().size).isGreaterThan(1)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`ScenarioSpec`/`scenarioEngines` do not exist).

Run: `./gradlew test --tests 'com.qkt.cli.ScenarioSweepParityTest'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Create `ScenarioSpec`.**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.dsl.ast.StrategyAst
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * One backtest configuration in a fan-out sweep. Everything here may differ between the engines that
 * share a single decoded feed; null means "use the context default". A scenario may NOT change the
 * symbol set or timeframes — the shared feed is keyed to those (enforced by `BacktestContext.backtest`).
 */
data class ScenarioSpec(
    val label: String,
    val params: Map<String, String> = emptyMap(),
    val ast: StrategyAst? = null,
    val brokerKind: BrokerKind? = null,
    val instruments: InstrumentRegistry? = null,
    val startingBalance: BigDecimal? = null,
)
```

- [ ] **Step 4: Add `scenarioEngines()` to `BacktestContext`** (replacing `sweepEngines()`; the `--param` path will lower to scenarios in Task 3 Step 6).

```kotlin
    fun scenarioEngines(): Pair<() -> TickFeed, (ScenarioSpec) -> ReplayEngine> {
        val sharedFeed = { backtest(emptyMap()).detachFeed() }
        val engineFor = { s: ScenarioSpec ->
            backtest(
                overrides = s.params,
                ast = s.ast ?: this.ast,
                brokerKind = s.brokerKind ?: this.brokerKind,
                instruments = s.instruments ?: this.instruments,
                startingBalance = s.startingBalance ?: this.startingBalance,
            ).toEngine(SequenceTickFeed(emptySequence()))
        }
        return sharedFeed to engineFor
    }
```

- [ ] **Step 5: Run the parity test — expect PASS.**

Run: `./gradlew test --tests 'com.qkt.cli.ScenarioSweepParityTest'`
Expected: PASS.

- [ ] **Step 6: Create `ScenarioFile` (YAML loader) and wire `--scenarios` into `SweepCommand`.** First read `YamlInstrumentRegistry` to confirm the YAML library in use and mirror its loading; the file is a list of maps:

```yaml
- label: rf-fixed-0.01-10000
  params: { slpct: "0.01", rrmult: "2" }
  strategy: /path/to/variant.qkt      # optional; default = the swept strategy
  broker: mt5-sim                      # optional
  instruments: /path/to/instruments.g6-1.5x.yaml   # optional
  startingBalance: "10000"             # optional
```

`ScenarioFile.load(path, defaultAst, dataRoot)` parses each entry: resolves `strategy` via `Dsl.parseFile` (reuse `SweepCommand`'s parse-or-error handling) into a `StrategyAst`, resolves `broker` via the same `when` `BacktestContext.build` uses, loads `instruments` via `YamlInstrumentRegistry.load` layered over `StandardInstrumentRegistry`, parses `startingBalance` via `BigDecimal`. In `SweepCommand.run()`:

```kotlin
        val (sharedFeed, engineFor) = ctx.scenarioEngines()
        val scenarios: List<ScenarioSpec> =
            args.option("scenarios")?.let { ScenarioFile.load(Path.of(it), ast, ctx.dataRoot()) }
                ?: combos.map { ScenarioSpec(label = it.label, params = it.overrides) }
        val ranked =
            SweepReplay(
                configs = scenarios.map { it.label to it },
                sharedFeed = sharedFeed,
                engineFor = { _, s -> engineFor(s) },
                parallelism = parallelism,
            ).run().rankedBy { rank.score(it) }
```

Update `printTable`/`printJson` generics from `SweepRun<ParamGrid.Combo>` to `SweepRun<ScenarioSpec>` and read `run.config.params` instead of `run.config.overrides`. (Expose `ctx.dataRoot()` as a tiny accessor, or pass the data-root string already known in `SweepCommand`.)

- [ ] **Step 7: Run sweep tests (the `--param` path must be unchanged).**

Run: `./gradlew test --tests 'com.qkt.cli.*Sweep*' --tests 'com.qkt.backtest.sweep.*'`
Expected: PASS.

- [ ] **Step 8: Commit** (after asking permission).

```bash
git add src/main/kotlin/com/qkt/cli/ScenarioSpec.kt src/main/kotlin/com/qkt/cli/ScenarioFile.kt \
        src/main/kotlin/com/qkt/cli/BacktestContext.kt src/main/kotlin/com/qkt/cli/SweepCommand.kt \
        src/test/kotlin/com/qkt/cli/ScenarioSweepParityTest.kt
git commit -m "feat(sweep): qkt sweep --scenarios runs broker/instruments/balance/sizing variants on one decode"
```

---

## Task 4 (WS1c): Emit `dailyPnL` + `maxDailyDrawdown` per combo in `sweep --json`

G3 needs `dailyPnL` (profitable-months); G6 risk-fit needs `maxDailyDrawdown` (prop-pass). Both already exist on each combo's `PerformanceReport`; they're just not serialized. Match the field names `qkt backtest --json` uses so forge's parser extends trivially.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/SweepCommand.kt` (`printJson`)
- Test: `src/test/kotlin/com/qkt/cli/SweepJsonFieldsTest.kt` (create)

**Interfaces:**
- Consumes: `PerformanceReport.dailyPnL: Map<LocalDate, BigDecimal>`, `PerformanceReport.maxDailyDrawdown: BigDecimal`. Reuse the exact `dailyPnL` serialization shape from `ReportFormat.printJson` (read it; copy the format — likely `{"2024-01-02":12.5,...}` with ISO date keys).
- Produces: each combo JSON object additionally has `"dailyPnL":{...}` and `"maxDailyDrawdown":N`.

- [ ] **Step 1: Write the test** — run a tiny sweep, capture stdout, assert the two new keys are present and `maxDailyDrawdown` matches the standalone backtest. (Mirror how existing CLI tests capture `System.out`; if there is a JSON helper in tests, reuse it.)

```kotlin
    @Test
    fun `sweep json carries dailyPnL and maxDailyDrawdown per combo`() {
        val json = runSweepCaptureJson(/* tiny strategy + 2-day store, one param combo */)
        assertThat(json).contains("\"dailyPnL\":")
        assertThat(json).contains("\"maxDailyDrawdown\":")
    }
```

- [ ] **Step 2: Run — expect FAIL.**

Run: `./gradlew test --tests 'com.qkt.cli.SweepJsonFieldsTest'`
Expected: FAIL (keys absent).

- [ ] **Step 3: Extend `printJson`.** Add the two fields to the per-combo string (reuse the `dailyPnL` formatter from `ReportFormat`; do not hand-roll a second date format):

```kotlin
                """{"label":"${run.label}","params":{$params},"rank":"${rank.flag}",""" +
                    """"trades":${r.tradeCount},"totalPnL":${r.totalPnL.toPlainString()},""" +
                    """"sharpe":${r.sharpeRatio?.toPlainString() ?: "null"},""" +
                    """"calmar":${r.calmarRatio?.toPlainString() ?: "null"},""" +
                    """"maxDrawdown":${r.maxDrawdown.toPlainString()},"winRate":${r.winRate.toPlainString()},""" +
                    """"maxDailyDrawdown":${r.maxDailyDrawdown.toPlainString()},""" +
                    """"dailyPnL":${formatDailyPnL(r.dailyPnL)}}"""
```

- [ ] **Step 4: Run — expect PASS.**

Run: `./gradlew test --tests 'com.qkt.cli.SweepJsonFieldsTest'`
Expected: PASS.

- [ ] **Step 5: Commit** (after asking permission).

```bash
git add src/main/kotlin/com/qkt/cli/SweepCommand.kt src/test/kotlin/com/qkt/cli/SweepJsonFieldsTest.kt
git commit -m "feat(sweep): emit dailyPnL and maxDailyDrawdown per combo in sweep --json"
```

- [ ] **Step 6: Push the branch and open the PR(s) to `dev`** (after asking permission). Let CI verify the full build. WS1 (Tasks 2-4) and WS2 (Task 1) may be one PR or split; split is cleaner for review.

---

## Task 5 (WS3, POST-DEPLOY): Point forge G3/G6 at the scenario sweep

**Gated on Tasks 1-4 being merged to `dev` and `:edge` rebuilt** (forge reads `:edge`). Repo: `qkt-forge` on bot2 (`/root/projects/qkt-forge`). Python, pytest. Do this only after `qkt sweep --scenarios` exists in `:edge` (verify with `qkt sweep --help` in the new image).

**Files (bot2):**
- Modify: `src/qkt_forge/qkt/runner.py` (add `scenario_sweep(...)` mirroring `sweep(...)`; write the YAML scenarios file, parse the enriched per-combo JSON).
- Modify: `src/qkt_forge/gates/validation.py` (G3: per-config loop → one `scenario_sweep`).
- Modify: `src/qkt_forge/gates/robustness.py` (G6 cost-stress: 3 backtests → one cost-axis scenario sweep).
- Modify: `src/qkt_forge/gates/risk_fit.py` (G6 risk-fit: 13 backtests → one scenario sweep; keep all 13 sims — not reducible).
- Test: forge's pytest suite for the gates (mirror existing gate tests).

- [ ] **Step 1:** Add `QktRunner.scenario_sweep(strategy_path, date_from, date_to, scenarios)` that writes a YAML scenarios file into the run dir, builds `docker run ... qkt sweep <strategy> --scenarios <file> --from --to --json --parallelism 1 --no-fetch --allow-incomplete`, and parses the JSON array via the existing `SweepRun.from_json` (now carrying `dailyPnL`/`maxDailyDrawdown` in `raw`). `--parallelism 1` so the decode happens exactly once.
- [ ] **Step 2:** G3 — replace the carried-config `backtest` loop with one `scenario_sweep` of param-only scenarios; compute `_profitable_months_pct(run.raw["dailyPnL"])` per combo; same pass/fail logic.
- [ ] **Step 3:** G6 cost-stress — one cost-axis scenario sweep (per-scenario `instruments` = the commission-scaled yaml, `broker=mt5-sim`); consume `total_pnl`.
- [ ] **Step 4:** G6 risk-fit — one scenario sweep; per-scenario `strategy` = the `_sub_sizing` variant `.qkt` (forge keeps writing these) and `startingBalance`; consume `total_pnl`, `raw["maxDailyDrawdown"]`, `max_drawdown`, `calmar`, `sharpe`, `trades`.
- [ ] **Step 5: Golden parity check.** On one known real `.qkt`, assert the scenario-sweep path yields the same G3 and G6 pass/fail verdict and the same selected winner as the pre-change N-run path (run both once, diff). Commit on a forge branch; deploy per the forge loop.

---

## Task 6 (POST-DEPLOY): Measure, then decide on the Phase-2 daemon

**Gated on Tasks 1-5 deployed.** Quantify the residual decode cost to decide whether a long-lived decoded-tick daemon is worth building.

- [ ] **Step 1:** Build qkt from source on bot2 with JFR enabled (the `:edge` jlink runtime lacks `jdk.jfr`; pull `dev` first so the build reads `.bin` not stale CSV — procedure in `project_sweep_fanout_2026_06_17`).
- [ ] **Step 2:** Profile the heaviest gate (G6) end-to-end. Record CPU-time fractions / hot-method shares (not wall-clock — the box is contention-bound) for: (i) byte→`long` read, (ii) `BigDecimal.valueOf` alloc + downstream `BigDecimal` decode, (iii) per-engine sim (`BigDecimal` compares, `OrderManager` triggers, candle aggregation).
- [ ] **Step 3: Decision.** If slice (ii) — the only part a cross-process cache could remove beyond mmap + page cache — is still **> ~25%** of fleet CPU, write the Phase-2 daemon spec (long-lived JVM, LRU decoded-tick cache keyed by symbol+day, forge submits jobs; parity = byte-identical to a standalone run). Else stop and record that cores/concurrency is the residual lever. Log the numbers either way (no silent cap).

---

## Self-Review

- **Spec coverage:** WS1 → Tasks 2-4; WS2 → Task 1; WS3 → Task 5; measurement + daemon gate → Task 6; bars → out of scope (spec + here). All spec sections map to a task.
- **Parity:** every qkt task's acceptance is byte-identical equality (`isEqualByComparingTo` for money, `isEqualTo` for counts/trades) to a standalone backtest; WS2 is guarded by the strengthened round-trip test; the symbol-set invariant is enforced in `backtest()` and tested.
- **Type consistency:** `ScenarioSpec` fields (`params`, `ast`, `brokerKind`, `instruments`, `startingBalance`) are referenced identically in Tasks 2/3; `backtest(...)` signature is defined in Task 2 and consumed in Task 3's `scenarioEngines()`; `PerformanceReport.dailyPnL`/`maxDailyDrawdown` types (`Map<LocalDate,BigDecimal>`, `BigDecimal`) match Task 4 usage.
- **Known verify-while-implementing points (not placeholders — explicit checks):** (a) `BinaryTickFormat.readHeader` leaves the buffer at the timestamps block (else compute `tsBase` from header size); (b) the YAML library behind `YamlInstrumentRegistry` (reuse, no new dep); (c) the `dailyPnL` JSON shape in `ReportFormat.printJson` (copy it); (d) the shared `@TempDir` store fixture used by existing `BacktestContext`/sweep tests (reuse `newContextOverTwoDays`).
