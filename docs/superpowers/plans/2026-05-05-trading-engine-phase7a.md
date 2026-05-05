# Phase 7a — Refactor + Abstractions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the mode-agnostic groundwork for Phase 7 — the `MarketSource` umbrella, `TimeMark`/`TradingCalendar`/session anchors, the `RangeAggregateIndicator` family, and `SessionContext` — without writing any live-runtime code. After this plan merges, all 264+ existing tests still pass, the engine still runs identically, and the next two plans (7b live runtime, 7c TradingView) plug into stable interfaces.

**Architecture:** Phase 6 shipped `HistoricalDataProvider` + `DataStore` + `DataRequest` for historical-only access. Phase 7's `MarketSource` is the umbrella that will eventually cover live too; this plan introduces it now, ports the existing local-cache implementation behind it, and adds the calendar + range-indicator primitives that strategies need. Live ticks land in Plan 7b. TradingView lands in Plan 7c.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5, AssertJ, kotlinx-serialization-json (already a dep). No new runtime dependencies in this plan.

**Spec:** [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../specs/2026-05-05-trading-engine-phase7-design.md)

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/marketdata/source/
    MarketSource.kt                       — interface
    MarketSourceCapability.kt             — enum
    MarketRequest.kt                      — data class (was DataRequest)
    UnsupportedDataException.kt           — moved from history/
    LocalMarketSource.kt                  — was StoreHistoricalDataProvider
    CompositeMarketSource.kt              — symbol-pattern router
    Reductions.kt                         — moved from history/

src/main/kotlin/com/qkt/common/
    SessionAnchor.kt                      — sealed class
    TradingCalendar.kt                    — interface + factories
    CryptoCalendar.kt                     — internal impl
    FxCalendar.kt                         — internal impl
    NyseCalendar.kt                       — internal impl
    TimeMark.kt                           — sealed class
    RefreshTrigger.kt                     — sealed class

src/main/kotlin/com/qkt/indicators/range/
    RangeAggregateIndicator.kt            — base machinery
    SessionAnchoredIndicator.kt           — sugar over the above
    PreviousDayHigh.kt
    PreviousDayLow.kt
    SessionHigh.kt
    SessionLow.kt

src/main/kotlin/com/qkt/strategy/
    SessionContext.kt                     — bundle injected into strategies
    Mode.kt                               — enum (BACKTEST | LIVE)

src/test/kotlin/...                       — mirroring tests for each
```

### Modified files

```
src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt    — DELETE (moved + renamed to MarketRequest)
src/main/kotlin/com/qkt/marketdata/history/                — DELETE entire package
src/main/kotlin/com/qkt/strategy/Strategy.kt               — add onTickWithContext default method
src/main/kotlin/com/qkt/app/Backtest.kt                    — add fromSource factory; mark fromStore deprecated; pass SessionContext
src/main/kotlin/com/qkt/app/TradingPipeline.kt             — receive SessionContext, route to strategies
src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt — minor rename (DataRequest → MarketRequest)
```

### Deleted files

```
src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt
src/main/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProvider.kt
src/main/kotlin/com/qkt/marketdata/history/Reductions.kt        — moved
src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt         — moved + renamed
src/test/kotlin/com/qkt/marketdata/history/UnsupportedDataExceptionTest.kt — moved
src/test/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProviderTest.kt — moved + renamed
src/test/kotlin/com/qkt/marketdata/history/ReductionsTest.kt    — moved
src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt     — moved + renamed
```

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Add `MarketSourceCapability` enum and `marketdata.source` package |
| 2 | A | Move `MarketRequest` (was `DataRequest`) to source package |
| 3 | A | Move `UnsupportedDataException` to source package |
| 4 | A | Add `MarketSource` interface |
| 5 | A | Add `LocalMarketSource` (was `StoreHistoricalDataProvider`) |
| 6 | A | Add `CompositeMarketSource` with symbol-pattern routing |
| 7 | A | Move `Reductions` to source package |
| 8 | A | Delete `marketdata.history` package |
| 9 | A | Update `Backtest.fromStore` to use `LocalMarketSource`; add `Backtest.fromSource` |
| 10 | B | Add `SessionAnchor` sealed class |
| 11 | B | Add `TradingCalendar` interface + `crypto()` impl |
| 12 | B | Add `fxDefault()` calendar (24/5 with weekend gaps) |
| 13 | B | Add `nyse()` calendar (9:30–16:00 ET with US holidays) |
| 14 | B | Add `TimeMark` sealed class |
| 15 | B | Add `TimeRange.of(from, to, clock, calendar)` builder |
| 16 | B | Add `RefreshTrigger` sealed class |
| 17 | C | Add `RangeAggregateIndicator` base machinery |
| 18 | C | Add `SessionAnchoredIndicator` (sugar) |
| 19 | C | Add `PreviousDayHigh` and `PreviousDayLow` |
| 20 | C | Add `SessionHigh` and `SessionLow` |
| 21 | D | Add `Mode` enum |
| 22 | D | Add `SessionContext` data class |
| 23 | D | Add `Strategy.onTickWithContext` default method bridge |
| 24 | D | Wire `SessionContext` through `Backtest` and `TradingPipeline` |
| 25 | — | Final verification |

---

## Group A: Renames + `MarketSource` family

### Task 1: Add `MarketSourceCapability` enum and `marketdata.source` package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/MarketSourceCapability.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/MarketSourceCapabilityTest.kt`

- [ ] **Step 1: Create the enum**

```kotlin
package com.qkt.marketdata.source

enum class MarketSourceCapability {
    LIVE_TICKS,
    BARS,
    TICKS,
}
```

- [ ] **Step 2: Sanity test (verifies the enum loads and has all variants)**

```kotlin
package com.qkt.marketdata.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketSourceCapabilityTest {
    @Test
    fun `enumerates all expected capabilities`() {
        assertThat(MarketSourceCapability.entries).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }
}
```

- [ ] **Step 3: Run check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 265 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/source/MarketSourceCapability.kt src/test/kotlin/com/qkt/marketdata/source/MarketSourceCapabilityTest.kt
git commit -m "feat(marketdata): add MarketSourceCapability enum"
```

---

### Task 2: Move `MarketRequest` (renamed `DataRequest`) to source package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/MarketRequest.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/MarketRequestTest.kt`
- Delete: `src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt`
- Delete: `src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt`
- Modify: `src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt` — change `DataRequest` references to `MarketRequest`
- Modify: `src/main/kotlin/com/qkt/marketdata/store/DataStore.kt` — change `DataRequest` references to `MarketRequest`
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt` — change import
- Modify: `src/test/kotlin/com/qkt/marketdata/store/DefaultDataStoreTest.kt` — change import
- Modify: `src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt` — change import

- [ ] **Step 1: Create the new `MarketRequest`**

```kotlin
package com.qkt.marketdata.source

import java.time.Instant

data class MarketRequest(
    val symbols: List<String>,
    val from: Instant? = null,
    val to: Instant? = null,
) {
    init {
        require(symbols.isNotEmpty()) { "MarketRequest requires at least one symbol" }
        if (from != null && to != null) {
            require(from < to) { "from must be < to: from=$from, to=$to" }
        }
    }
}
```

- [ ] **Step 2: Create the test (port from `DataRequestTest`)**

```kotlin
package com.qkt.marketdata.source

