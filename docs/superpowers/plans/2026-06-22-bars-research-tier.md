# Bars research tier (`--bars`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `--bars` research tier that replays pre-built binary bars (~tens-of-× faster than ticks) for multi-market / multi-timeframe strategies, reserving ticks for grading.

**Architecture:** A binary bar store (mirrors the binary tick store) written once by `qkt data build-bars` (decode `.bin` ticks → aggregate → store). `--bars` forces `Backtest.replayFeed` to synthesize the engine feed from those bars (via the existing `BarTickFeed`, 4 ticks/bar) at each symbol's finest declared timeframe; `CandleHub` aggregates up to coarser declared timeframes (proven exact). Tick decode is paid once at build time; replay reads page-cached binary bars.

**Tech Stack:** Kotlin, JDK 21, JUnit 5 + AssertJ, Gradle, ktlint. Deploy: qkt `dev`→`testing`→`:edge`; qkt-forge on bot2 consumes `:edge`.

**Spec:** `docs/superpowers/specs/2026-06-22-bars-research-tier-design.md`.

## Global Constraints

- **JDK 21** toolchain. JUnit 5 + AssertJ; `BigDecimal`/`Money` equality via `isEqualByComparingTo`, counts via `isEqualTo`. Run one class: `./gradlew test --tests 'FQCN'`. Run ktlint: `./gradlew ktlintCheck` (fix: `ktlintFormat`); **120-char** line limit.
- **`--bars` is a research tier, NOT byte-identical to ticks** (intrabar fills approximated). It must never silently replace ticks: the flag is explicit, missing bars are a hard error, and grading stays on ticks.
- Tick path unchanged: with `forceBars=false` (default), every existing backtest/sweep/walkforward behaves exactly as today.
- Bars are tiny (a day ≈ tens of KB): use `Files.readAllBytes` + binary columnar decode (no mmap needed at bar sizes; the OS page cache already shares the bytes across processes).
- Commits: conventional, subject only, no body/footer/AI refs. `git status` before any `git add`; never `git add -A`. Ask before committing. qkt PRs target `dev`.
- A scenario/strategy's **symbol set + timeframes** drive the feed; `--bars` synthesizes at each symbol's **finest declared tf** and `CandleHub` derives coarser ones.

---

## Task 1: Binary bar I/O — format, writer, mmap-free feed

Mirror the binary **tick** store (`BinaryTickFormat`/`BinaryTickWriter`/`BinaryTickFeed`) for **bars**. A bar = `(startTs, open, high, low, close, volume)`; columnar `int64` (startTs raw, OHLCV scaled at `Money.SCALE`=8).

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryBarFormat.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryBarWriter.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryBarFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryBarRoundTripTest.kt`

**Interfaces:**
- Consumes: `Candle(symbol, open, high, low, close, volume: BigDecimal, startTime, endTime: Long)`, `Money.SCALE`, `Money.ROUNDING`.
- Produces: `BinaryBarWriter().write(path: Path, symbol: String, timeframeMs: Long, bars: List<Candle>)`; `BinaryBarFeed(path: Path).candles(): List<Candle>` (each `endTime = startTime + timeframeMs`).

- [ ] **Step 1: Write the round-trip test (the parity guard).**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryBarRoundTripTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `writer then feed reproduces bars exactly`(
        @TempDir dir: Path,
    ) {
        val tf = 900_000L // 15m
        val bars =
            (0 until 5000).map { i ->
                val base = 1_712_000_000_000L + i * tf
                Candle("XAUUSD", bd("1850.${1000 + i % 8000}"), bd("1851.${2000 + i % 7000}"),
                    bd("1849.${100 + i % 8000}"), bd("1850.${5000 + i % 4000}"),
                    bd("${i % 1000}.00000000"), base, base + tf)
            }
        val file = dir.resolve("2024-01-04.bin")
        BinaryBarWriter().write(file, "XAUUSD", tf, bars)
        assertEquals(bars, BinaryBarFeed(file).candles())
    }

    @Test
    fun `empty bar list round-trips`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("empty.bin")
        BinaryBarWriter().write(file, "XAUUSD", 900_000L, emptyList())
        assertEquals(emptyList<Candle>(), BinaryBarFeed(file).candles())
    }
}
```