import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketRequestTest {
    @Test
    fun `requires non empty symbols`() {
        assertThatThrownBy { MarketRequest(symbols = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requires from less than to when both set`() {
        val a = Instant.parse("2024-01-15T00:00:00Z")
        val b = Instant.parse("2024-01-16T00:00:00Z")
        assertThatThrownBy { MarketRequest(listOf("X"), from = b, to = a) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `null from and to are allowed`() {
        MarketRequest(listOf("X"))
        MarketRequest(listOf("X"), from = Instant.parse("2024-01-15T00:00:00Z"))
        MarketRequest(listOf("X"), to = Instant.parse("2024-01-15T00:00:00Z"))
    }
}
```

- [ ] **Step 3: Update consumers — global rename `DataRequest` → `MarketRequest`**

Run from repo root:
```bash
grep -rl "DataRequest" src/ | xargs sed -i 's/DataRequest/MarketRequest/g'
```

Then update imports in the modified files: any `import com.qkt.marketdata.store.DataRequest` becomes `import com.qkt.marketdata.source.MarketRequest`.

- [ ] **Step 4: Delete the old files**

```bash
rm src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt
rm src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt
```

- [ ] **Step 5: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, 265 tests pass (DataRequestTest renamed to MarketRequestTest).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(marketdata): rename DataRequest to MarketRequest in source package"
```

---

### Task 3: Move `UnsupportedDataException` to source package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/UnsupportedDataException.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/source/DataCapability.kt` — TEMP shim, deleted in Task 4
- Modify: `src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt` — re-export `UnsupportedDataException` from new location

Note: The Phase 6 `UnsupportedDataException` references `DataCapability` (which renames to `MarketSourceCapability` in Task 1's package). We need `UnsupportedDataException` to take `MarketSourceCapability`, but we keep `HistoricalDataProvider`'s callers compiling until Task 8 deletes them. Solution: define the new exception against `MarketSourceCapability` and re-export from `history` for back-compat during this transition window.

- [ ] **Step 1: Create the new exception**

```kotlin
package com.qkt.marketdata.source

class UnsupportedDataException(
    capability: MarketSourceCapability,
    providerClass: String,
) : RuntimeException("$providerClass does not support $capability")
```

- [ ] **Step 2: Make the old `history.UnsupportedDataException` a thin alias for the transition window**

Modify `src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt`:

```kotlin
package com.qkt.marketdata.history

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSourceCapability

@Deprecated("Use com.qkt.marketdata.source.MarketSourceCapability", ReplaceWith("MarketSourceCapability", "com.qkt.marketdata.source.MarketSourceCapability"))
typealias DataCapability = MarketSourceCapability

@Deprecated("Use com.qkt.marketdata.source.UnsupportedDataException", ReplaceWith("UnsupportedDataException", "com.qkt.marketdata.source.UnsupportedDataException"))
typealias UnsupportedDataException = com.qkt.marketdata.source.UnsupportedDataException

interface HistoricalDataProvider {
    val capabilities: Set<MarketSourceCapability>

    fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick>

    fun candles(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle>
}
```

- [ ] **Step 3: Port the test**

Create `src/test/kotlin/com/qkt/marketdata/source/UnsupportedDataExceptionTest.kt`:

```kotlin
package com.qkt.marketdata.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsupportedDataExceptionTest {
    @Test
    fun `message includes capability and provider class`() {
        val ex = UnsupportedDataException(MarketSourceCapability.TICKS, "FakeProvider")
        assertThat(ex.message).contains("TICKS")
        assertThat(ex.message).contains("FakeProvider")
    }

    @Test
    fun `is a RuntimeException`() {
        val ex = UnsupportedDataException(MarketSourceCapability.BARS, "X")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
```

Delete the old test:
```bash
rm src/test/kotlin/com/qkt/marketdata/history/UnsupportedDataExceptionTest.kt
```

- [ ] **Step 4: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, 265 tests pass. `StoreHistoricalDataProvider` keeps working via the typealias.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(marketdata): move UnsupportedDataException to source package"
```

---

### Task 4: Add `MarketSource` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/MarketSource.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/MarketSourceTest.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

interface MarketSource {
    val name: String
    val capabilities: Set<MarketSourceCapability>

    fun supports(symbol: String): Boolean

    fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName ?: "MarketSource")

    fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        throw UnsupportedDataException(MarketSourceCapability.BARS, this::class.java.simpleName ?: "MarketSource")

    fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        throw UnsupportedDataException(MarketSourceCapability.TICKS, this::class.java.simpleName ?: "MarketSource")
}
```

- [ ] **Step 2: Write a sanity test using a tiny fake**

```kotlin
package com.qkt.marketdata.source

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketSourceTest {
    private class BarsOnlySource : MarketSource {
        override val name = "BarsOnly"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true
    }

    @Test
    fun `unsupported live ticks throws with capability and class`() {
        val src = BarsOnlySource()
        assertThatThrownBy { src.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("LIVE_TICKS")
            .hasMessageContaining("BarsOnlySource")
    }

    @Test
    fun `unsupported ticks throws with capability and class`() {
        val src = BarsOnlySource()
        val range =
            com.qkt.common.TimeRange(
                java.time.Instant.parse("2024-01-15T00:00:00Z"),
                java.time.Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThatThrownBy { src.ticks("X", range).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("TICKS")
    }

    @Test
    fun `capabilities reports only what is supported`() {
        val src = BarsOnlySource()
        assertThat(src.capabilities).containsExactly(MarketSourceCapability.BARS)
    }
}
```

- [ ] **Step 3: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, 268 tests pass (3 new).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/source/MarketSource.kt src/test/kotlin/com/qkt/marketdata/source/MarketSourceTest.kt
git commit -m "feat(marketdata): add MarketSource umbrella interface"
```

---

### Task 5: Add `LocalMarketSource` (was `StoreHistoricalDataProvider`)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/LocalMarketSource.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/LocalMarketSourceTest.kt`

The existing `StoreHistoricalDataProvider` had a clock + look-ahead-bias guard and queried `DataStore` for ticks across day boundaries; the new `LocalMarketSource` keeps both behaviors, gains the `bars()` capability by aggregating ticks via `CandleAggregator` semantics (one-shot historical aggregation, not the live-stream version), and implements `MarketSource`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalMarketSourceTest {
    @TempDir lateinit var dir: Path

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
    private val day15: Long = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private fun seed() {
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        val rows =
            "$header\n" +
                "${day15 + 1000L},X,100,1,,,,\n" +
                "${day15 + 31_000L},X,101,1,,,,\n" +
                "${day15 + 61_000L},X,102,1,,,,"
        Files.writeString(symDir.resolve("2024-01-15.csv"), rows)
        ManifestStore(dir, FixedClock(time = day15)).write(
            Manifest(symbol = "X", ranges = listOf(DayRange("2024-01-15", "2024-01-16"))),
        )
    }

    private fun sourceAt(now: String): LocalMarketSource {
        seed()
        val clock = FixedClock(time = Instant.parse(now).toEpochMilli())
        return LocalMarketSource(DefaultDataStore(root = dir, clock = clock), clock)
    }

    @Test
    fun `capabilities advertise BARS and TICKS`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        assertThat(src.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }

    @Test
    fun `does not advertise LIVE_TICKS`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        assertThat(src.capabilities).doesNotContain(MarketSourceCapability.LIVE_TICKS)
    }

    @Test
    fun `liveTicks throws UnsupportedDataException`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        assertThatThrownBy { src.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
            .hasMessageContaining("LIVE_TICKS")
    }

    @Test
    fun `ticks returns ticks within range`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        val ts = src.ticks("X", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(day15 + 1000L, day15 + 31_000L, day15 + 61_000L)
    }

    @Test
    fun `look ahead query throws`() {
        val src = sourceAt("2024-01-15T00:00:00Z")
        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        assertThatThrownBy { src.ticks("X", range).toList() }
            .hasMessageContaining("look-ahead")
    }

    @Test
    fun `bars aggregates ticks via TimeWindow`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-15T00:02:00Z"))
        val bars = src.bars("X", TimeWindow.ONE_MINUTE, range).toList()
        assertThat(bars).hasSize(2)
        assertThat(bars[0].close).isEqualByComparingTo(Money.of("101"))
        assertThat(bars[1].open).isEqualByComparingTo(Money.of("102"))
    }

    @Test
    fun `supports any symbol because the store is content-addressable`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        assertThat(src.supports("anything")).isTrue()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.source.LocalMarketSourceTest"`
Expected: compile failure — `Unresolved reference: LocalMarketSource`.

- [ ] **Step 3: Implement `LocalMarketSource.kt`**

Port `StoreHistoricalDataProvider` directly, adapting to the new interface:

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.store.DataStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LocalMarketSource(
    private val store: DataStore,
    private val clock: Clock,
) : MarketSource {
    override val name: String = "Local"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = true

    override fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName!!)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val now = Instant.ofEpochMilli(clock.now())
        require(range.to <= now) {
            "look-ahead bias: cannot query ticks beyond current time. now=$now, requested to=${range.to}; symbol=$symbol"
        }
        return sequence {
            val days = daysCovering(range)
            for (day in days) {
                val path = store.dayFile(symbol, day) ?: continue
                CsvTickFeed(path).use { feed ->
                    while (true) {
                        val t = feed.next() ?: break
                        if (t.timestamp < range.from.toEpochMilli()) continue
                        if (t.timestamp >= range.to.toEpochMilli()) return@use
                        yield(t)
                    }
                }
            }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        sequence {
            var bucketStart: Long = -1
            var bucketEnd: Long = -1
            var open: BigDecimal = Money.ZERO
            var high: BigDecimal = Money.ZERO
            var low: BigDecimal = Money.ZERO
            var close: BigDecimal = Money.ZERO
            var volume: BigDecimal = Money.ZERO
            var hasData = false

            for (tick in ticks(symbol, range)) {
                val ws = window.windowStartFor(tick.timestamp)
                if (!hasData) {
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                    hasData = true
                    continue
                }
                if (tick.timestamp >= bucketEnd) {
                    yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                } else {
                    if (tick.price > high) high = tick.price
                    if (tick.price < low) low = tick.price
                    close = tick.price
                    if (tick.volume != null) volume = volume.add(tick.volume)
                }
            }
            if (hasData) {
                yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
            }
        }

    private fun daysCovering(range: TimeRange): List<LocalDate> {
        val fromDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val toInclusiveDay = Instant.ofEpochMilli(range.to.toEpochMilli() - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusiveDay)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.source.LocalMarketSourceTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew ktlintFormat check`
Expected: 275 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/source/LocalMarketSource.kt src/test/kotlin/com/qkt/marketdata/source/LocalMarketSourceTest.kt
git commit -m "feat(marketdata): add LocalMarketSource implementing MarketSource"
```

---

### Task 6: Add `CompositeMarketSource` with symbol-pattern routing

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/CompositeMarketSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompositeMarketSourceTest {
    private class FakeSource(
        override val name: String,
        override val capabilities: Set<MarketSourceCapability>,
        private val supportedPrefixes: List<String>,
    ) : MarketSource {
        var lastBarsSymbol: String? = null
        var lastTicksSymbol: String? = null

        override fun supports(symbol: String): Boolean = supportedPrefixes.any { symbol.startsWith(it) }

        override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> {
            lastBarsSymbol = symbol
            return emptySequence()
        }

        override fun ticks(symbol: String, range: TimeRange): Sequence<Tick> {
            lastTicksSymbol = symbol
            return emptySequence()
        }
    }

    @Test
    fun `routes bar query to source whose pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:", "BINANCE:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("OANDA:EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(tv.lastBarsSymbol).isEqualTo("OANDA:EURUSD")
        assertThat(local.lastBarsSymbol).isNull()
    }

    @Test
    fun `falls back when no pattern matches`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        composite.bars("EURUSD", TimeWindow.ONE_MINUTE, range).toList()

        assertThat(local.lastBarsSymbol).isEqualTo("EURUSD")
        assertThat(tv.lastBarsSymbol).isNull()
    }

    @Test
    fun `capabilities is the union of all routes plus fallback`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS), listOf(""))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.capabilities).containsExactlyInAnyOrder(
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.TICKS,
        )
    }

    @Test
    fun `supports returns true if any route or fallback supports`() {
        val tv = FakeSource("TV", setOf(MarketSourceCapability.BARS), listOf("OANDA:"))
        val local = FakeSource("Local", setOf(MarketSourceCapability.TICKS), listOf("LOCAL:"))
        val composite =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("OANDA:") to tv),
                fallback = local,
            )

        assertThat(composite.supports("OANDA:EURUSD")).isTrue()
        assertThat(composite.supports("LOCAL:X")).isTrue()
        assertThat(composite.supports("UNKNOWN")).isFalse()
    }

    @Test
    fun `live ticks throws when neither route nor fallback supports it`() {
        val barsOnly = FakeSource("BarsOnly", setOf(MarketSourceCapability.BARS), listOf(""))
        val composite = CompositeMarketSource(routes = emptyList(), fallback = barsOnly)
        assertThatThrownBy { composite.liveTicks(listOf("X")) }
            .isInstanceOf(UnsupportedDataException::class.java)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.source.CompositeMarketSourceTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `CompositeMarketSource.kt`**

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

fun interface SymbolPattern {
    fun matches(symbol: String): Boolean

    companion object {
        fun prefix(prefix: String): SymbolPattern = SymbolPattern { it.startsWith(prefix) }

        fun exact(symbol: String): SymbolPattern = SymbolPattern { it == symbol }
    }
}

class CompositeMarketSource(
    private val routes: List<Pair<SymbolPattern, MarketSource>>,
    private val fallback: MarketSource,
) : MarketSource {
    override val name: String = "Composite"

    override val capabilities: Set<MarketSourceCapability> =
        (routes.map { it.second.capabilities } + fallback.capabilities).flatten().toSet()

    override fun supports(symbol: String): Boolean = sourceFor(symbol).supports(symbol)

    private fun sourceFor(symbol: String): MarketSource =
        routes.firstOrNull { (pat, _) -> pat.matches(symbol) }?.second ?: fallback

    override fun liveTicks(symbols: List<String>): TickFeed {
        val grouped = symbols.groupBy { sourceFor(it) }
        if (grouped.size == 1) {
            return grouped.keys.first().liveTicks(symbols)
        }
        throw UnsupportedDataException(
            MarketSourceCapability.LIVE_TICKS,
            "CompositeMarketSource cannot fan-in live feeds in Phase 7a; planned for Phase 7b",
        )
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = sourceFor(symbol).bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = sourceFor(symbol).ticks(symbol, range)
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.source.CompositeMarketSourceTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew ktlintFormat check`
Expected: 280 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt src/test/kotlin/com/qkt/marketdata/source/CompositeMarketSourceTest.kt
git commit -m "feat(marketdata): add CompositeMarketSource for symbol-pattern routing"
```

---

### Task 7: Move `Reductions` to source package

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/source/Reductions.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/ReductionsTest.kt`
- Delete: `src/main/kotlin/com/qkt/marketdata/history/Reductions.kt`
- Delete: `src/test/kotlin/com/qkt/marketdata/history/ReductionsTest.kt`

- [ ] **Step 1: Move file with package change**

```bash
mv src/main/kotlin/com/qkt/marketdata/history/Reductions.kt src/main/kotlin/com/qkt/marketdata/source/Reductions.kt
mv src/test/kotlin/com/qkt/marketdata/history/ReductionsTest.kt src/test/kotlin/com/qkt/marketdata/source/ReductionsTest.kt
sed -i 's/package com.qkt.marketdata.history/package com.qkt.marketdata.source/' src/main/kotlin/com/qkt/marketdata/source/Reductions.kt
sed -i 's/package com.qkt.marketdata.history/package com.qkt.marketdata.source/' src/test/kotlin/com/qkt/marketdata/source/ReductionsTest.kt
```

- [ ] **Step 2: Update consumers — find all imports of `com.qkt.marketdata.history.Reductions` (or any of its functions) and rewrite**

```bash
grep -rl "com.qkt.marketdata.history" src/ | xargs -r sed -i 's|com.qkt.marketdata.history|com.qkt.marketdata.source|g'
```

- [ ] **Step 3: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, 280 tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(marketdata): move Reductions to source package"
```

---

### Task 8: Delete `marketdata.history` package

**Files:**
- Delete: `src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt`
- Delete: `src/main/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProvider.kt`
- Delete: `src/test/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProviderTest.kt`

The `LocalMarketSource` from Task 5 supersedes `StoreHistoricalDataProvider`. Existing tests for the latter become redundant (the new `LocalMarketSourceTest` covers equivalent behavior plus more). Per the project's no-backwards-compat rule, we delete outright.

- [ ] **Step 1: Search for any remaining direct usage of the deprecated types**

Run:
```bash
grep -rn "HistoricalDataProvider\|StoreHistoricalDataProvider" src/ | grep -v "marketdata/history" || echo "no references"
```

If any references remain outside the to-be-deleted files, fix them inline by replacing with `MarketSource` / `LocalMarketSource`.

- [ ] **Step 2: Delete the files**

```bash
rm -r src/main/kotlin/com/qkt/marketdata/history/
rm -r src/test/kotlin/com/qkt/marketdata/history/
```

- [ ] **Step 3: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(marketdata): remove obsolete history package"
```

---

### Task 9: Add `Backtest.fromSource` factory; update `fromStore` to use `LocalMarketSource`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`
- Create: `src/test/kotlin/com/qkt/app/BacktestFromSourceTest.kt`

- [ ] **Step 1: Read the current `Backtest.kt` to understand current shape**

Run: `cat src/main/kotlin/com/qkt/app/Backtest.kt | head -50`

You should see the existing `companion object { fun fromStore(...) }` from Phase 6.

- [ ] **Step 2: Add `Backtest.fromSource` factory**

Modify the `companion object` in `Backtest.kt`:

```kotlin
    companion object {
        fun fromStore(
            strategies: List<Strategy>,
            rules: List<RiskRule> = emptyList(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
        ): Backtest = fromSource(
            strategies = strategies,
            rules = rules,
            source = LocalMarketSource(store, FixedClock(time = request.from?.toEpochMilli() ?: 0L)),
            request = request,
            candleWindow = candleWindow,
        )

        fun fromSource(
            strategies: List<Strategy>,
            rules: List<RiskRule> = emptyList(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
        ): Backtest {
            require(MarketSourceCapability.TICKS in source.capabilities) {
                "Backtest requires a MarketSource that supports TICKS; ${source.name} has ${source.capabilities}"
            }
            require(request.symbols.size == 1 || source is CompositeMarketSource || source.capabilities.containsAll(setOf(MarketSourceCapability.TICKS))) {
                "Backtest with multi-symbol requests requires a source with TICKS capability"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val range = TimeRange(from, to)
            val perSymbolFeeds: List<TickFeed> =
                request.symbols.map { sym ->
                    SequenceTickFeed(source.ticks(sym, range))
                }
            val feed: TickFeed = if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
            return Backtest(
                strategies = strategies,
                rules = rules,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = from.toEpochMilli(),
            )
        }
    }
```

Add the imports at the top of `Backtest.kt`:
```kotlin
import com.qkt.common.TimeRange
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SequenceTickFeed
```

- [ ] **Step 3: Add `SequenceTickFeed` adapter**

Create `src/main/kotlin/com/qkt/marketdata/source/SequenceTickFeed.kt`:

```kotlin
package com.qkt.marketdata.source

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

class SequenceTickFeed(
    seq: Sequence<Tick>,
) : TickFeed {
    private val iter = seq.iterator()

    override fun next(): Tick? = if (iter.hasNext()) iter.next() else null

    override fun close() {}
}
```

- [ ] **Step 4: Write integration test for `Backtest.fromSource`**

Create `src/test/kotlin/com/qkt/app/BacktestFromSourceTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.common.Money
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.common.SystemClock
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestFromSourceTest {
    private val sample = Path.of("data/sample")

    @Test
    fun `fromSource runs against LocalMarketSource end to end`() {
        val store = DefaultDataStore(root = sample)
        val source = LocalMarketSource(store, SystemClock())
        val request =
            MarketRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest.fromSource(
                strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1)),
                source = source,
                request = request,
            ).run()
        assertThat(result.tradeCount).isEqualTo(10)
    }

    @Test
    fun `fromSource matches fromStore output for the same data`() {
        val store = DefaultDataStore(root = sample)
        val source = LocalMarketSource(store, SystemClock())
        val request =
            MarketRequest(
                symbols = listOf("EURUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )

        val viaSource =
            Backtest.fromSource(
                strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2, size = Money.of("1"))),
                source = source,
                request = request,
            ).run()

        val viaStore =
            Backtest.fromStore(
                strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2, size = Money.of("1"))),
                store = store,
                request = request,
            ).run()

        assertThat(viaSource.tradeCount).isEqualTo(viaStore.tradeCount)
        assertThat(viaSource.totalPnL).isEqualByComparingTo(viaStore.totalPnL)
    }
}
```

- [ ] **Step 5: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL. The existing `BacktestFromStoreTest` continues to pass via the updated `fromStore` factory; the new `BacktestFromSourceTest` adds 2 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(app): add Backtest.fromSource factory consuming MarketSource"
```

---

## Group B: TimeMark + Calendar + Anchor

### Task 10: Add `SessionAnchor` sealed class

**Files:**
- Create: `src/main/kotlin/com/qkt/common/SessionAnchor.kt`

- [ ] **Step 1: Create the sealed class**

```kotlin
package com.qkt.common

import java.time.Duration

sealed class SessionAnchor {
    object PreviousDay : SessionAnchor()

    object CurrentSession : SessionAnchor()

    object PreviousSession : SessionAnchor()

    data class Rolling(val duration: Duration) : SessionAnchor() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "Rolling duration must be positive: $duration"
            }
        }
    }
}
```

- [ ] **Step 2: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/common/SessionAnchor.kt
git commit -m "feat(common): add SessionAnchor sealed class"
```

---

### Task 11: Add `TradingCalendar` interface + `crypto()` impl

**Files:**
- Create: `src/main/kotlin/com/qkt/common/TradingCalendar.kt`
- Create: `src/main/kotlin/com/qkt/common/CryptoCalendar.kt`
- Create: `src/test/kotlin/com/qkt/common/CryptoCalendarTest.kt`

- [ ] **Step 1: Define the interface**

`src/main/kotlin/com/qkt/common/TradingCalendar.kt`:

```kotlin
package com.qkt.common

import java.time.Instant

interface TradingCalendar {
    val name: String

    fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean

    fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange

    fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long

    fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar
    }
}
```

- [ ] **Step 2: Write failing test for the crypto calendar**

`src/test/kotlin/com/qkt/common/CryptoCalendarTest.kt`:

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CryptoCalendarTest {
    private val cal = TradingCalendar.crypto()
    private val midnight = Instant.parse("2024-01-15T00:00:00Z")

    @Test
    fun `name is crypto`() {
        assertThat(cal.name).isEqualTo("crypto")
    }

    @Test
    fun `is in session at any time`() {
        assertThat(cal.isInSession("BTCUSD", midnight)).isTrue()
        assertThat(cal.isInSession("BTCUSD", midnight.plus(Duration.ofHours(3)))).isTrue()
        assertThat(cal.isInSession("BTCUSD", midnight.plus(Duration.ofDays(7)))).isTrue()
    }

    @Test
    fun `sessionRange for crypto is the calendar UTC day containing t`() {
        val r = cal.sessionRange("BTCUSD", midnight.plus(Duration.ofHours(3)))
        assertThat(r.from).isEqualTo(midnight)
        assertThat(r.to).isEqualTo(midnight.plus(Duration.ofDays(1)))
    }

    @Test
    fun `previous day anchor returns yesterday epoch`() {
        val today = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, today)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `current session anchor returns today epoch`() {
        val today = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.CurrentSession, today)
        val range = cal.rangeFor(SessionAnchor.CurrentSession, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }

    @Test
    fun `rolling anchor returns the duration ending at t`() {
        val now = Instant.parse("2024-01-15T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.Rolling(Duration.ofHours(6)), now)
        val range = cal.rangeFor(SessionAnchor.Rolling(Duration.ofHours(6)), key)
        assertThat(range.to).isEqualTo(now)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T09:00:00Z"))
    }
}
```

- [ ] **Step 3: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.CryptoCalendarTest"`
Expected: compile failure (`CryptoCalendar` does not exist).

- [ ] **Step 4: Implement `CryptoCalendar`**

`src/main/kotlin/com/qkt/common/CryptoCalendar.kt`:

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object CryptoCalendar : TradingCalendar {
    override val name: String = "crypto"

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean = true

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val day = t.atZone(ZoneOffset.UTC).toLocalDate()
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = start.plus(Duration.ofDays(1))
        return TimeRange(start, end)
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long =
        when (anchor) {
            SessionAnchor.PreviousDay -> dayEpoch(t) - 1
            SessionAnchor.CurrentSession -> dayEpoch(t)
            SessionAnchor.PreviousSession -> dayEpoch(t) - 1
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange =
        when (anchor) {
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            SessionAnchor.CurrentSession -> {
                val start = Instant.ofEpochSecond(anchorEpoch * 86_400L)
                TimeRange(start, start.plus(Duration.ofDays(1)))
            }
            is SessionAnchor.Rolling -> {
                val end = Instant.ofEpochMilli(anchorEpoch)
                TimeRange(end.minus(anchor.duration), end)
            }
        }

    private fun dayEpoch(t: Instant): Long = t.epochSecond / 86_400L
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.CryptoCalendarTest"`
Expected: 6 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, ~288 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/common/TradingCalendar.kt src/main/kotlin/com/qkt/common/CryptoCalendar.kt src/test/kotlin/com/qkt/common/CryptoCalendarTest.kt
git commit -m "feat(common): add TradingCalendar interface and crypto calendar"
```

---

### Task 12: Add `fxDefault()` calendar (24/5 with weekend gap)

**Files:**
- Create: `src/main/kotlin/com/qkt/common/FxCalendar.kt`
- Create: `src/test/kotlin/com/qkt/common/FxCalendarTest.kt`
- Modify: `src/main/kotlin/com/qkt/common/TradingCalendar.kt` — add `fxDefault()` factory

FX market is open 24 hours from Sunday 17:00 ET (22:00 UTC) until Friday 17:00 ET (22:00 UTC). For Phase 7 we use the 22:00 UTC simplification (no DST handling — too narrow a benefit). Sessions are anchored to the FX trading day (which starts at 22:00 UTC the previous calendar day).

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FxCalendarTest {
    private val cal = TradingCalendar.fxDefault()

    @Test
    fun `name is fx`() {
        assertThat(cal.name).isEqualTo("fx")
    }

    @Test
    fun `weekday afternoon UTC is in session`() {
        val mon = Instant.parse("2024-01-15T15:00:00Z")
        assertThat(cal.isInSession("EURUSD", mon)).isTrue()
    }

    @Test
    fun `saturday morning is closed`() {
        val sat = Instant.parse("2024-01-13T08:00:00Z")
        assertThat(cal.isInSession("EURUSD", sat)).isFalse()
    }

    @Test
    fun `friday before 22 UTC is in session`() {
        val fri = Instant.parse("2024-01-12T21:59:00Z")
        assertThat(cal.isInSession("EURUSD", fri)).isTrue()
    }

    @Test
    fun `friday at 22 UTC is closed`() {
        val fri = Instant.parse("2024-01-12T22:00:00Z")
        assertThat(cal.isInSession("EURUSD", fri)).isFalse()
    }

    @Test
    fun `sunday at 22 UTC reopens`() {
        val sun = Instant.parse("2024-01-14T22:00:00Z")
        assertThat(cal.isInSession("EURUSD", sun)).isTrue()
    }

    @Test
    fun `sessionRange for weekday returns the FX day from 22 UTC of previous calendar day to 22 UTC of t`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val r = cal.sessionRange("EURUSD", mon)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
    }

    @Test
    fun `previous day anchor on monday returns sunday-night-into-monday FX day`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, mon)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-13T22:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
    }

    @Test
    fun `current session anchor on monday returns the in-progress monday FX day`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.CurrentSession, mon)
        val range = cal.rangeFor(SessionAnchor.CurrentSession, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
    }

    @Test
    fun `rolling anchor independent of session boundaries`() {
        val mon = Instant.parse("2024-01-15T10:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.Rolling(Duration.ofHours(2)), mon)
        val range = cal.rangeFor(SessionAnchor.Rolling(Duration.ofHours(2)), key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-15T08:00:00Z"))
        assertThat(range.to).isEqualTo(mon)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.FxCalendarTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `FxCalendar`**

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object FxCalendar : TradingCalendar {
    override val name: String = "fx"

    private val sessionStartUtcHour = 22

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(ZoneOffset.UTC)
        val dow = zdt.dayOfWeek.value
        val hour = zdt.hour
        return when (dow) {
            6 -> false
            7 -> hour >= sessionStartUtcHour
            5 -> hour < sessionStartUtcHour
            else -> true
        }
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val end = sessionEnd(t)
        val start = end.minus(Duration.ofDays(1))
        return TimeRange(start, end)
    }

    private fun sessionEnd(t: Instant): Instant {
        val day = t.atZone(ZoneOffset.UTC).toLocalDate()
        val candidate = day.atTime(sessionStartUtcHour, 0).toInstant(ZoneOffset.UTC)
        return if (t < candidate) candidate else candidate.plus(Duration.ofDays(1))
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val end = sessionEnd(t)
        return when (anchor) {
            SessionAnchor.CurrentSession -> end.toEpochMilli()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession -> end.minus(Duration.ofDays(1)).toEpochMilli()
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }
    }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange {
        val end = Instant.ofEpochMilli(anchorEpoch)
        return when (anchor) {
            SessionAnchor.CurrentSession,
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession -> TimeRange(end.minus(Duration.ofDays(1)), end)
            is SessionAnchor.Rolling -> TimeRange(end.minus(anchor.duration), end)
        }
    }
}
```

- [ ] **Step 4: Add factory in `TradingCalendar`**

Modify `src/main/kotlin/com/qkt/common/TradingCalendar.kt` companion:

```kotlin
    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar
        fun fxDefault(): TradingCalendar = FxCalendar
    }
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.FxCalendarTest"`
Expected: 10 tests PASS.

- [ ] **Step 6: Full check + commit**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/kotlin/com/qkt/common/TradingCalendar.kt src/main/kotlin/com/qkt/common/FxCalendar.kt src/test/kotlin/com/qkt/common/FxCalendarTest.kt
git commit -m "feat(common): add fxDefault calendar with 24/5 session boundaries"
```

---

### Task 13: Add `nyse()` calendar (9:30–16:00 ET with US holidays)

**Files:**
- Create: `src/main/kotlin/com/qkt/common/NyseCalendar.kt`
- Create: `src/test/kotlin/com/qkt/common/NyseCalendarTest.kt`
- Modify: `src/main/kotlin/com/qkt/common/TradingCalendar.kt` — add `nyse()` factory

US holidays handled: New Year's Day, MLK, Presidents Day, Good Friday, Memorial Day, Juneteenth, Independence Day, Labor Day, Thanksgiving, Christmas. Half-days (early close at 13:00 ET) are out of scope for Phase 7a — we treat them as full sessions and document in the changelog.

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NyseCalendarTest {
    private val cal = TradingCalendar.nyse()

    @Test
    fun `name is nyse`() {
        assertThat(cal.name).isEqualTo("nyse")
    }

    @Test
    fun `weekday in session window is open`() {
        val t = Instant.parse("2024-01-16T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isTrue()
    }

    @Test
    fun `weekday before 9_30 ET is closed`() {
        val t = Instant.parse("2024-01-16T13:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `weekday after 16_00 ET is closed`() {
        val t = Instant.parse("2024-01-16T22:30:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `saturday is closed`() {
        val t = Instant.parse("2024-01-13T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `MLK day 2024 is closed`() {
        val t = Instant.parse("2024-01-15T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `christmas day 2024 is closed`() {
        val t = Instant.parse("2024-12-25T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `independence day 2024 is closed`() {
        val t = Instant.parse("2024-07-04T15:00:00Z")
        assertThat(cal.isInSession("AAPL", t)).isFalse()
    }

    @Test
    fun `previous day anchor returns previous trading day`() {
        val mon = Instant.parse("2024-01-16T15:00:00Z")
        val key = cal.anchorEpochFor(SessionAnchor.PreviousDay, mon)
        val range = cal.rangeFor(SessionAnchor.PreviousDay, key)
        assertThat(range.from).isEqualTo(Instant.parse("2024-01-12T14:30:00Z"))
        assertThat(range.to).isEqualTo(Instant.parse("2024-01-12T21:00:00Z"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.NyseCalendarTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `NyseCalendar`**

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object NyseCalendar : TradingCalendar {
    override val name: String = "nyse"

    private val zone = ZoneId.of("America/New_York")
    private val openTime = LocalTime.of(9, 30)
    private val closeTime = LocalTime.of(16, 0)

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(zone)
        val date = zdt.toLocalDate()
        if (zdt.dayOfWeek.value >= 6) return false
        if (isHoliday(date)) return false
        val tod = zdt.toLocalTime()
        return tod >= openTime && tod < closeTime
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val date = t.atZone(zone).toLocalDate()
        val start = date.atTime(openTime).atZone(zone).toInstant()
        val end = date.atTime(closeTime).atZone(zone).toInstant()
        return TimeRange(start, end)
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val date = t.atZone(zone).toLocalDate()
        return when (anchor) {
            SessionAnchor.CurrentSession -> date.toEpochDay()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession -> previousTradingDay(date).toEpochDay()
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }
    }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange =
        when (anchor) {
            SessionAnchor.CurrentSession,
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession -> {
                val date = LocalDate.ofEpochDay(anchorEpoch)
                val start = date.atTime(openTime).atZone(zone).toInstant()
                val end = date.atTime(closeTime).atZone(zone).toInstant()
                TimeRange(start, end)
            }
            is SessionAnchor.Rolling -> {
                val end = Instant.ofEpochMilli(anchorEpoch)
                TimeRange(end.minus(anchor.duration), end)
            }
        }

    private fun previousTradingDay(date: LocalDate): LocalDate {
        var d = date.minusDays(1)
        while (d.dayOfWeek.value >= 6 || isHoliday(d)) d = d.minusDays(1)
        return d
    }

    private fun isHoliday(d: LocalDate): Boolean {
        val year = d.year
        return d in holidaysFor(year)
    }

    private val holidayCache = mutableMapOf<Int, Set<LocalDate>>()

    private fun holidaysFor(year: Int): Set<LocalDate> =
        holidayCache.getOrPut(year) { computeHolidays(year) }

    private fun computeHolidays(year: Int): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        days.add(observed(LocalDate.of(year, 1, 1)))
        days.add(LocalDate.of(year, 1, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.MONDAY)))
        days.add(LocalDate.of(year, 2, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.MONDAY)))
        days.add(goodFriday(year))
        days.add(LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(java.time.DayOfWeek.MONDAY)))
        if (year >= 2022) days.add(observed(LocalDate.of(year, 6, 19)))
        days.add(observed(LocalDate.of(year, 7, 4)))
        days.add(LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(java.time.DayOfWeek.MONDAY)))
        days.add(LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, java.time.DayOfWeek.THURSDAY)))
        days.add(observed(LocalDate.of(year, 12, 25)))
        return days
    }

    private fun observed(d: LocalDate): LocalDate =
        when (d.dayOfWeek) {
            java.time.DayOfWeek.SATURDAY -> d.minusDays(1)
            java.time.DayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }

    private fun goodFriday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        val easter = LocalDate.of(year, month, day)
        return easter.minusDays(2)
    }
}
```

- [ ] **Step 4: Add factory in `TradingCalendar`**

```kotlin
    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar
        fun fxDefault(): TradingCalendar = FxCalendar
        fun nyse(): TradingCalendar = NyseCalendar
    }
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.NyseCalendarTest"`
Expected: 9 tests PASS.

- [ ] **Step 6: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/common/TradingCalendar.kt src/main/kotlin/com/qkt/common/NyseCalendar.kt src/test/kotlin/com/qkt/common/NyseCalendarTest.kt
git commit -m "feat(common): add NYSE calendar with US holiday handling"
```

---

### Task 14: Add `TimeMark` sealed class

**Files:**
- Create: `src/main/kotlin/com/qkt/common/TimeMark.kt`

- [ ] **Step 1: Create the sealed class**

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

sealed class TimeMark {
    object Now : TimeMark()

    data class Absolute(val instant: Instant) : TimeMark()

    data class AtSessionAnchor(
        val anchor: SessionAnchor,
        val timeOfDay: LocalTime? = null,
    ) : TimeMark()

    data class RelativeToNow(val offset: Duration) : TimeMark()
}
```

- [ ] **Step 2: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/common/TimeMark.kt
git commit -m "feat(common): add TimeMark sealed class"
```

---

### Task 15: Add `TimeRange.of(from, to, clock, calendar)` builder

**Files:**
- Modify: `src/main/kotlin/com/qkt/common/TimeRange.kt`
- Create: `src/test/kotlin/com/qkt/common/TimeRangeOfTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeRangeOfTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val clock = FixedClock(time = now.toEpochMilli())
    private val cal = TradingCalendar.crypto()

    @Test
    fun `Now resolves to clock now`() {
        val r = TimeRange.of(TimeMark.RelativeToNow(Duration.ofMinutes(-1)), TimeMark.Now, clock, cal)
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `Absolute resolves to its instant`() {
        val abs = Instant.parse("2024-01-14T00:00:00Z")
        val r =
            TimeRange.of(
                TimeMark.Absolute(abs),
                TimeMark.Absolute(now),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(abs)
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `AtSessionAnchor without timeOfDay resolves to anchor range bounds`() {
        val r =
            TimeRange.of(
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay),
                TimeMark.AtSessionAnchor(SessionAnchor.CurrentSession),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `AtSessionAnchor with timeOfDay clamps to that time within the anchor range`() {
        val r =
            TimeRange.of(
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay, LocalTime.of(10, 0)),
                TimeMark.AtSessionAnchor(SessionAnchor.PreviousDay, LocalTime.of(18, 0)),
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T10:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-14T18:00:00Z"))
    }

    @Test
    fun `RelativeToNow with negative offset resolves to past`() {
        val r =
            TimeRange.of(
                TimeMark.RelativeToNow(Duration.ofHours(-3)),
                TimeMark.Now,
                clock,
                cal,
            )
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T12:00:00Z"))
        assertThat(r.to).isEqualTo(now)
    }

    @Test
    fun `inverted range throws`() {
        assertThatThrownBy {
            TimeRange.of(TimeMark.Now, TimeMark.RelativeToNow(Duration.ofHours(-1)), clock, cal)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `range to in the future throws look-ahead`() {
        assertThatThrownBy {
            TimeRange.of(
                TimeMark.RelativeToNow(Duration.ofHours(-1)),
                TimeMark.RelativeToNow(Duration.ofHours(1)),
                clock,
                cal,
            )
        }.hasMessageContaining("look-ahead")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.TimeRangeOfTest"`
Expected: compile failure (`TimeRange.Companion.of` does not exist).

- [ ] **Step 3: Add the builder**

Append to `src/main/kotlin/com/qkt/common/TimeRange.kt`:

```kotlin
package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

data class TimeRange(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from < to) { "TimeRange requires from < to: from=$from, to=$to" }
    }

    val durationMs: Long get() = to.toEpochMilli() - from.toEpochMilli()

    operator fun contains(t: Instant): Boolean = t >= from && t < to

    companion object {
        fun of(
            from: TimeMark,
            to: TimeMark,
            clock: Clock,
            calendar: TradingCalendar,
        ): TimeRange {
            val now = Instant.ofEpochMilli(clock.now())
            val resolvedFrom = resolve(from, now, calendar, isStart = true)
            val resolvedTo = resolve(to, now, calendar, isStart = false)
            require(resolvedFrom < resolvedTo) {
                "Inverted TimeRange: from=$resolvedFrom to=$resolvedTo"
            }
            require(resolvedTo <= now) {
                "look-ahead bias: TimeRange.to ($resolvedTo) must be <= now ($now)"
            }
            return TimeRange(resolvedFrom, resolvedTo)
        }

        private fun resolve(
            mark: TimeMark,
            now: Instant,
            cal: TradingCalendar,
            isStart: Boolean,
        ): Instant =
            when (mark) {
                is TimeMark.Now -> now
                is TimeMark.Absolute -> mark.instant
                is TimeMark.RelativeToNow -> now.plus(mark.offset)
                is TimeMark.AtSessionAnchor -> resolveAnchor(mark, now, cal, isStart)
            }

        private fun resolveAnchor(
            mark: TimeMark.AtSessionAnchor,
            now: Instant,
            cal: TradingCalendar,
            isStart: Boolean,
        ): Instant {
            val anchorEpoch = cal.anchorEpochFor(mark.anchor, now)
            val range = cal.rangeFor(mark.anchor, anchorEpoch)
            if (mark.timeOfDay == null) {
                return if (isStart) range.from else range.to
            }
            val day = range.from.atZone(ZoneOffset.UTC).toLocalDate()
            return day.atTime(mark.timeOfDay).toInstant(ZoneOffset.UTC)
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.TimeRangeOfTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/common/TimeRange.kt src/test/kotlin/com/qkt/common/TimeRangeOfTest.kt
git commit -m "feat(common): add TimeRange.of builder for TimeMark composition"
```

---

### Task 16: Add `RefreshTrigger` sealed class

**Files:**
- Create: `src/main/kotlin/com/qkt/common/RefreshTrigger.kt`

- [ ] **Step 1: Create the sealed class**

```kotlin
package com.qkt.common

import java.time.LocalTime

sealed class RefreshTrigger {
    object Once : RefreshTrigger()

    data class EveryNTicks(val n: Int) : RefreshTrigger() {
        init {
            require(n > 0) { "EveryNTicks requires n > 0: $n" }
        }
    }

    data class OnAnchorRollover(
        val anchor: SessionAnchor,
        val calendar: TradingCalendar,
    ) : RefreshTrigger()

    object OnSessionRollover : RefreshTrigger()

    data class OnTimeOfDay(val time: LocalTime) : RefreshTrigger()
}
```

- [ ] **Step 2: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/common/RefreshTrigger.kt
git commit -m "feat(common): add RefreshTrigger sealed class"
```

---

## Group C: Range-aggregate indicators

### Task 17: Add `RangeAggregateIndicator` base machinery

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/range/RangeAggregateIndicator.kt`
- Create: `src/test/kotlin/com/qkt/indicators/range/RangeAggregateIndicatorTest.kt`

`RangeAggregateIndicator` is decoupled from the `Indicator<TIn>` interface. It exposes its own `update(tick: Tick)` and `value(): T?` so it can be generic in T (whereas `Indicator` is fixed to `BigDecimal?`). Strategies hold `RangeAggregateIndicator` instances directly.

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RangeAggregateIndicatorTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val clock = FixedClock(time = day15.plusSeconds(60).toEpochMilli())
    private val cal = TradingCalendar.crypto()

    private fun candle(price: String, ts: Long) =
        Candle("X", Money.of(price), Money.of(price), Money.of(price), Money.of(price), Money.ZERO, ts, ts + 60_000L)

    private class FakeSource(private val seq: Sequence<Candle>) : MarketSource {
        override val name = "Fake"
        override val capabilities = setOf(MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> = seq
    }

    @Test
    fun `caches reduce result and updates on refresh trigger`() {
        var calls = 0
        val source =
            FakeSource(
                generateSequence(0L) { it + 1 }
                    .take(3)
                    .map { i -> candle((100 + i).toString(), day15.plusSeconds(i).toEpochMilli()) },
            )
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = {
                    calls++
                    TimeRange(day15.minusSeconds(60), day15)
                },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )

        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        indicator.update(Tick("X", Money.of("100"), day15.plusSeconds(1).toEpochMilli()))
        indicator.update(Tick("X", Money.of("100"), day15.plusSeconds(2).toEpochMilli()))

        assertThat(indicator.value()).isEqualByComparingTo(Money.of("102"))
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `EveryNTicks refresh recomputes every n ticks`() {
        var calls = 0
        val source =
            FakeSource(
                sequenceOf(candle("100", day15.toEpochMilli())),
            )
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = {
                    calls++
                    TimeRange(day15.minusSeconds(60), day15)
                },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.EveryNTicks(3),
            )

        repeat(7) { i ->
            indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli() + i))
        }

        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `value is null until first update`() {
        val source = FakeSource(sequenceOf(candle("100", day15.toEpochMilli())))
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = { TimeRange(day15.minusSeconds(60), day15) },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )
        assertThat(indicator.value()).isNull()
        assertThat(indicator.isReady).isFalse()
    }

    @Test
    fun `isReady becomes true after a successful reduce`() {
        val source = FakeSource(sequenceOf(candle("100", day15.toEpochMilli())))
        val indicator =
            RangeAggregateIndicator(
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                rangeSpec = { TimeRange(day15.minusSeconds(60), day15) },
                reduce = { it.maxOfOrNull { c -> c.high } },
                source = source,
                clock = clock,
                refreshOn = RefreshTrigger.Once,
            )
        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        assertThat(indicator.isReady).isTrue()
        assertThat(indicator.value()).isEqualByComparingTo<BigDecimal>(Money.of("100"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.indicators.range.RangeAggregateIndicatorTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource

open class RangeAggregateIndicator<T : Any>(
    private val symbol: String,
    private val window: TimeWindow,
    private val rangeSpec: () -> TimeRange,
    private val reduce: (Sequence<Candle>) -> T?,
    private val source: MarketSource,
    private val clock: Clock,
    private val refreshOn: RefreshTrigger,
) {
    private var cached: T? = null
    private var lastRefreshKey: Long = Long.MIN_VALUE
    private var ticksSinceRefresh: Int = 0

    fun update(tick: Tick) {
        if (tick.symbol != symbol) return
        val key = currentRefreshKey(tick)
        if (key != lastRefreshKey) {
            cached = reduce(source.bars(symbol, window, rangeSpec()))
            lastRefreshKey = key
        }
    }

    fun value(): T? = cached

    val isReady: Boolean get() = cached != null

    private fun currentRefreshKey(tick: Tick): Long {
        val now = java.time.Instant.ofEpochMilli(clock.now())
        return when (val r = refreshOn) {
            is RefreshTrigger.Once -> if (lastRefreshKey == Long.MIN_VALUE) 0L else lastRefreshKey
            is RefreshTrigger.EveryNTicks -> {
                ticksSinceRefresh++
                if (ticksSinceRefresh >= r.n) {
                    ticksSinceRefresh = 0
                    lastRefreshKey + 1
                } else {
                    lastRefreshKey
                }
            }
            is RefreshTrigger.OnAnchorRollover -> r.calendar.anchorEpochFor(r.anchor, now)
            RefreshTrigger.OnSessionRollover -> now.epochSecond / 86_400L
            is RefreshTrigger.OnTimeOfDay -> {
                val zdt = now.atZone(java.time.ZoneOffset.UTC)
                val today = zdt.toLocalDate().atTime(r.time).toInstant(java.time.ZoneOffset.UTC)
                if (now >= today) today.toEpochMilli() else today.minusSeconds(86_400).toEpochMilli()
            }
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.indicators.range.RangeAggregateIndicatorTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/indicators/range/RangeAggregateIndicator.kt src/test/kotlin/com/qkt/indicators/range/RangeAggregateIndicatorTest.kt
git commit -m "feat(indicators): add RangeAggregateIndicator base"
```

---

### Task 18: Add `SessionAnchoredIndicator` (sugar)

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/range/SessionAnchoredIndicator.kt`
- Create: `src/test/kotlin/com/qkt/indicators/range/SessionAnchoredIndicatorTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionAnchoredIndicatorTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")

    private fun candle(price: String, startMs: Long) =
        Candle("X", Money.of(price), Money.of(price), Money.of(price), Money.of(price), Money.ZERO, startMs, startMs + 60_000L)

    private class FakeSource(private val byRangeStartMs: Map<Long, List<Candle>>) : MarketSource {
        override val name = "Fake"
        override val capabilities = setOf(MarketSourceCapability.BARS)
        override fun supports(symbol: String): Boolean = true
        override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> =
            byRangeStartMs[range.from.toEpochMilli()]?.asSequence() ?: emptySequence()
    }

    @Test
    fun `previous-day anchor refreshes when day rolls over`() {
        val day14Start = day15.minusSeconds(86_400).toEpochMilli()
        val day13Start = day14Start - 86_400_000L
        val source =
            FakeSource(
                mapOf(
                    day14Start to listOf(candle("110", day14Start)),
                    day13Start to listOf(candle("105", day13Start)),
                ),
            )

        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator =
            object : SessionAnchoredIndicator<BigDecimal>(
                anchor = SessionAnchor.PreviousDay,
                calendar = TradingCalendar.crypto(),
                symbol = "X",
                window = TimeWindow.ONE_MINUTE,
                source = source,
                clock = clock,
                reduce = { it.maxOfOrNull { c -> c.high } },
            ) {}

        indicator.update(Tick("X", Money.of("100"), day15.toEpochMilli()))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("110"))

        clock.time = day15.plusSeconds(86_400).toEpochMilli()
        indicator.update(Tick("X", Money.of("100"), clock.time))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("110"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.indicators.range.SessionAnchoredIndicatorTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.RefreshTrigger
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource

abstract class SessionAnchoredIndicator<T : Any>(
    anchor: SessionAnchor,
    calendar: TradingCalendar,
    symbol: String,
    window: TimeWindow,
    source: MarketSource,
    clock: Clock,
    reduce: (Sequence<Candle>) -> T?,
) : RangeAggregateIndicator<T>(
        symbol = symbol,
        window = window,
        rangeSpec = {
            calendar.rangeFor(anchor, calendar.anchorEpochFor(anchor, java.time.Instant.ofEpochMilli(clock.now())))
        },
        reduce = reduce,
        source = source,
        clock = clock,
        refreshOn = RefreshTrigger.OnAnchorRollover(anchor, calendar),
    )
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.indicators.range.SessionAnchoredIndicatorTest"`
Expected: 1 test PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/indicators/range/SessionAnchoredIndicator.kt src/test/kotlin/com/qkt/indicators/range/SessionAnchoredIndicatorTest.kt
git commit -m "feat(indicators): add SessionAnchoredIndicator as RangeAggregateIndicator sugar"
```

---

### Task 19: Add `PreviousDayHigh` and `PreviousDayLow`

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/range/PreviousDayHigh.kt`
- Create: `src/main/kotlin/com/qkt/indicators/range/PreviousDayLow.kt`
- Create: `src/test/kotlin/com/qkt/indicators/range/PreviousDayExtremesTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PreviousDayExtremesTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z")
    private val day14Start = day15.minusSeconds(86_400).toEpochMilli()

    private fun candle(open: String, high: String, low: String, close: String, startMs: Long) =
        Candle("X", Money.of(open), Money.of(high), Money.of(low), Money.of(close), Money.ZERO, startMs, startMs + 60_000L)

    private val source =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)
            override fun supports(symbol: String): Boolean = true
            override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> {
                if (range.from.toEpochMilli() == day14Start) {
                    return sequenceOf(
                        candle("100", "110", "95", "105", day14Start),
                        candle("105", "115", "102", "108", day14Start + 60_000L),
                    )
                }
                return emptySequence()
            }
        }

    @Test
    fun `PreviousDayHigh returns max of all previous-day candle highs`() {
        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator = PreviousDayHigh("X", TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("120"), clock.time))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("115"))
    }

    @Test
    fun `PreviousDayLow returns min of all previous-day candle lows`() {
        val clock = FixedClock(time = day15.toEpochMilli())
        val indicator = PreviousDayLow("X", TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("80"), clock.time))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("95"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.indicators.range.PreviousDayExtremesTest"`
Expected: compile failure.

- [ ] **Step 3: Implement both indicators**

`src/main/kotlin/com/qkt/indicators/range/PreviousDayHigh.kt`:

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

class PreviousDayHigh(
    symbol: String,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = SessionAnchor.PreviousDay,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.maxOfOrNull { c -> c.high } },
    )
```

`src/main/kotlin/com/qkt/indicators/range/PreviousDayLow.kt`:

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

class PreviousDayLow(
    symbol: String,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = SessionAnchor.PreviousDay,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.minOfOrNull { c -> c.low } },
    )
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.indicators.range.PreviousDayExtremesTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/indicators/range/PreviousDayHigh.kt src/main/kotlin/com/qkt/indicators/range/PreviousDayLow.kt src/test/kotlin/com/qkt/indicators/range/PreviousDayExtremesTest.kt
git commit -m "feat(indicators): add PreviousDayHigh and PreviousDayLow"
```

---

### Task 20: Add `SessionHigh` and `SessionLow`

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/range/SessionHigh.kt`
- Create: `src/main/kotlin/com/qkt/indicators/range/SessionLow.kt`
- Create: `src/test/kotlin/com/qkt/indicators/range/SessionExtremesTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionExtremesTest {
    private val day15Start = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private val source =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)
            override fun supports(symbol: String): Boolean = true
            override fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> =
                if (range.from.toEpochMilli() == day15Start) {
                    sequenceOf(
                        Candle("X", Money.of("100"), Money.of("125"), Money.of("90"), Money.of("110"), Money.ZERO, day15Start, day15Start + 60_000L),
                    )
                } else {
                    emptySequence()
                }
        }

    @Test
    fun `SessionHigh on current session returns max high`() {
        val clock = FixedClock(time = day15Start + 3_600_000L)
        val indicator = SessionHigh("X", SessionAnchor.CurrentSession, TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("120"), clock.time))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("125"))
    }

    @Test
    fun `SessionLow on current session returns min low`() {
        val clock = FixedClock(time = day15Start + 3_600_000L)
        val indicator = SessionLow("X", SessionAnchor.CurrentSession, TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("100"), clock.time))
        assertThat(indicator.value()).isEqualByComparingTo(Money.of("90"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.indicators.range.SessionExtremesTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

`SessionHigh.kt`:
```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

class SessionHigh(
    symbol: String,
    anchor: SessionAnchor,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = anchor,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.maxOfOrNull { c -> c.high } },
    )
```

`SessionLow.kt`:
```kotlin
package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SessionAnchor
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal

class SessionLow(
    symbol: String,
    anchor: SessionAnchor,
    calendar: TradingCalendar,
    source: MarketSource,
    clock: Clock,
    window: TimeWindow = TimeWindow.ONE_MINUTE,
) : SessionAnchoredIndicator<BigDecimal>(
        anchor = anchor,
        calendar = calendar,
        symbol = symbol,
        window = window,
        source = source,
        clock = clock,
        reduce = { it.minOfOrNull { c -> c.low } },
    )
```

- [ ] **Step 4: Confirm GREEN + full check + commit**

```bash
./gradlew test --tests "com.qkt.indicators.range.SessionExtremesTest"
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/indicators/range/SessionHigh.kt src/main/kotlin/com/qkt/indicators/range/SessionLow.kt src/test/kotlin/com/qkt/indicators/range/SessionExtremesTest.kt
git commit -m "feat(indicators): add SessionHigh and SessionLow"
```

---

## Group D: SessionContext + Strategy extension

### Task 21: Add `Mode` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/Mode.kt`

- [ ] **Step 1: Create + commit**

```kotlin
package com.qkt.strategy

enum class Mode {
    BACKTEST,
    LIVE,
}
```

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/Mode.kt
git commit -m "feat(strategy): add Mode enum"
```

---

### Task 22: Add `SessionContext` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/SessionContext.kt`

- [ ] **Step 1: Create + commit**

```kotlin
package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource

data class SessionContext(
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
)
```

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/SessionContext.kt
git commit -m "feat(strategy): add SessionContext bundle"
```

---

### Task 23: Add `Strategy.onTickWithContext` default method bridge

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/Strategy.kt`
- Create: `src/test/kotlin/com/qkt/strategy/StrategyContextBridgeTest.kt`

- [ ] **Step 1: Read current Strategy interface**

Run: `cat src/main/kotlin/com/qkt/strategy/Strategy.kt`

Expected: a single `interface Strategy { fun onTick(tick: Tick, emit: (Signal) -> Unit) }` definition.

- [ ] **Step 2: Add the bridge method**

Modify `Strategy.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    )

    fun onTickWithContext(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        onTick(tick, emit)
    }
}
```

- [ ] **Step 3: Write a test verifying the bridge**

```kotlin
package com.qkt.strategy

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyContextBridgeTest {
    private val tick = Tick("X", Money.of("100"), 0L)
    private val ctx =
        SessionContext(
            mode = Mode.BACKTEST,
            clock = FixedClock(time = 0L),
            calendar = TradingCalendar.crypto(),
            source = object : MarketSource {
                override val name = "Empty"
                override val capabilities = emptySet<MarketSourceCapability>()
                override fun supports(symbol: String): Boolean = false
            },
        )

    @Test
    fun `default onTickWithContext routes to onTick`() {
        var called = 0
        val s = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                called++
            }
        }
        s.onTickWithContext(tick, ctx, {})
        assertThat(called).isEqualTo(1)
    }

    @Test
    fun `overriding onTickWithContext uses the context`() {
        val seenModes = mutableListOf<Mode>()
        val s = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                throw IllegalStateException("should not be called when context override is present")
            }

            override fun onTickWithContext(tick: Tick, ctx: SessionContext, emit: (Signal) -> Unit) {
                seenModes.add(ctx.mode)
            }
        }
        s.onTickWithContext(tick, ctx, {})
        assertThat(seenModes).containsExactly(Mode.BACKTEST)
    }
}
```

- [ ] **Step 4: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/Strategy.kt src/test/kotlin/com/qkt/strategy/StrategyContextBridgeTest.kt
git commit -m "feat(strategy): add onTickWithContext bridge default method"
```

---

### Task 24: Wire `SessionContext` through `Backtest` and `TradingPipeline`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`

- [ ] **Step 1: Read current `TradingPipeline.kt`**

Find the strategy invocation site (`strategy.onTick(...)` call). It will be replaced with `strategy.onTickWithContext(tick, ctx, emit)`.

- [ ] **Step 2: Modify `TradingPipeline` to take a `SessionContext` and route through `onTickWithContext`**

Locate the constructor parameter list of `TradingPipeline` and add `private val sessionContext: SessionContext`.

Locate the strategy dispatch (typically in an `ingest` method) and change:
```kotlin
strategies.forEach { it.onTick(tick) { signal -> ... } }
```
to:
```kotlin
strategies.forEach { it.onTickWithContext(tick, sessionContext) { signal -> ... } }
```

- [ ] **Step 3: Modify `Backtest` to construct and pass the `SessionContext`**

In `Backtest.run()`, before the `TradingPipeline` constructor call, build the context:

```kotlin
val ctx =
    SessionContext(
        mode = Mode.BACKTEST,
        clock = clock,
        calendar = calendar,
        source = source ?: NullMarketSource,
    )
```

Where `source` is a new optional `MarketSource` parameter on `Backtest` (default `null`, which uses `NullMarketSource`). `NullMarketSource` is a tiny inert source that throws `UnsupportedDataException` for everything; strategies that don't need a source never see this.

Add `NullMarketSource` to `src/main/kotlin/com/qkt/marketdata/source/NullMarketSource.kt`:

```kotlin
package com.qkt.marketdata.source

object NullMarketSource : MarketSource {
    override val name: String = "Null"
    override val capabilities: Set<MarketSourceCapability> = emptySet()
    override fun supports(symbol: String): Boolean = false
}
```

Add `source: MarketSource? = null` and `calendar: TradingCalendar = TradingCalendar.crypto()` parameters to the `Backtest` primary constructor (defaults preserve existing call sites).

In `Backtest.fromSource`, pass `source = source` so the `SessionContext` reflects the real source.

Pass `ctx` into the `TradingPipeline(...)` constructor.

- [ ] **Step 4: Run check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL. All existing tests pass; behavior unchanged because mode-agnostic strategies never override `onTickWithContext`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(app): plumb SessionContext through Backtest and TradingPipeline"
```

---

## Task 25: Final verification

- [ ] **Step 1: Full build**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`. ~310 tests pass.

- [ ] **Step 2: Run the demo**

```bash
./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"
```
Expected: 10 (3 FILLED + 7 REJECTED — same as Phase 6).

- [ ] **Step 3: Verify no orphan packages or files**

```bash
find src -name "*.kt" -path "*history*" 2>&1
```
Expected: empty output (the `marketdata.history` package is gone).

```bash
grep -rn "DataRequest\|HistoricalDataProvider\|StoreHistoricalDataProvider" src/ 2>&1 | grep -v "MarketRequest\|@Deprecated" || echo "no orphan references"
```
Expected: `no orphan references`.

- [ ] **Step 4: Verify acceptance criteria**

- [ ] `MarketSourceCapability` enum exists with `LIVE_TICKS`, `BARS`, `TICKS`.
- [ ] `MarketRequest` exists in `com.qkt.marketdata.source`; `DataRequest` deleted.
- [ ] `MarketSource` interface ships with default-throwing methods for unsupported capabilities.
- [ ] `LocalMarketSource` implements `MarketSource` with `BARS` + `TICKS`; `liveTicks` throws.
- [ ] `CompositeMarketSource` routes per `SymbolPattern` with fallback.
- [ ] `Reductions` lives in `com.qkt.marketdata.source`; old location deleted.
- [ ] `Backtest.fromSource(source, request, ...)` factory ships; `Backtest.fromStore` updated to use `LocalMarketSource` internally.
- [ ] `SessionAnchor` sealed class ships.
- [ ] `TradingCalendar` interface ships with `crypto()`, `fxDefault()`, `nyse()` factories.
- [ ] `TimeMark` sealed class ships.
- [ ] `TimeRange.of(from, to, clock, calendar)` builder ships.
- [ ] `RefreshTrigger` sealed class ships.
- [ ] `RangeAggregateIndicator<T>` ships.
- [ ] `SessionAnchoredIndicator<T>` ships as sugar over `RangeAggregateIndicator`.
- [ ] `PreviousDayHigh`, `PreviousDayLow`, `SessionHigh`, `SessionLow` ship.
- [ ] `Mode` enum + `SessionContext` ship.
- [ ] `Strategy.onTickWithContext` default-implementation bridge ships; existing strategies compile unchanged.
- [ ] All ~310 tests pass.
- [ ] `./gradlew run` output unchanged from Phase 6 (10 FILLED+REJECTED).

- [ ] **Step 5: No commit (verification only)**

When all boxes checked, Plan 7a is done. Plan 7b (live runtime) follows.