- [ ] **Step 2: Run it — expect compile FAIL** (`BinaryBarFormat`/`Writer`/`Feed` absent).

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryBarRoundTripTest'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement `BinaryBarFormat`.**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * On-disk layout of a binary bar day-file (`<date>.bin`), format `qkt-bar-bin-v1`. A little-endian
 * header then a columnar body: six int64 blocks of [Header.barCount] values each — startTs (raw ms),
 * then open/high/low/close/volume as scaled integers (a BigDecimal's unscaled value at scale [SCALE],
 * e.g. 1850.50000000 -> 185050000000). Reconstruct a value with BigDecimal.valueOf(stored, SCALE).
 */
object BinaryBarFormat {
    val MAGIC = byteArrayOf('Q'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1
    const val SCALE = Money.SCALE // 8

    data class Header(
        val symbol: String,
        val barCount: Int,
        val timeframeMs: Long,
        val version: Int = VERSION,
        val scale: Int = SCALE,
    )

    fun writeHeader(
        buf: ByteBuffer,
        h: Header,
    ) {
        buf.put(MAGIC)
        buf.putInt(h.version)
        buf.putInt(h.scale)
        buf.putLong(h.timeframeMs)
        val sym = h.symbol.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(sym.size)
        buf.put(sym)
        buf.putInt(h.barCount)
    }

    fun readHeader(buf: ByteBuffer): Header {
        val magic = ByteArray(4).also { buf.get(it) }
        check(magic.contentEquals(MAGIC)) { "bad magic: not a qkt-bar-bin file" }
        val version = buf.int
        check(version == VERSION) { "unsupported binary bar version: $version" }
        val scale = buf.int
        val timeframeMs = buf.long
        val symLen = buf.int
        val sym = ByteArray(symLen).also { buf.get(it) }
        val barCount = buf.int
        return Header(String(sym, StandardCharsets.UTF_8), barCount, timeframeMs, version, scale)
    }
}
```

- [ ] **Step 4: Implement `BinaryBarWriter`.**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Writes [Candle]s to a binary bar day-file ([BinaryBarFormat]). Columnar: startTs then O/H/L/C/V. */
class BinaryBarWriter {
    fun write(
        path: Path,
        symbol: String,
        timeframeMs: Long,
        bars: List<Candle>,
    ) {
        val n = bars.size
        val symBytes = symbol.toByteArray(Charsets.UTF_8)
        val headerSize = 4 + 4 + 4 + 8 + 4 + symBytes.size + 4
        val buf = ByteBuffer.allocate(headerSize + n * 6 * Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        BinaryBarFormat.writeHeader(buf, BinaryBarFormat.Header(symbol, n, timeframeMs))
        for (b in bars) buf.putLong(b.startTime)
        for (b in bars) buf.putLong(scaled(b.open))
        for (b in bars) buf.putLong(scaled(b.high))
        for (b in bars) buf.putLong(scaled(b.low))
        for (b in bars) buf.putLong(scaled(b.close))
        for (b in bars) buf.putLong(scaled(b.volume))
        Files.createDirectories(path.parent)
        Files.write(path, buf.array())
    }

    private fun scaled(v: BigDecimal): Long = v.setScale(Money.SCALE, Money.ROUNDING).unscaledValue().longValueExact()
}
```

- [ ] **Step 5: Implement `BinaryBarFeed`.**

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads [Candle]s from a binary bar day-file ([BinaryBarFormat]). Bars are tiny (a day is tens of KB),
 * so it reads the whole file (served from the OS page cache after first read) and decodes the columnar
 * int64 body with BigDecimal.valueOf(stored, scale). Each candle's endTime = startTime + timeframeMs.
 */
class BinaryBarFeed(
    path: Path,
) {
    private val header: BinaryBarFormat.Header
    private val candles: List<Candle>

    init {
        val buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        header = BinaryBarFormat.readHeader(buf)
        val n = header.barCount
        val ts = LongArray(n) { buf.long }
        val open = LongArray(n) { buf.long }
        val high = LongArray(n) { buf.long }
        val low = LongArray(n) { buf.long }
        val close = LongArray(n) { buf.long }
        val volume = LongArray(n) { buf.long }
        val s = header.scale
        candles =
            (0 until n).map { i ->
                Candle(
                    header.symbol,
                    BigDecimal.valueOf(open[i], s),
                    BigDecimal.valueOf(high[i], s),
                    BigDecimal.valueOf(low[i], s),
                    BigDecimal.valueOf(close[i], s),
                    BigDecimal.valueOf(volume[i], s),
                    ts[i],
                    ts[i] + header.timeframeMs,
                )
            }
    }

    fun candles(): List<Candle> = candles
}
```

- [ ] **Step 6: Run the round-trip test — expect PASS.**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryBarRoundTripTest'`
Expected: PASS (both tests).

- [ ] **Step 7: ktlint + commit** (after asking permission).

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/marketdata/BinaryBar*.kt src/test/kotlin/com/qkt/marketdata/BinaryBarRoundTripTest.kt
git commit -m "feat(marketdata): binary bar format, writer, and feed"
```

---

## Task 2: `BinaryBarStore` — day-file layout + read/write/has

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/BinaryBarStore.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/BinaryBarStoreTest.kt`

**Interfaces:**
- Consumes: `BinaryBarWriter`, `BinaryBarFeed`, `TimeWindow` (`canonicalSpec()` → e.g. "15m", `toMillis()`).
- Produces: `BinaryBarStore(root: Path)` with `hasDay(broker,symbol,tf,date)`, `writeDay(broker,symbol,tf,date,bars)`, `readDay(broker,symbol,tf,date): List<Candle>`. Layout `bars/<broker>/<symbol>/<tf>/<YYYY-MM-DD>.bin`. (No manifest — file existence IS coverage; YAGNI for Layer 0.)

- [ ] **Step 1: Write the store test.**

```kotlin
package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryBarStoreTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `write then read a day; coverage reflects presence`(
        @TempDir dir: Path,
    ) {
        val store = BinaryBarStore(dir)
        val tf = TimeWindow.parse("15m")
        val day = LocalDate.parse("2024-01-04")
        val base = 1_712_000_000_000L
        val bars = listOf(Candle("XAUUSD", bd("1850"), bd("1851"), bd("1849"), bd("1850.5"), bd("10"), base, base + 900_000L))
        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isFalse()
        store.writeDay("BACKTEST", "XAUUSD", tf, day, bars)
        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isTrue()
        assertThat(store.readDay("BACKTEST", "XAUUSD", tf, day)).isEqualTo(bars)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.** `./gradlew test --tests 'com.qkt.marketdata.store.BinaryBarStoreTest'`

- [ ] **Step 3: Implement `BinaryBarStore`.**

```kotlin
package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.BinaryBarFeed
import com.qkt.marketdata.BinaryBarWriter
import com.qkt.marketdata.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * On-disk binary bar store: `bars/<broker>/<symbol>/<tf>/<YYYY-MM-DD>.bin`. One UTC day per file.
 * File presence is coverage. Written by `qkt data build-bars`; read by the `--bars` replay path.
 */
class BinaryBarStore(
    private val root: Path,
) {
    private fun dayFile(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Path = root.resolve("bars").resolve(broker).resolve(symbol).resolve(tf.canonicalSpec()).resolve("$date.bin")

    fun hasDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): Boolean = Files.exists(dayFile(broker, symbol, tf, date))

    fun writeDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
        bars: List<Candle>,
    ) = BinaryBarWriter().write(dayFile(broker, symbol, tf, date), symbol, tf.toMillis(), bars)

    fun readDay(
        broker: String,
        symbol: String,
        tf: TimeWindow,
        date: LocalDate,
    ): List<Candle> = BinaryBarFeed(dayFile(broker, symbol, tf, date)).candles()
}
```

(Verify `TimeWindow.canonicalSpec()` and `toMillis()` exist — the audit cited `canonicalSpec()`; confirm the ms accessor's exact name, e.g. `toMillis()`/`windowMs`, and use it.)

- [ ] **Step 4: Run — expect PASS.** Then ktlint + commit `feat(marketdata): binary bar store (day-file layout, read/write)`.

---

## Task 3: `qkt data build-bars` — decode ticks once → store bars

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DataCommand.kt` (add a `build-bars` subcommand alongside `convert`)
- Test: `src/test/kotlin/com/qkt/cli/DataBuildBarsTest.kt`

**Interfaces:**
- Consumes: the tick read path (`DefaultDataStore`/`LocalMarketSource.ticks` or `DayTickFeed`), `CandleAggregator`, `BinaryBarStore`, `BacktestContext.parseInstant`.
- Produces: CLI `qkt data build-bars <SYMBOL> --tf <interval> --from <d> --to <d> [--data-root <p>]` writing `bars/BACKTEST/<SYMBOL>/<tf>/<day>.bin`.

- [ ] **Step 1: Write the build test** — seed a tick day (via `BinaryTickWriter` into the tick store, or `FakeXauFetcher`), run `build-bars`, assert the stored bars equal a direct `CandleAggregator` aggregation of those ticks.

```kotlin
// Seed ~1 day of XAUUSD ticks into a temp data-root (BinaryTickWriter -> symbols/BACKTEST/XAUUSD/<day>.bin),
// run DataCommand(Args(arrayOf("data","build-bars","XAUUSD","--tf","15m","--from","2024-01-04",
//   "--to","2024-01-05","--data-root", dir.resolve("data").toString()))).run()
// then BinaryBarStore(dir/data).readDay("BACKTEST","XAUUSD",TimeWindow.parse("15m"),day) == aggregate(ticks,15m).
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement the `build-bars` subcommand.** Read the existing `DataCommand.convert` for the arg/store conventions, then:
  - Resolve `dataRoot`, `from`/`to` (`BacktestContext.parseInstant`), `tf = TimeWindow.parse(args.requireOption("tf"))`, symbol (positional, prefixed `BACKTEST:<SYMBOL>` for the source).
  - For each UTC day in `[from,to)`: stream that day's ticks (`DayTickFeed`/`LocalMarketSource.ticks` for the day), feed a `CandleAggregator(window=tf)` (use the `bus=null` private factory via the existing `onClose` constructor — `CandleAggregator.kt:108` shows `CandleAggregator(window, onClose, bus=null)`; reuse it), collect closed candles + flush, `store.writeDay(...)`. Skip days already present (incremental).
  - Print a summary (`built N days, M bars`).

- [ ] **Step 4: Run — expect PASS.** Then ktlint + commit `feat(data): qkt data build-bars aggregates the tick store into a binary bar store`.

---

## Task 4: `--bars` replay — forceBars + per-symbol finest-tf + binary-bar resolution

The core. `forceBars` flips `replayFeed`'s tick-preference; `BacktestContext` computes per-symbol finest-tf windows and injects the `BinaryBarStore`; `LocalMarketSource.bars()` prefers binary bars (exact tf, else aggregate from a finer stored tf, else — under force — error).

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt` (`replayFeed`, `fromSource`, `fromStore` — add `forceBars`; pass per-symbol windows)
- Modify: `src/main/kotlin/com/qkt/marketdata/source/LocalMarketSource.kt` (`bars()` prefers `BinaryBarStore`; resolver order)
- Modify: `src/main/kotlin/com/qkt/cli/BacktestContext.kt` (`forceBars` field, `barWindows` map, inject `BinaryBarStore`, thread to `Backtest.fromStore`)
- Test: `src/test/kotlin/com/qkt/backtest/BarsReplayTest.kt`

**Interfaces:**
- Consumes: `BinaryBarStore`, `BarTickFeed`, `MergingTickFeed`, `CandleHub`.
- Produces: `Backtest.fromStore(..., forceBars: Boolean = false, barWindows: Map<String, TimeWindow> = emptyMap())`; `replayFeed(source, symbol, range, window, forceBars)`.

- [ ] **Step 1: Write the tests** (promote the spike's multi-tf rollup; add force-bars replay + missing-bars error):

```kotlin
// (a) multi-timeframe rollup exact: ticks->15m bars->candleToTicks->aggregate 1h == ticks->1h
//     (the validated spike check, kept as a real regression test).
// (b) --bars replay reads the binary bar store and produces trades (build bars in a temp store,
//     run a backtest with forceBars=true over a multi-tf strategy, assert >0 trades + both tf series built).
// (c) missing-bars under forceBars errors with a build-bars hint (no silent tick aggregation).
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement.**
  - `replayFeed` (`Backtest.kt:224`): signature gains `forceBars: Boolean`; guard becomes `if (!forceBars && ticksAvailable) { ... }`; when `forceBars && (BARS !in caps || window == null)` → `error("--bars: no bars for $symbol at $window; run: qkt data build-bars $symbol --tf ${window?.canonicalSpec()}")`.
  - `fromSource`/`fromStore`: add `forceBars` + `barWindows: Map<String,TimeWindow>`; in the per-symbol map (`Backtest.kt:195`), pass `barWindows[sym] ?: candleWindow` as that symbol's window.
  - `LocalMarketSource.bars(symbol, window, range)`: if a `BinaryBarStore` is injected and all days present at `window` → read it; else if present at a *finer* stored tf → read finer + `CandleAggregator` rollup to `window`; else fall through (today's CSV/tick-aggregation — reached only when `!forceBars`).
  - `BacktestContext`: `val forceBars = args.flag("bars")`; `barWindows = ast.streams.groupBy { it.qktSymbol }.mapValues { (_, s) -> s.map { TimeWindow.parse(it.timeframe) }.minByOrNull { it.toMillis() }!! }`; construct + inject a `BinaryBarStore(dataRoot)`; pass `forceBars` + `barWindows` to `Backtest.fromStore`.

- [ ] **Step 4: Run — expect PASS** (incl. the multi-tf rollup + force-bars + error tests).

- [ ] **Step 5: Regression** — `./gradlew test --tests 'com.qkt.backtest.*' --tests 'com.qkt.cli.*'` (default tick path unchanged). ktlint + commit `feat(backtest): --bars forces binary-bar replay at each symbol's finest timeframe`.

---

## Task 5: `--bars` CLI flag on backtest / sweep / walkforward

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt`, `SweepCommand.kt`, `WalkForwardCommand.kt` (none needed if all route through `BacktestContext.build` reading `args.flag("bars")` — verify; add the help/usage line + the research-tier notice).
- Test: `src/test/kotlin/com/qkt/cli/BarsCliTest.kt`

**Interfaces:** Consumes Task 4's `BacktestContext` `forceBars`. Produces the `--bars` flag end-to-end.

- [ ] **Step 1: Test** — `qkt backtest <strat> --bars` over a temp store with pre-built bars exits SUCCESS and (via captured stderr) prints the research-tier notice; without pre-built bars exits USER_ERROR with the `build-bars` hint.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3:** Since sweep/walkforward route through `ctx.backtest`/`scenarioEngines`, the Task-4 context field already covers them; add the stderr notice (`research tier: bar-approximated fills — not for grading`) when `forceBars`, and document `--bars` in each command's usage string. Catch the missing-bars `IllegalStateException` → `USER_ERROR`.
- [ ] **Step 4: Run — expect PASS.** ktlint + commit `feat(cli): --bars research-tier flag on backtest/sweep/walkforward`.

- [ ] **Step 6: PR to `dev`** (after asking) — push branch, open PR, let CI (`build`+`build-windows`) verify, merge → `:edge`.

---

## Task 6 (POST-DEPLOY): prove it live on qkt-forge + confirm

**Gated on Tasks 1-5 merged to `dev` and `:edge` rebuilt.** On bot2, real data.

- [ ] **Step 1:** Build bars for a real symbol: `docker run … qkt data build-bars XAUUSD --tf 15m --from 2021-01-01 --to 2023-01-01 --data-root …/run/data` (decode-once). Confirm `bars/BACKTEST/XAUUSD/15m/*.bin` written.
- [ ] **Step 2:** Run a real strategy two ways over 2yr: tick (`qkt backtest …`) vs `--bars` (`qkt backtest … --bars`). Record wall-clock; confirm the bar run is in single-digit seconds and tens-of-× faster.
- [ ] **Step 3:** Multi-market + multi-timeframe: build bars for the finest tf of a 2-symbol, 2-timeframe strategy; `--bars` run produces trades and builds every declared tf.
- [ ] **Step 4:** Wire into qkt-forge's research/exploration step (the fast pre-screen) — `runner.backtest`/`sweep` gain a `bars: bool` that appends `--bars`; the gate that screens ideas uses it, grading gates stay on ticks. Golden-check: a known candidate screens sanely on bars. (Forge-repo change on bot2, uncommitted per its pattern.)
- [ ] **Step 5:** Record the measured numbers in `project_backtest_decode_sharing_2026_06_22` (or a new memory). Confirm the forge loop stays healthy.

---

## Self-Review

- **Spec coverage:** binary store → Tasks 1-2; build-bars → Task 3; `--bars`+resolver+finest-tf → Task 4; CLI flag + guardrail → Task 5; live on forge + confirm → Task 6; Layer 1 → out of scope (spec). Multi-tf rollup test → Task 4(a). All spec sections covered.
- **Placeholders:** Task 3/5/6 steps name exact files, commands, and the resolver/aggregation logic; the one verify-while-implementing note (TimeWindow ms accessor name; whether sweep/walkforward need edits) is an explicit check, not a gap.
- **Type consistency:** `BinaryBarWriter().write(path,symbol,timeframeMs,bars)` / `BinaryBarFeed(path).candles()` / `BinaryBarStore.{hasDay,writeDay,readDay}(broker,symbol,tf:TimeWindow,date)` / `replayFeed(...,forceBars)` / `fromStore(...,forceBars,barWindows)` are used identically across tasks. `Candle` field order matches `Candle.kt`.
