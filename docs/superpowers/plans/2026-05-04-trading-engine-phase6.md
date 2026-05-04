# Trading Engine Phase 6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace synthetic in-memory tick feeds with a real, scalable, multi-symbol historical-data subsystem. Strategies + DSL get a unified data interface that works identically in backtest and live trading.

**Architecture:** Three orthogonal interfaces — `TickFeed` (streaming), `HistoricalDataProvider` (range query), `DataStore` (persistence) — compose to power both backtest and live modes. On-disk store at `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv.gz` with daily-partitioned gzipped CSVs and per-symbol JSON manifests. Lazy gap-fill via pluggable `DataFetcher` (Phase 6 ships a bash-shelling impl that calls `dukascopy-node`; pure-Kotlin and remote-API providers drop in later behind same interfaces). Strategy code is mode-agnostic — swap concrete impls at construction.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. New dependency: `kotlinx-serialization-json:1.7.3`.

**Spec:** [`docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md`](../specs/2026-05-04-trading-engine-phase6-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase6-data-store`. Phase 5 is on `main` with 167 tests; Phase 6 spec is committed.

---

## Task ordering

```
Build / foundations
 1. Build deps + kotlinx-serialization plugin
 2. Tick extension (bid/ask/bidVolume/askVolume + mid/spread)
 3. TickFeed widening to AutoCloseable

Time utilities
 4. TimeRange value type
 5. TimeContext range factories

Feed layer
 6. CsvTickFeed (streaming + gzip + strict validation)
 7. MergingTickFeed (k-way merge)
 8. RangeClippedTickFeed (range filter)
 9. ConcatenatedTickFeed (sequential concat)
10. HistoricalTickFeed.fromCsv factory

Engine integration
11. Backtest refactor: take TickFeed, secondary constructor for List<Tick>
12. CandleAggregator multi-symbol regression tests (verify existing behavior)
13. IndicatorMap helper

Store layer
14. DataRoot + DataRequest
15. Manifest + ManifestStore (kotlinx-serialization)
16. DataFetcher interface + ScriptDataFetcher
17. DefaultDataStore + DataStore interface
18. scripts/fetch-dukascopy.sh (committed bash)

Sample data
19. data/sample/ fixtures + .gitignore

Query layer
20. HistoricalDataProvider + DataCapability + UnsupportedDataException
21. StoreHistoricalDataProvider
22. Reductions (extension functions)

Integration
23. Backtest.fromStore + integration tests
24. README "Getting real data" section
25. Final verification
```

25 tasks. Cumulative test counts after each:

| After task | New tests | Cumulative |
|---|---|---|
| 1  |  0 | 167 |
| 2  | +1 | 168 |
| 3  |  0 | 168 |
| 4  | +3 | 171 |
| 5  | +10 | 181 |
| 6  | +14 | 195 |
| 7  | +6 | 201 |
| 8  | +4 | 205 |
| 9  | +3 | 208 |
| 10 | +2 | 210 |
| 11 |  0 | 210 |
| 12 | +5 | 215 |
| 13 | +4 | 219 |
| 14 | +5 | 224 |
| 15 | +8 | 232 |
| 16 | +4 | 236 |
| 17 | +10 | 246 |
| 18 |  0 | 246 |
| 19 |  0 | 246 |
| 20 | +2 | 248 |
| 21 | +6 | 254 |
| 22 | +6 | 260 |
| 23 | +5 | 265 |
| 24 |  0 | 265 |
| 25 |  0 | 265 |

Final target: **265 tests** (167 baseline + 98 new). Spec said ~242; the actual count came in higher because invariant + boundary coverage adds up. Rough number; ±5 expected.

---

## Task 1: Build deps + kotlinx-serialization plugin

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add serialization version + library to `gradle/libs.versions.toml`**

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.1.0"
junit = "5.11.4"
assertj = "3.27.0"
ktlint-plugin = "12.1.1"
slf4j = "2.0.16"
kotlinx-serialization = "1.7.3"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-plugin" }
```

- [ ] **Step 2: Apply serialization plugin + add dependency in `build.gradle.kts`**

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
}

group = "com.qkt"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.qkt.app.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
    enableExperimentalRules.set(false)
}
```

- [ ] **Step 3: Run check to verify build still works**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 167 tests pass, ktlint clean.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add kotlinx-serialization-json"
```

---

## Task 2: Tick extension (bid/ask + mid/spread)

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/Tick.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/TickTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/TickTest.kt`:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TickTest {
    @Test
    fun `mid and spread null when bid or ask absent`() {
        val tick = Tick("EURUSD", Money.of("1.0843"), 0L, volume = Money.of("1"))
        assertThat(tick.mid).isNull()
        assertThat(tick.spread).isNull()
    }

    @Test
    fun `mid and spread compute when both bid and ask present`() {
        val tick = Tick(
            symbol = "EURUSD",
            price = Money.of("1.0842"),
            timestamp = 0L,
            bid = Money.of("1.0841"),
            ask = Money.of("1.0843"),
        )
        assertThat(tick.mid).isEqualByComparingTo(Money.of("1.0842"))
        assertThat(tick.spread).isEqualByComparingTo(Money.of("0.0002"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.TickTest"`
Expected: compile failure — `Unresolved reference: bid`.

- [ ] **Step 3: Modify `src/main/kotlin/com/qkt/marketdata/Tick.kt`**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

data class Tick(
    val symbol: String,
    val price: BigDecimal,
    val timestamp: Long,
    val volume: BigDecimal? = null,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val bidVolume: BigDecimal? = null,
    val askVolume: BigDecimal? = null,
) {
    val mid: BigDecimal?
        get() = if (bid != null && ask != null) {
            bid.add(ask, Money.CONTEXT)
                .divide(BigDecimal(2), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)
        } else {
            null
        }

    val spread: BigDecimal?
        get() = if (bid != null && ask != null) {
            ask.subtract(bid, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
        } else {
            null
        }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.TickTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Full check (verify no regressions)**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 168 total tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/Tick.kt src/test/kotlin/com/qkt/marketdata/TickTest.kt
git commit -m "feat(marketdata): extend Tick with bid ask and derived mid spread"
```

---

## Task 3: TickFeed widens to AutoCloseable

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/TickFeed.kt`

No new test (the change is interface-only; existing tests using `TickFeed` should compile unchanged).

- [ ] **Step 1: Modify `TickFeed.kt`**

```kotlin
package com.qkt.marketdata

interface TickFeed : AutoCloseable {
    fun next(): Tick?

    override fun close() {}
}
```

- [ ] **Step 2: Run check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 168 tests pass. Existing `MockTickFeed` and `HistoricalTickFeed` impls inherit the no-op default.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/TickFeed.kt
git commit -m "refactor(marketdata): widen TickFeed to AutoCloseable"
```

---

## Task 4: TimeRange value type

**Files:**
- Create: `src/main/kotlin/com/qkt/common/TimeRange.kt`
- Create: `src/test/kotlin/com/qkt/common/TimeRangeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeRangeTest {
    private val a = Instant.parse("2024-01-15T00:00:00Z")
    private val b = Instant.parse("2024-01-16T00:00:00Z")

    @Test
    fun `requires from less than to`() {
        assertThatThrownBy { TimeRange(b, a) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TimeRange(a, a) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `contains is half open`() {
        val r = TimeRange(a, b)
        assertThat(a in r).isTrue()
        assertThat(b in r).isFalse()
        assertThat(Instant.parse("2024-01-15T12:00:00Z") in r).isTrue()
    }

    @Test
    fun `durationMs computes correctly`() {
        val r = TimeRange(a, b)
        assertThat(r.durationMs).isEqualTo(86_400_000L)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.TimeRangeTest"`
Expected: compile failure — `Unresolved reference: TimeRange`.

- [ ] **Step 3: Implement `TimeRange.kt`**

```kotlin
package com.qkt.common

import java.time.Instant

data class TimeRange(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from < to) { "TimeRange requires from < to: from=$from, to=$to" }
    }

    val durationMs: Long
        get() = to.toEpochMilli() - from.toEpochMilli()

    operator fun contains(t: Instant): Boolean = t >= from && t < to
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.TimeRangeTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 171 tests pass, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/common/TimeRange.kt src/test/kotlin/com/qkt/common/TimeRangeTest.kt
git commit -m "feat(common): add TimeRange value type"
```

---

## Task 5: TimeContext range factories

**Files:**
- Create: `src/main/kotlin/com/qkt/common/TimeContext.kt`
- Create: `src/test/kotlin/com/qkt/common/TimeContextTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimeContextTest {
    private fun ctx(at: String): TimeContext =
        TimeContext(FixedClock(time = Instant.parse(at).toEpochMilli()))

    @Test
    fun `now returns clock time`() {
        val time = ctx("2024-01-15T14:23:00Z")
        assertThat(time.now()).isEqualTo(Instant.parse("2024-01-15T14:23:00Z"))
    }

    @Test
    fun `today is start of today to start of tomorrow UTC`() {
        val r = ctx("2024-01-15T14:23:00Z").today()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }

    @Test
    fun `yesterday is start of prev day to start of today UTC`() {
        val r = ctx("2024-01-15T14:23:00Z").yesterday()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `lastDays trails N days from now`() {
        val r = ctx("2024-01-15T14:23:00Z").lastDays(3)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-12T14:23:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T14:23:00Z"))
    }

    @Test
    fun `lastHours trails N hours from now`() {
        val r = ctx("2024-01-15T14:23:00Z").lastHours(2)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T12:23:00Z"))
    }

    @Test
    fun `thisMonth covers first to first of next`() {
        val r = ctx("2024-01-15T14:23:00Z").thisMonth()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-02-01T00:00:00Z"))
    }

    @Test
    fun `previousMonth handles January rolling into prev year`() {
        val r = ctx("2024-01-15T14:23:00Z").previousMonth()
        assertThat(r.from).isEqualTo(Instant.parse("2023-12-01T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun `thisYear and previousYear correct`() {
        val time = ctx("2024-03-15T00:00:00Z")
        assertThat(time.thisYear().from).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(time.previousYear().from).isEqualTo(Instant.parse("2023-01-01T00:00:00Z"))
        assertThat(time.previousYear().to).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun `session returns time of day window for a date`() {
        val time = ctx("2024-01-15T14:23:00Z")
        val r = time.session(LocalDate.parse("2024-01-15"), 8, 16)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T08:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T16:00:00Z"))
    }

    @Test
    fun `session with endHour 24 rolls to next day midnight`() {
        val time = ctx("2024-01-15T14:23:00Z")
        val r = time.session(LocalDate.parse("2024-01-15"), 22, 24)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.common.TimeContextTest"`
Expected: compile failure — `Unresolved reference: TimeContext`.

- [ ] **Step 3: Implement `TimeContext.kt`**

```kotlin
package com.qkt.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class TimeContext(
    private val clock: Clock,
    val zone: ZoneId = ZoneOffset.UTC,
) {
    fun now(): Instant = Instant.ofEpochMilli(clock.now())

    fun today(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        return TimeRange(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun yesterday(): TimeRange {
        val date = now().atZone(zone).toLocalDate().minusDays(1)
        return TimeRange(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun lastHours(n: Long): TimeRange = TimeRange(now().minus(n, ChronoUnit.HOURS), now())

    fun lastDays(n: Long): TimeRange = TimeRange(now().minus(n, ChronoUnit.DAYS), now())

    fun lastWeeks(n: Long): TimeRange = lastDays(n * 7)

    fun thisWeek(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return TimeRange(
            monday.atStartOfDay(zone).toInstant(),
            monday.plusDays(7).atStartOfDay(zone).toInstant(),
        )
    }

    fun lastWeek(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val thisMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMonday = thisMonday.minusDays(7)
        return TimeRange(
            lastMonday.atStartOfDay(zone).toInstant(),
            thisMonday.atStartOfDay(zone).toInstant(),
        )
    }

    fun thisMonth(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val first = date.withDayOfMonth(1)
        return TimeRange(
            first.atStartOfDay(zone).toInstant(),
            first.plusMonths(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun previousMonth(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val firstThis = date.withDayOfMonth(1)
        val firstPrev = firstThis.minusMonths(1)
        return TimeRange(
            firstPrev.atStartOfDay(zone).toInstant(),
            firstThis.atStartOfDay(zone).toInstant(),
        )
    }

    fun thisYear(): TimeRange {
        val year = now().atZone(zone).toLocalDate().year
        return TimeRange(
            LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
            LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant(),
        )
    }

    fun previousYear(): TimeRange {
        val year = now().atZone(zone).toLocalDate().year
        return TimeRange(
            LocalDate.of(year - 1, 1, 1).atStartOfDay(zone).toInstant(),
            LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
        )
    }

    fun session(
        date: LocalDate,
        startHour: Int,
        endHour: Int,
    ): TimeRange {
        require(startHour in 0..23) { "startHour must be in 0..23: $startHour" }
        require(endHour in 1..24) { "endHour must be in 1..24: $endHour" }
        require(startHour < endHour) { "startHour < endHour required: $startHour, $endHour" }
        val start = date.atTime(startHour, 0).atZone(zone).toInstant()
        val end =
            if (endHour == 24) {
                date.plusDays(1).atStartOfDay(zone).toInstant()
            } else {
                date.atTime(endHour, 0).atZone(zone).toInstant()
            }
        return TimeRange(start, end)
    }

    fun sessionToday(
        startHour: Int,
        endHour: Int,
    ): TimeRange = session(now().atZone(zone).toLocalDate(), startHour, endHour)

    fun sessionYesterday(
        startHour: Int,
        endHour: Int,
    ): TimeRange = session(now().atZone(zone).toLocalDate().minusDays(1), startHour, endHour)
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.common.TimeContextTest"`
Expected: 10 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 181 tests pass, ktlint clean. If ktlint complains on multiline expression formatting, run `./gradlew ktlintFormat` and restage before commit.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/common/TimeContext.kt src/test/kotlin/com/qkt/common/TimeContextTest.kt
git commit -m "feat(common): add TimeContext range factories"
```

---

## Task 6: CsvTickFeed (streaming + gzip + strict validation)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/CsvTickFeed.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/CsvTickFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CsvTickFeedTest {
    @TempDir lateinit var dir: Path

    private fun writeCsv(
        name: String,
        contents: String,
    ): Path {
        val p = dir.resolve(name)
        Files.writeString(p, contents)
        return p
    }

    private fun writeGzipCsv(
        name: String,
        contents: String,
    ): Path {
        val p = dir.resolve(name)
        GZIPOutputStream(Files.newOutputStream(p)).use { it.write(contents.toByteArray()) }
        return p
    }

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    @Test
    fun `parses minimal trade rows`() {
        val csv = "$header\n1000,XAUUSD,2400.50,1.5,,,,\n2000,XAUUSD,2400.55,2.0,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t1 = feed.next()!!
            assertThat(t1.symbol).isEqualTo("XAUUSD")
            assertThat(t1.price).isEqualByComparingTo(Money.of("2400.50"))
            assertThat(t1.volume).isEqualByComparingTo(Money.of("1.5"))
            assertThat(t1.bid).isNull()
            assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `parses quote rows and computes price as mid when blank`() {
        val csv = "$header\n1000,EURUSD,,,1.0841,1.0843,2,1.5"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t = feed.next()!!
            assertThat(t.bid).isEqualByComparingTo(Money.of("1.0841"))
            assertThat(t.ask).isEqualByComparingTo(Money.of("1.0843"))
            assertThat(t.price).isEqualByComparingTo(Money.of("1.0842"))
        }
    }

    @Test
    fun `parses full 8 column rows`() {
        val csv = "$header\n1000,XAUUSD,2400.55,2.0,2400.54,2400.56,5.0,3.0"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            val t = feed.next()!!
            assertThat(t.price).isEqualByComparingTo(Money.of("2400.55"))
            assertThat(t.bid).isEqualByComparingTo(Money.of("2400.54"))
            assertThat(t.ask).isEqualByComparingTo(Money.of("2400.56"))
            assertThat(t.askVolume).isEqualByComparingTo(Money.of("3.0"))
        }
    }

    @Test
    fun `streams gzipped CSV transparently via gz extension`() {
        val csv = "$header\n1000,XAUUSD,100,1,,,,"
        CsvTickFeed(writeGzipCsv("a.csv.gz", csv)).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `header only file returns null on first next`() {
        CsvTickFeed(writeCsv("h.csv", header)).use { feed ->
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `skips empty lines mid file`() {
        val csv = "$header\n1000,XAUUSD,100,1,,,,\n\n2000,XAUUSD,101,1,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
            assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `empty file throws`() {
        assertThatThrownBy { CsvTickFeed(writeCsv("e.csv", "")) }
            .hasMessageContaining("empty CSV")
    }

    @Test
    fun `wrong header throws`() {
        val bad = "time,sym,p\n1,X,1"
        assertThatThrownBy { CsvTickFeed(writeCsv("a.csv", bad)) }
            .hasMessageContaining("unexpected header")
    }

    @Test
    fun `wrong column count throws`() {
        val csv = "$header\n1000,XAUUSD,100"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("expected 8 columns")
        }
    }

    @Test
    fun `unparseable timestamp throws`() {
        val csv = "$header\nabc,XAUUSD,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("invalid timestamp")
        }
    }

    @Test
    fun `non decreasing timestamps required`() {
        val csv = "$header\n2000,X,100,,,,,\n1000,X,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            feed.next()
            assertThatThrownBy { feed.next() }.hasMessageContaining("non-decreasing")
        }
    }

    @Test
    fun `empty symbol throws`() {
        val csv = "$header\n1000,,100,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("empty symbol")
        }
    }

    @Test
    fun `row needs price or bid plus ask`() {
        val csv = "$header\n1000,X,,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("price OR")
        }
    }

    @Test
    fun `inverted bid greater than ask throws`() {
        val csv = "$header\n1000,X,,,1.5,1.4,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("bid > ask")
        }
    }

    @Test
    fun `negative price throws`() {
        val csv = "$header\n1000,X,-1.0,,,,,"
        CsvTickFeed(writeCsv("a.csv", csv)).use { feed ->
            assertThatThrownBy { feed.next() }.hasMessageContaining("negative")
        }
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.CsvTickFeedTest"`
Expected: compile failure — `Unresolved reference: CsvTickFeed`.

- [ ] **Step 3: Implement `CsvTickFeed.kt`**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

class CsvTickFeed(
    private val path: Path,
) : TickFeed {
    private val reader: BufferedReader = openReader(path)
    private var lineNumber: Int = 1
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val header = reader.readLine() ?: error("empty CSV: $path")
        require(header == EXPECTED_HEADER) {
            "unexpected header at $path:1: got '$header', expected '$EXPECTED_HEADER'"
        }
    }

    override fun next(): Tick? {
        while (true) {
            val line = reader.readLine() ?: return null
            lineNumber++
            if (line.isEmpty()) continue
            val tick = parseLine(line)
            check(tick.timestamp >= lastTimestamp) {
                "$path:$lineNumber: non-decreasing timestamps required " +
                    "(got ${tick.timestamp}, last $lastTimestamp): $line"
            }
            lastTimestamp = tick.timestamp
            return tick
        }
    }

    override fun close() = reader.close()

    private fun parseLine(line: String): Tick {
        val cols = line.split(",")
        check(cols.size == 8) { "$path:$lineNumber: expected 8 columns, got ${cols.size}: $line" }
        val ts = cols[0].toLongOrNull()
            ?: error("$path:$lineNumber: invalid timestamp: '${cols[0]}'")
        val symbol = cols[1]
        check(symbol.isNotEmpty()) { "$path:$lineNumber: empty symbol: $line" }
        val price = parseOpt(cols[2], "price")
        val volume = parseOpt(cols[3], "volume")
        val bid = parseOpt(cols[4], "bid")
        val ask = parseOpt(cols[5], "ask")
        val bidVol = parseOpt(cols[6], "bidVolume")
        val askVol = parseOpt(cols[7], "askVolume")

        check(price != null || (bid != null && ask != null)) {
            "$path:$lineNumber: row needs price OR (bid AND ask): $line"
        }
        if (bid != null && ask != null) {
            check(bid <= ask) { "$path:$lineNumber: bid > ask: bid=$bid, ask=$ask" }
        }
        listOf(
            "price" to price,
            "bid" to bid,
            "ask" to ask,
            "volume" to volume,
            "bidVolume" to bidVol,
            "askVolume" to askVol,
        ).forEach { (name, v) ->
            if (v != null && v.signum() < 0) error("$path:$lineNumber: negative $name: $v")
        }

        val finalPrice = price
            ?: bid!!.add(ask!!, Money.CONTEXT)
                .divide(BigDecimal(2), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)

        return Tick(
            symbol = symbol,
            price = finalPrice,
            timestamp = ts,
            volume = volume,
            bid = bid,
            ask = ask,
            bidVolume = bidVol,
            askVolume = askVol,
        )
    }

    private fun parseOpt(raw: String, field: String): BigDecimal? {
        if (raw.isEmpty()) return null
        return try {
            BigDecimal(raw).setScale(Money.SCALE, Money.ROUNDING)
        } catch (e: NumberFormatException) {
            error("$path:$lineNumber: invalid $field: '$raw'")
        }
    }

    private fun openReader(p: Path): BufferedReader {
        val raw = Files.newInputStream(p)
        val stream =
            if (p.fileName.toString().endsWith(".gz")) {
                GZIPInputStream(raw)
            } else {
                raw
            }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    companion object {
        const val EXPECTED_HEADER: String =
            "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.CsvTickFeedTest"`
Expected: 14 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 195 tests pass, ktlint clean. If ktlint formats anything, run `./gradlew ktlintFormat` and restage before commit.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/CsvTickFeed.kt src/test/kotlin/com/qkt/marketdata/CsvTickFeedTest.kt
git commit -m "feat(marketdata): add streaming CsvTickFeed with strict validation"
```

---

## Task 7: MergingTickFeed (k-way merge)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/MergingTickFeed.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/MergingTickFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MergingTickFeedTest {
    private fun feed(ticks: List<Tick>): TickFeed = HistoricalTickFeed(ticks)

    private fun tick(symbol: String, ts: Long, price: String = "1") =
        Tick(symbol, Money.of(price), ts)

    @Test
    fun `interleaves two feeds in timestamp order`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 3L), tick("A", 5L)))
        val b = feed(listOf(tick("B", 2L), tick("B", 4L)))
        val m = MergingTickFeed(listOf(a, b))
        val collected = generateSequence { m.next() }.toList().map { it.timestamp }
        assertThat(collected).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `tie break by feed list order`() {
        val a = feed(listOf(tick("A", 5L)))
        val b = feed(listOf(tick("B", 5L)))
        val m = MergingTickFeed(listOf(a, b))
        val first = m.next()!!
        val second = m.next()!!
        assertThat(first.symbol).isEqualTo("A")
        assertThat(second.symbol).isEqualTo("B")
        assertThat(m.next()).isNull()
    }

    @Test
    fun `handles three feeds with mixed densities`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 7L)))
        val b = feed(listOf(tick("B", 2L), tick("B", 3L), tick("B", 4L), tick("B", 5L)))
        val c = feed(listOf(tick("C", 6L)))
        val m = MergingTickFeed(listOf(a, b, c))
        val ts = generateSequence { m.next() }.toList().map { it.timestamp }
        assertThat(ts).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `empty inner feed produces no ticks`() {
        val m = MergingTickFeed(listOf(feed(emptyList()), feed(emptyList())))
        assertThat(m.next()).isNull()
    }

    @Test
    fun `single feed merge degenerates to passthrough`() {
        val a = feed(listOf(tick("A", 1L), tick("A", 2L)))
        val m = MergingTickFeed(listOf(a))
        assertThat(m.next()!!.timestamp).isEqualTo(1L)
        assertThat(m.next()!!.timestamp).isEqualTo(2L)
        assertThat(m.next()).isNull()
    }

    @Test
    fun `close propagates to all inner feeds`() {
        var closedA = false
        var closedB = false
        val a = object : TickFeed {
            override fun next(): Tick? = null
            override fun close() { closedA = true }
        }
        val b = object : TickFeed {
            override fun next(): Tick? = null
            override fun close() { closedB = true }
        }
        MergingTickFeed(listOf(a, b)).close()
        assertThat(closedA).isTrue()
        assertThat(closedB).isTrue()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.MergingTickFeedTest"`
Expected: compile failure — `Unresolved reference: MergingTickFeed`.

- [ ] **Step 3: Implement `MergingTickFeed.kt`**

```kotlin
package com.qkt.marketdata

import java.util.PriorityQueue

class MergingTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private data class HeadEntry(
        val feedIndex: Int,
        val tick: Tick,
    )

    private val heap: PriorityQueue<HeadEntry> = PriorityQueue(
        compareBy({ it.tick.timestamp }, { it.feedIndex }),
    )

    init {
        feeds.forEachIndexed { i, f ->
            f.next()?.let { heap.add(HeadEntry(i, it)) }
        }
    }

    override fun next(): Tick? {
        val entry = heap.poll() ?: return null
        feeds[entry.feedIndex].next()?.let { heap.add(HeadEntry(entry.feedIndex, it)) }
        return entry.tick
    }

    override fun close() {
        feeds.forEach { runCatching { it.close() } }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.MergingTickFeedTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 201 tests pass, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/MergingTickFeed.kt src/test/kotlin/com/qkt/marketdata/MergingTickFeedTest.kt
git commit -m "feat(marketdata): add MergingTickFeed k-way merge"
```

---

## Task 8: RangeClippedTickFeed

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/RangeClippedTickFeed.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/RangeClippedTickFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RangeClippedTickFeedTest {
    private fun tick(ts: Long) = Tick("X", Money.of("1"), ts)

    @Test
    fun `drops ticks before from`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(5L), tick(10L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 5L, toMs = 100L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(5L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(10L)
        assertThat(clipped.next()).isNull()
    }

    @Test
    fun `stops at first tick at or after to`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(5L), tick(10L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 10L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(1L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(5L)
        assertThat(clipped.next()).isNull()
    }

    @Test
    fun `closes inner feed early when to reached`() {
        var closed = false
        val inner = object : TickFeed {
            private val src = HistoricalTickFeed(listOf(tick(1L), tick(10L)))
            override fun next(): Tick? = src.next()
            override fun close() { closed = true }
        }
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 5L)
        clipped.next()
        clipped.next()
        assertThat(closed).isTrue()
    }

    @Test
    fun `passthrough when range covers all`() {
        val inner = HistoricalTickFeed(listOf(tick(1L), tick(2L)))
        val clipped = RangeClippedTickFeed(inner, fromMs = 0L, toMs = 100L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(1L)
        assertThat(clipped.next()!!.timestamp).isEqualTo(2L)
        assertThat(clipped.next()).isNull()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.RangeClippedTickFeedTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `RangeClippedTickFeed.kt`**

```kotlin
package com.qkt.marketdata

class RangeClippedTickFeed(
    private val inner: TickFeed,
    private val fromMs: Long,
    private val toMs: Long,
) : TickFeed {
    override fun next(): Tick? {
        while (true) {
            val tick = inner.next() ?: return null
            if (tick.timestamp >= toMs) {
                inner.close()
                return null
            }
            if (tick.timestamp >= fromMs) return tick
        }
    }

    override fun close() = inner.close()
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.RangeClippedTickFeedTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 205 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/RangeClippedTickFeed.kt src/test/kotlin/com/qkt/marketdata/RangeClippedTickFeedTest.kt
git commit -m "feat(marketdata): add RangeClippedTickFeed for range filtering"
```

---

## Task 9: ConcatenatedTickFeed

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/ConcatenatedTickFeed.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/ConcatenatedTickFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConcatenatedTickFeedTest {
    private fun tick(ts: Long) = Tick("X", Money.of("1"), ts)

    @Test
    fun `streams feeds end to end`() {
        val a = HistoricalTickFeed(listOf(tick(1L), tick(2L)))
        val b = HistoricalTickFeed(listOf(tick(3L)))
        val cat = ConcatenatedTickFeed(listOf(a, b))
        val ts = generateSequence { cat.next() }.toList().map { it.timestamp }
        assertThat(ts).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `empty feed list returns null immediately`() {
        val cat = ConcatenatedTickFeed(emptyList())
        assertThat(cat.next()).isNull()
    }

    @Test
    fun `closes earlier feeds as it advances`() {
        var closedA = false
        var closedB = false
        val a = object : TickFeed {
            private var emitted = false
            override fun next(): Tick? = if (emitted) null else { emitted = true; tick(1L) }
            override fun close() { closedA = true }
        }
        val b = object : TickFeed {
            private var emitted = false
            override fun next(): Tick? = if (emitted) null else { emitted = true; tick(2L) }
            override fun close() { closedB = true }
        }
        val cat = ConcatenatedTickFeed(listOf(a, b))
        assertThat(cat.next()!!.timestamp).isEqualTo(1L)
        assertThat(cat.next()!!.timestamp).isEqualTo(2L)
        assertThat(cat.next()).isNull()
        assertThat(closedA).isTrue()
        assertThat(closedB).isTrue()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.ConcatenatedTickFeedTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `ConcatenatedTickFeed.kt`**

```kotlin
package com.qkt.marketdata

class ConcatenatedTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? {
        while (index < feeds.size) {
            val tick = feeds[index].next()
            if (tick != null) return tick
            feeds[index].close()
            index++
        }
        return null
    }

    override fun close() {
        for (i in index until feeds.size) runCatching { feeds[i].close() }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.ConcatenatedTickFeedTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 208 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/ConcatenatedTickFeed.kt src/test/kotlin/com/qkt/marketdata/ConcatenatedTickFeedTest.kt
git commit -m "feat(marketdata): add ConcatenatedTickFeed for sequential feeds"
```

---

## Task 10: HistoricalTickFeed.fromCsv factory

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/HistoricalTickFeed.kt`
- Modify: `src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt`

- [ ] **Step 1: Add the test (append to existing test class)**

Append to `src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt`:

```kotlin
    @Test
    fun `fromCsv eager loads a CSV into a list backed feed`(@TempDir dir: java.nio.file.Path) {
        val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
        val csv = "$header\n1000,X,100,1,,,,\n2000,X,101,1,,,,"
        val path = dir.resolve("a.csv")
        java.nio.file.Files.writeString(path, csv)
        val feed = HistoricalTickFeed.fromCsv(path)
        assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
        assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
        assertThat(feed.next()).isNull()
    }
```

Add the import `import org.junit.jupiter.api.io.TempDir` if not already present.

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.HistoricalTickFeedTest"`
Expected: compile failure — `Unresolved reference: fromCsv`.

- [ ] **Step 3: Modify `HistoricalTickFeed.kt`**

```kotlin
package com.qkt.marketdata

import java.nio.file.Path

class HistoricalTickFeed(
    private val ticks: List<Tick>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null

    companion object {
        fun fromCsv(path: Path): HistoricalTickFeed {
            val all = mutableListOf<Tick>()
            CsvTickFeed(path).use { feed ->
                while (true) {
                    val t = feed.next() ?: break
                    all.add(t)
                }
            }
            return HistoricalTickFeed(all)
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.HistoricalTickFeedTest"`
Expected: existing tests + 1 new = all PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 210 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/HistoricalTickFeed.kt src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt
git commit -m "feat(marketdata): add HistoricalTickFeed.fromCsv factory"
```

---

## Task 11: Refactor Backtest to take TickFeed

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`

No new test in this task — existing `BacktestTest` exercises the secondary constructor. New `fromStore`-based tests come in Task 23.

- [ ] **Step 1: Modify `src/main/kotlin/com/qkt/app/Backtest.kt`**

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import java.math.BigDecimal

class Backtest(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
) {
    constructor(
        strategies: List<Strategy>,
        rules: List<RiskRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
    ) : this(
        strategies = strategies,
        rules = rules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
    )

    fun run(): BacktestResult {
        val clock = FixedClock(time = initialTimestamp)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules, positions)

        val tradeRecords = mutableListOf<TradeRecord>()
        val rejections = mutableListOf<RiskRejectedEvent>()
        var peakEquity: BigDecimal = Money.ZERO
        var maxDrawdown: BigDecimal = Money.ZERO

        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = strategies,
                riskEngine = riskEngine,
                candleWindow = candleWindow,
                onFilled = { trade, realized -> tradeRecords.add(TradeRecord(trade, realized)) },
                onRejected = { e -> rejections.add(e) },
                onCandle = {},
            )

        bus.subscribe<TickEvent> {
            val equity = pnl.totalPnL()
            if (equity > peakEquity) peakEquity = equity
            val drawdown = peakEquity.subtract(equity)
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        feed.use { f ->
            while (true) {
                val tick = f.next() ?: break
                clock.time = tick.timestamp
                pipeline.ingest(tick)
            }
        }

        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            realizedTotal = pnl.realizedTotal(),
            unrealizedTotal = pnl.unrealizedTotal(),
            totalPnL = pnl.totalPnL(),
            tradeCount = tradeRecords.size,
            winRate = computeWinRate(tradeRecords),
            maxDrawdown = maxDrawdown,
        )
    }

    private fun computeWinRate(records: List<TradeRecord>): BigDecimal {
        val closing = records.filter { it.realized.signum() != 0 }
        if (closing.isEmpty()) return Money.ZERO
        val wins = closing.count { it.realized.signum() > 0 }
        return BigDecimal(wins)
            .divide(BigDecimal(closing.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 2: Run check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 210 tests pass. Existing `BacktestTest` cases that construct `Backtest(strategies, rules, ticks = listOf(...))` still work via the secondary constructor.

If any existing test fails to compile or pass: revert and re-examine. Likely cause: a test was passing positional args without the `ticks =` named arg; switch to `ticks = listOf(...)` in that test as a minimal fix.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/app/Backtest.kt
git commit -m "refactor(app): make Backtest consume TickFeed with List<Tick> shim"
```

---

## Task 12: CandleAggregator multi-symbol regression tests

**Files:**
- Modify: `src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`

The aggregator is **already** multi-symbol-aware. This task adds explicit regression tests pinning the cross-asset behavior.

- [ ] **Step 1: Append regression tests**

Append to `src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`:

```kotlin
    @Test
    fun `aggregates per symbol with interleaved input`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val agg = CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        // Two symbols, ticks within the same minute window followed by ticks crossing into the next minute.
        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("B", Money.of("200"), 1_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("110"), 30_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("190"), 45_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("120"), 61_000L))) // crosses minute boundary -> emits A's first candle
        bus.publish(TickEvent(Tick("B", Money.of("210"), 62_000L))) // crosses minute boundary -> emits B's first candle

        assertThat(emitted).hasSize(2)
        val candleA = emitted.first { it.symbol == "A" }
        val candleB = emitted.first { it.symbol == "B" }
        assertThat(candleA.high).isEqualByComparingTo(Money.of("110"))
        assertThat(candleB.high).isEqualByComparingTo(Money.of("200"))
        assertThat(candleB.low).isEqualByComparingTo(Money.of("190"))
    }

    @Test
    fun `each symbol's OHLC is independent`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val agg = CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        // A spikes, B does not. Verify A's high doesn't leak into B.
        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("A", Money.of("9999"), 5_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("50"), 10_000L)))
        bus.publish(TickEvent(Tick("B", Money.of("51"), 20_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("100"), 61_000L))) // emit A
        bus.publish(TickEvent(Tick("B", Money.of("52"), 62_000L))) // emit B

        val candleB = emitted.first { it.symbol == "B" }
        assertThat(candleB.high).isEqualByComparingTo(Money.of("51"))
        assertThat(candleB.low).isEqualByComparingTo(Money.of("50"))
    }

    @Test
    fun `unknown symbol on first sight starts a fresh in progress candle`() {
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val agg = CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val emitted = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { emitted.add(it.candle) }

        bus.publish(TickEvent(Tick("A", Money.of("100"), 0L)))
        bus.publish(TickEvent(Tick("NEW", Money.of("999"), 1_000L)))
        bus.publish(TickEvent(Tick("A", Money.of("101"), 61_000L))) // emit A only
        bus.publish(TickEvent(Tick("NEW", Money.of("1000"), 62_000L))) // emit NEW

        assertThat(emitted.map { it.symbol }).containsExactlyInAnyOrder("A", "NEW")
    }
```

Imports needed (add if missing):
```kotlin
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
```

(If `CandleAggregatorTest` already has these imports, skip duplicates.)

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.qkt.candles.CandleAggregatorTest"`
Expected: existing tests + 3 new = all PASS.

- [ ] **Step 3: Full check**

Run: `./gradlew check`
Expected: 215 tests, ktlint clean.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt
git commit -m "test(candles): add multi-symbol regression tests for CandleAggregator"
```

---

## Task 13: IndicatorMap helper

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/IndicatorMap.kt`
- Create: `src/test/kotlin/com/qkt/indicators/IndicatorMapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.indicators

import com.qkt.common.Money
import com.qkt.indicators.catalog.SMA
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorMapTest {
    @Test
    fun `get creates indicator on first call via factory`() {
        val map = IndicatorMap { SMA(3) }
        val sma = map.get("EURUSD")
        assertThat(sma).isInstanceOf(SMA::class.java)
        assertThat(sma.isReady).isFalse()
    }

    @Test
    fun `get returns same instance on subsequent calls`() {
        val map = IndicatorMap { SMA(3) }
        val a = map.get("EURUSD")
        val b = map.get("EURUSD")
        assertThat(a).isSameAs(b)
    }

    @Test
    fun `has and symbols reflect state`() {
        val map = IndicatorMap { SMA(3) }
        assertThat(map.has("EURUSD")).isFalse()
        map.get("EURUSD")
        map.get("XAUUSD")
        assertThat(map.has("EURUSD")).isTrue()
        assertThat(map.symbols()).containsExactlyInAnyOrder("EURUSD", "XAUUSD")
    }

    @Test
    fun `independent state per symbol`() {
        val map = IndicatorMap { SMA(2) }
        map.get("A").update(Money.of("10"))
        map.get("A").update(Money.of("20"))
        map.get("B").update(Money.of("100"))
        assertThat(map.get("A").value()).isEqualByComparingTo(Money.of("15"))
        assertThat(map.get("B").value()).isNull()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.indicators.IndicatorMapTest"`
Expected: compile failure — `Unresolved reference: IndicatorMap`.

- [ ] **Step 3: Implement `IndicatorMap.kt`**

```kotlin
package com.qkt.indicators

class IndicatorMap<T : Indicator<*>>(
    private val factory: () -> T,
) {
    private val map: MutableMap<String, T> = mutableMapOf()

    fun get(symbol: String): T = map.getOrPut(symbol) { factory() }

    fun has(symbol: String): Boolean = symbol in map

    fun symbols(): Set<String> = map.keys

    fun all(): Map<String, T> = map.toMap()
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.indicators.IndicatorMapTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 219 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/IndicatorMap.kt src/test/kotlin/com/qkt/indicators/IndicatorMapTest.kt
git commit -m "feat(indicators): add IndicatorMap per-symbol helper"
```

---

## Task 14: DataRoot + DataRequest

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/DataRoot.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/store/DataRootTest.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/qkt/marketdata/store/DataRootTest.kt`:
```kotlin
package com.qkt.marketdata.store

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataRootTest {
    @Test
    fun `falls back to user home dotqkt data when env unset`() {
        val resolved = DataRoot.resolveExplicit(env = null)
        val expected = Path.of(System.getProperty("user.home"), ".qkt", "data")
        assertThat(resolved).isEqualTo(expected)
    }

    @Test
    fun `respects QKT_DATA_HOME when set`() {
        val resolved = DataRoot.resolveExplicit(env = "/tmp/qkt-test")
        assertThat(resolved).isEqualTo(Path.of("/tmp/qkt-test"))
    }
}
```

`src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt`:
```kotlin
package com.qkt.marketdata.store

import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DataRequestTest {
    @Test
    fun `requires non empty symbols`() {
        assertThatThrownBy { DataRequest(symbols = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requires from less than to when both set`() {
        val a = Instant.parse("2024-01-15T00:00:00Z")
        val b = Instant.parse("2024-01-16T00:00:00Z")
        assertThatThrownBy { DataRequest(listOf("X"), from = b, to = a) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `null from and to are allowed`() {
        DataRequest(listOf("X")) // no exception
        DataRequest(listOf("X"), from = Instant.parse("2024-01-15T00:00:00Z"))
        DataRequest(listOf("X"), to = Instant.parse("2024-01-15T00:00:00Z"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.store.*"`
Expected: compile failure on both classes.

- [ ] **Step 3: Implement `DataRoot.kt`**

```kotlin
package com.qkt.marketdata.store

import java.nio.file.Path

object DataRoot {
    const val ENV: String = "QKT_DATA_HOME"

    fun resolve(): Path = resolveExplicit(System.getenv(ENV))

    fun resolveExplicit(env: String?): Path =
        if (env != null) {
            Path.of(env)
        } else {
            Path.of(System.getProperty("user.home"), ".qkt", "data")
        }
}
```

- [ ] **Step 4: Implement `DataRequest.kt`**

```kotlin
package com.qkt.marketdata.store

import java.time.Instant

data class DataRequest(
    val symbols: List<String>,
    val from: Instant? = null,
    val to: Instant? = null,
) {
    init {
        require(symbols.isNotEmpty()) { "DataRequest requires at least one symbol" }
        if (from != null && to != null) {
            require(from < to) { "from must be < to: from=$from, to=$to" }
        }
    }
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.store.*"`
Expected: 5 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew check`
Expected: 224 tests, ktlint clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/store/DataRoot.kt src/main/kotlin/com/qkt/marketdata/store/DataRequest.kt src/test/kotlin/com/qkt/marketdata/store/DataRootTest.kt src/test/kotlin/com/qkt/marketdata/store/DataRequestTest.kt
git commit -m "feat(marketdata): add DataRoot resolver and DataRequest type"
```

---

## Task 15: Manifest + ManifestStore

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/Manifest.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/store/ManifestStore.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/store/ManifestStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManifestStoreTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `write then read round trips a manifest`() {
        val store = ManifestStore(dir)
        val manifest = Manifest(
            symbol = "EURUSD",
            ranges = listOf(DayRange("2024-01-15", "2024-01-17")),
            lastUpdated = "2026-05-04T00:00:00Z",
        )
        store.write(manifest)
        val read = store.read("EURUSD")
        assertThat(read.symbol).isEqualTo("EURUSD")
        assertThat(read.ranges).hasSize(1)
        assertThat(read.ranges[0].from).isEqualTo("2024-01-15")
        assertThat(read.ranges[0].to).isEqualTo("2024-01-17")
    }

    @Test
    fun `read of missing manifest returns empty manifest with no ranges`() {
        val store = ManifestStore(dir)
        val read = store.read("UNKNOWN")
        assertThat(read.symbol).isEqualTo("UNKNOWN")
        assertThat(read.ranges).isEmpty()
    }

    @Test
    fun `unknown schemaVersion throws`() {
        val store = ManifestStore(dir)
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        Files.writeString(
            symDir.resolve("manifest.json"),
            """{"schemaVersion":2,"schema":"qkt-csv-v1","symbol":"X","ranges":[],"lastUpdated":""}""",
        )
        assertThatThrownBy { store.read("X") }.hasMessageContaining("unsupported manifest schemaVersion")
    }

    @Test
    fun `unknown schema name throws`() {
        val store = ManifestStore(dir)
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        Files.writeString(
            symDir.resolve("manifest.json"),
            """{"schemaVersion":1,"schema":"other","symbol":"X","ranges":[],"lastUpdated":""}""",
        )
        assertThatThrownBy { store.read("X") }.hasMessageContaining("unsupported manifest schema")
    }

    @Test
    fun `coalesce merges adjacent day ranges`() {
        val store = ManifestStore(dir)
        val merged = store.coalesce(
            listOf(DayRange("2024-01-01", "2024-01-08")),
            DayRange("2024-01-08", "2024-01-15"),
        )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].from).isEqualTo("2024-01-01")
        assertThat(merged[0].to).isEqualTo("2024-01-15")
    }

    @Test
    fun `coalesce keeps disjoint ranges separate`() {
        val store = ManifestStore(dir)
        val merged = store.coalesce(
            listOf(DayRange("2024-01-01", "2024-01-08")),
            DayRange("2024-01-15", "2024-01-22"),
        )
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `coalesce handles range fully contained`() {
        val store = ManifestStore(dir)
        val merged = store.coalesce(
            listOf(DayRange("2024-01-01", "2024-01-15")),
            DayRange("2024-01-05", "2024-01-08"),
        )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].from).isEqualTo("2024-01-01")
        assertThat(merged[0].to).isEqualTo("2024-01-15")
    }

    @Test
    fun `atomic write does not leave half written file on success`() {
        val store = ManifestStore(dir)
        val manifest = Manifest(symbol = "X", ranges = emptyList(), lastUpdated = "")
        store.write(manifest)
        val symDir = dir.resolve("symbols").resolve("X")
        assertThat(Files.exists(symDir.resolve("manifest.json"))).isTrue()
        assertThat(Files.list(symDir).toList().none { it.fileName.toString().endsWith(".tmp") }).isTrue()
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.store.ManifestStoreTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `Manifest.kt`**

```kotlin
package com.qkt.marketdata.store

import kotlinx.serialization.Serializable

@Serializable
data class Manifest(
    val schemaVersion: Int = 1,
    val schema: String = "qkt-csv-v1",
    val symbol: String,
    val ranges: List<DayRange> = emptyList(),
    val lastUpdated: String = "",
)

@Serializable
data class DayRange(
    val from: String,
    val to: String,
)
```

- [ ] **Step 4: Implement `ManifestStore.kt`**

```kotlin
package com.qkt.marketdata.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class ManifestStore(
    private val root: Path,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun read(symbol: String): Manifest {
        val path = manifestPath(symbol)
        if (!Files.exists(path)) {
            return Manifest(symbol = symbol)
        }
        val text = Files.readString(path)
        val manifest = try {
            json.decodeFromString<Manifest>(text)
        } catch (e: Exception) {
            error("corrupt manifest at $path: ${e.message}; run ./gradlew rebuildManifest to recover")
        }
        require(manifest.schemaVersion == 1) {
            "unsupported manifest schemaVersion at $path: ${manifest.schemaVersion}; expected 1"
        }
        require(manifest.schema == "qkt-csv-v1") {
            "unsupported manifest schema at $path: ${manifest.schema}; expected 'qkt-csv-v1'"
        }
        return manifest
    }

    fun write(manifest: Manifest) {
        val symDir = root.resolve("symbols").resolve(manifest.symbol)
        Files.createDirectories(symDir)
        val target = symDir.resolve("manifest.json")
        val tmp = symDir.resolve("manifest.json.tmp")
        val updated = manifest.copy(lastUpdated = Instant.now().toString())
        Files.writeString(tmp, json.encodeToString(updated))
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun coalesce(existing: List<DayRange>, added: DayRange): List<DayRange> {
        val all = (existing + added).sortedBy { it.from }
        val merged = mutableListOf<DayRange>()
        for (range in all) {
            val last = merged.lastOrNull()
            if (last != null && last.to >= range.from) {
                merged[merged.size - 1] = DayRange(last.from, maxOf(last.to, range.to))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun manifestPath(symbol: String): Path =
        root.resolve("symbols").resolve(symbol).resolve("manifest.json")
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.store.ManifestStoreTest"`
Expected: 8 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew check`
Expected: 232 tests, ktlint clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/store/Manifest.kt src/main/kotlin/com/qkt/marketdata/store/ManifestStore.kt src/test/kotlin/com/qkt/marketdata/store/ManifestStoreTest.kt
git commit -m "feat(marketdata): add Manifest and ManifestStore with atomic writes"
```

---

## Task 16: DataFetcher interface + ScriptDataFetcher

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/DataFetcher.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/store/ScriptDataFetcher.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/store/ScriptDataFetcherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScriptDataFetcherTest {
    @TempDir lateinit var dir: Path

    private fun writeScript(name: String, body: String): Path {
        val path = dir.resolve(name)
        Files.writeString(path, "#!/usr/bin/env bash\n$body")
        path.toFile().setExecutable(true)
        return path
    }

    @Test
    fun `successful script invocation produces target file`() {
        val script = writeScript(
            "ok.sh",
            """set -e
            mkdir -p "$(dirname "$3")"
            echo "from-script:$1:$2" > "$3"
            """.trimIndent(),
        )
        val target = dir.resolve("out").resolve("file.csv.gz")
        ScriptDataFetcher(script).fetch("EURUSD", LocalDate.parse("2024-01-15"), target)
        assertThat(Files.exists(target)).isTrue()
        assertThat(Files.readString(target).trim()).isEqualTo("from-script:EURUSD:2024-01-15")
    }

    @Test
    fun `non zero exit throws with arguments`() {
        val script = writeScript("fail.sh", "exit 7")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }
            .hasMessageContaining("rc=7")
            .hasMessageContaining("symbol=X")
    }

    @Test
    fun `script exit zero but missing target file throws`() {
        val script = writeScript("nofile.sh", "exit 0")
        val target = dir.resolve("out").resolve("file.csv.gz")
        assertThatThrownBy {
            ScriptDataFetcher(script).fetch("X", LocalDate.parse("2024-01-15"), target)
        }
            .hasMessageContaining("exited 0 but produced no file")
    }

    @Test
    fun `companion dukascopy points at scripts dir`() {
        val fetcher = ScriptDataFetcher.dukascopy()
        // No fetch invocation; just verify the constructor wires the right path.
        assertThat(fetcher).isNotNull
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.store.ScriptDataFetcherTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `DataFetcher.kt`**

```kotlin
package com.qkt.marketdata.store

import java.nio.file.Path
import java.time.LocalDate

interface DataFetcher {
    fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    )
}
```

- [ ] **Step 4: Implement `ScriptDataFetcher.kt`**

```kotlin
package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class ScriptDataFetcher(
    private val script: Path,
) : DataFetcher {
    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        val rc = ProcessBuilder("bash", script.toString(), symbol, day.toString(), target.toString())
            .inheritIO()
            .start()
            .waitFor()
        check(rc == 0) {
            "fetcher script failed: rc=$rc symbol=$symbol day=$day script=$script"
        }
        check(Files.exists(target)) {
            "fetcher script exited 0 but produced no file: $target"
        }
    }

    companion object {
        fun dukascopy(scriptPath: Path = Path.of("scripts/fetch-dukascopy.sh")): ScriptDataFetcher =
            ScriptDataFetcher(scriptPath)
    }
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.store.ScriptDataFetcherTest"`
Expected: 4 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew check`
Expected: 236 tests, ktlint clean.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/store/DataFetcher.kt src/main/kotlin/com/qkt/marketdata/store/ScriptDataFetcher.kt src/test/kotlin/com/qkt/marketdata/store/ScriptDataFetcherTest.kt
git commit -m "feat(marketdata): add DataFetcher and ScriptDataFetcher"
```

---

## Task 17: DefaultDataStore + DataStore interface

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/DataStore.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/store/DefaultDataStoreTest.kt`

This is the most substantial task in the phase. The store coordinates manifest reads, gap-fill, fetcher invocation, and feed assembly.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.store

import com.qkt.common.Money
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultDataStoreTest {
    @TempDir lateinit var dir: Path

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun writeDay(symbol: String, day: String, ticks: List<Pair<Long, String>>) {
        val symDir = dir.resolve("symbols").resolve(symbol)
        Files.createDirectories(symDir)
        val rows = buildString {
            appendLine(header)
            ticks.forEach { (ts, price) ->
                appendLine("$ts,$symbol,$price,1,,,,")
            }
        }.trimEnd('\n')
        Files.writeString(symDir.resolve("$day.csv"), rows)
    }

    private fun writeManifest(symbol: String, ranges: List<Pair<String, String>>) {
        val store = ManifestStore(dir)
        store.write(Manifest(symbol = symbol, ranges = ranges.map { DayRange(it.first, it.second) }))
    }

    @Test
    fun `dayFile resolves csv when present`() {
        writeDay("X", "2024-01-15", listOf(0L to "100"))
        val store = DefaultDataStore(root = dir)
        val path = store.dayFile("X", LocalDate.parse("2024-01-15"))
        assertThat(path).isNotNull
        assertThat(path!!.fileName.toString()).isEqualTo("2024-01-15.csv")
    }

    @Test
    fun `dayFile returns null when neither csv nor csvgz exists`() {
        val store = DefaultDataStore(root = dir)
        assertThat(store.dayFile("X", LocalDate.parse("2024-01-15"))).isNull()
    }

    @Test
    fun `openFeed with explicit range and cached data streams ticks`() {
        writeDay("X", "2024-01-15", listOf(1L to "100", 2L to "101"))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request = DataRequest(
            symbols = listOf("X"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-16T00:00:00Z"),
        )
        store.openFeed(request).use { feed ->
            assertThat(feed.next()!!.timestamp).isEqualTo(1L)
            assertThat(feed.next()!!.timestamp).isEqualTo(2L)
            assertThat(feed.next()).isNull()
        }
    }

    @Test
    fun `openFeed with no fetcher and missing days throws clear error`() {
        val store = DefaultDataStore(root = dir, fetcher = null)
        val request = DataRequest(
            symbols = listOf("X"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-16T00:00:00Z"),
        )
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("missing data")
            .hasMessageContaining("X")
    }

    @Test
    fun `openFeed with explicit range and missing days invokes fetcher`() {
        var fetched = mutableListOf<Pair<String, LocalDate>>()
        val fetcher = object : DataFetcher {
            override fun fetch(symbol: String, day: LocalDate, target: Path) {
                fetched.add(symbol to day)
                Files.createDirectories(target.parent)
                Files.writeString(target, "$header\n0,$symbol,100,1,,,,")
            }
        }
        val store = DefaultDataStore(root = dir, fetcher = fetcher)
        val request = DataRequest(
            symbols = listOf("X"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
        store.openFeed(request).use { feed ->
            generateSequence { feed.next() }.toList()
        }
        assertThat(fetched).containsExactly(
            "X" to LocalDate.parse("2024-01-15"),
            "X" to LocalDate.parse("2024-01-16"),
        )
    }

    @Test
    fun `openFeed merges multiple symbols by timestamp`() {
        writeDay("A", "2024-01-15", listOf(1L to "100", 3L to "102"))
        writeDay("B", "2024-01-15", listOf(2L to "200", 4L to "202"))
        writeManifest("A", listOf("2024-01-15" to "2024-01-16"))
        writeManifest("B", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request = DataRequest(
            symbols = listOf("A", "B"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-16T00:00:00Z"),
        )
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected.map { it.timestamp }).containsExactly(1L, 2L, 3L, 4L)
            assertThat(collected.map { it.symbol }).containsExactly("A", "B", "A", "B")
        }
    }

    @Test
    fun `openFeed with null from and to resolves intersection of cached ranges`() {
        writeDay("A", "2024-01-15", listOf(0L to "100"))
        writeDay("B", "2024-01-15", listOf(0L to "100"))
        writeDay("B", "2024-01-16", listOf(86_400_000L to "101"))
        writeManifest("A", listOf("2024-01-15" to "2024-01-16"))
        writeManifest("B", listOf("2024-01-15" to "2024-01-17"))
        val store = DefaultDataStore(root = dir)
        val request = DataRequest(symbols = listOf("A", "B"))
        // Intersection: both have 2024-01-15; A doesn't have 2024-01-16. So range = [2024-01-15, 2024-01-16).
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected.map { it.timestamp }).containsExactly(0L, 0L)
            assertThat(collected.map { it.symbol }).containsExactlyInAnyOrder("A", "B")
        }
    }

    @Test
    fun `openFeed with null from and to and empty cache throws clear error`() {
        val store = DefaultDataStore(root = dir)
        val request = DataRequest(symbols = listOf("A", "B"))
        assertThatThrownBy { store.openFeed(request) }
            .hasMessageContaining("no cached data")
    }

    @Test
    fun `openFeed clips to non day aligned from and to`() {
        writeDay("X", "2024-01-15", listOf(
            0L to "100",
            12 * 3_600_000L to "101", // 12:00
            18 * 3_600_000L to "102", // 18:00
        ))
        writeManifest("X", listOf("2024-01-15" to "2024-01-16"))
        val store = DefaultDataStore(root = dir)
        val request = DataRequest(
            symbols = listOf("X"),
            from = Instant.parse("2024-01-15T10:00:00Z"),
            to = Instant.parse("2024-01-15T17:00:00Z"),
        )
        store.openFeed(request).use { feed ->
            val collected = generateSequence { feed.next() }.toList()
            assertThat(collected).hasSize(1)
            assertThat(collected[0].timestamp).isEqualTo(12 * 3_600_000L)
        }
    }

    @Test
    fun `prefetch fills missing days without opening feed`() {
        var fetched = 0
        val fetcher = object : DataFetcher {
            override fun fetch(symbol: String, day: LocalDate, target: Path) {
                fetched++
                Files.createDirectories(target.parent)
                Files.writeString(target, "$header\n0,$symbol,100,1,,,,")
            }
        }
        val store = DefaultDataStore(root = dir, fetcher = fetcher)
        val request = DataRequest(
            symbols = listOf("X"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
        store.prefetch(request)
        assertThat(fetched).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.store.DefaultDataStoreTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `DataStore.kt`**

```kotlin
package com.qkt.marketdata.store

import com.qkt.marketdata.TickFeed
import java.nio.file.Path
import java.time.LocalDate

interface DataStore {
    val root: Path

    fun manifest(symbol: String): Manifest

    fun dayFile(symbol: String, day: LocalDate): Path?

    fun openFeed(request: DataRequest): TickFeed

    fun prefetch(request: DataRequest)

    fun rebuildManifests()
}
```

- [ ] **Step 4: Implement `DefaultDataStore.kt`**

```kotlin
package com.qkt.marketdata.store

import com.qkt.marketdata.ConcatenatedTickFeed
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.RangeClippedTickFeed
import com.qkt.marketdata.TickFeed
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DefaultDataStore(
    override val root: Path,
    private val fetcher: DataFetcher? = null,
    private val manifestStore: ManifestStore = ManifestStore(root),
) : DataStore {

    override fun manifest(symbol: String): Manifest = manifestStore.read(symbol)

    override fun dayFile(symbol: String, day: LocalDate): Path? {
        val symDir = root.resolve("symbols").resolve(symbol)
        val gz = symDir.resolve("$day.csv.gz")
        val flat = symDir.resolve("$day.csv")
        return when {
            Files.exists(gz) -> gz
            Files.exists(flat) -> flat
            else -> null
        }
    }

    override fun openFeed(request: DataRequest): TickFeed {
        val (fromMs, toMs) = resolveRange(request)
        materializeMissing(request.symbols, fromMs, toMs)

        val perSymbol = request.symbols.map { sym ->
            val days = daysCovering(fromMs, toMs)
            val feeds = days.mapNotNull { dayFile(sym, it) }.map { CsvTickFeed(it) }
            ConcatenatedTickFeed(feeds)
        }
        val merged: TickFeed = if (perSymbol.size == 1) perSymbol[0] else MergingTickFeed(perSymbol)
        return RangeClippedTickFeed(merged, fromMs = fromMs, toMs = toMs)
    }

    override fun prefetch(request: DataRequest) {
        val (fromMs, toMs) = resolveRange(request)
        materializeMissing(request.symbols, fromMs, toMs)
    }

    override fun rebuildManifests() {
        val symbolsDir = root.resolve("symbols")
        if (!Files.exists(symbolsDir)) return
        Files.list(symbolsDir).use { stream ->
            for (symDir in stream) {
                if (!Files.isDirectory(symDir)) continue
                val sym = symDir.fileName.toString()
                val days = Files.list(symDir).use { fs ->
                    fs.map { it.fileName.toString() }
                        .filter { it.endsWith(".csv") || it.endsWith(".csv.gz") }
                        .map { it.removeSuffix(".gz").removeSuffix(".csv") }
                        .sorted()
                        .toList()
                }
                if (days.isEmpty()) continue
                val ranges = mutableListOf<DayRange>()
                var rangeStart: String? = null
                var rangeEnd: String? = null
                for (day in days) {
                    val date = LocalDate.parse(day)
                    if (rangeStart == null) {
                        rangeStart = day
                        rangeEnd = date.plusDays(1).toString()
                    } else if (rangeEnd == day) {
                        rangeEnd = date.plusDays(1).toString()
                    } else {
                        ranges.add(DayRange(rangeStart, rangeEnd!!))
                        rangeStart = day
                        rangeEnd = date.plusDays(1).toString()
                    }
                }
                if (rangeStart != null) ranges.add(DayRange(rangeStart, rangeEnd!!))
                manifestStore.write(Manifest(symbol = sym, ranges = ranges))
            }
        }
    }

    private fun resolveRange(request: DataRequest): Pair<Long, Long> {
        val (from, to) = if (request.from != null && request.to != null) {
            request.from to request.to
        } else {
            val ranges = request.symbols.map { manifestStore.read(it).ranges }
            check(ranges.all { it.isNotEmpty() }) {
                "no cached data for symbols ${request.symbols}; specify DataRequest(symbols, from, to) to trigger a fetch"
            }
            val earliest = ranges.maxOf { LocalDate.parse(it.first().from) }
            val latest = ranges.minOf { LocalDate.parse(it.last().to) }
            check(earliest < latest) {
                "requested symbols ${request.symbols} have no overlapping cached date range"
            }
            val resolvedFrom = request.from ?: earliest.atStartOfDay(ZoneOffset.UTC).toInstant()
            val resolvedTo = request.to ?: latest.atStartOfDay(ZoneOffset.UTC).toInstant()
            resolvedFrom to resolvedTo
        }
        return from.toEpochMilli() to to.toEpochMilli()
    }

    private fun materializeMissing(symbols: List<String>, fromMs: Long, toMs: Long) {
        val days = daysCovering(fromMs, toMs)
        for (sym in symbols) {
            val manifest = manifestStore.read(sym)
            val covered = manifest.ranges.flatMap { dayList(it) }.toSet()
            val missing = days.filter { it.toString() !in covered }
            if (missing.isEmpty()) continue
            val f = fetcher ?: error(
                "missing data for symbol $sym days $missing (no fetcher configured); supply a DataFetcher to DefaultDataStore",
            )
            for (day in missing) {
                val target = root.resolve("symbols").resolve(sym).resolve("$day.csv.gz")
                f.fetch(sym, day, target)
            }
            // Update manifest after successful fetch.
            var ranges = manifest.ranges
            for (day in missing) {
                ranges = manifestStore.coalesce(ranges, DayRange(day.toString(), day.plusDays(1).toString()))
            }
            manifestStore.write(manifest.copy(ranges = ranges))
        }
    }

    private fun daysCovering(fromMs: Long, toMs: Long): List<LocalDate> {
        val fromDay = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate()
        val toInclusiveDay = Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusiveDay)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }

    private fun dayList(range: DayRange): List<String> {
        val days = mutableListOf<String>()
        var d = LocalDate.parse(range.from)
        val end = LocalDate.parse(range.to)
        while (d.isBefore(end)) {
            days.add(d.toString())
            d = d.plusDays(1)
        }
        return days
    }

    companion object {
        fun fromEnv(fetcher: DataFetcher? = null): DefaultDataStore =
            DefaultDataStore(root = DataRoot.resolve(), fetcher = fetcher)
    }
}
```

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.store.DefaultDataStoreTest"`
Expected: 10 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew check`
Expected: 246 tests, ktlint clean. If formatting fails, run `./gradlew ktlintFormat` and restage before commit.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/store/DataStore.kt src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt src/test/kotlin/com/qkt/marketdata/store/DefaultDataStoreTest.kt
git commit -m "feat(marketdata): add DefaultDataStore with gap fill and rebuild"
```

---

## Task 18: scripts/fetch-dukascopy.sh

**Files:**
- Create: `scripts/fetch-dukascopy.sh`

No tests — this is a runtime script that the user invokes manually or via `ScriptDataFetcher`. Validity is checked by running it once locally; CI doesn't exercise it.

- [ ] **Step 1: Create the script**

`scripts/fetch-dukascopy.sh`:

```bash
#!/usr/bin/env bash
# Fetch a single day of tick data for one symbol from Dukascopy and write to qkt CSV format.
#
# Usage: fetch-dukascopy.sh SYMBOL YYYY-MM-DD TARGET_PATH
#   SYMBOL      e.g. EURUSD
#   YYYY-MM-DD  the calendar date (UTC) to fetch
#   TARGET_PATH where to write the gzipped CSV (e.g. ~/.qkt/data/symbols/EURUSD/2024-01-15.csv.gz)
#
# Requires: Node.js 18+, dukascopy-node installed globally:
#   npm i -g dukascopy-node

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 SYMBOL YYYY-MM-DD TARGET_PATH" >&2
    exit 64
fi

symbol="$1"
day="$2"
target="$3"

if ! command -v npx >/dev/null 2>&1; then
    echo "node/npx not found; install Node.js 18+ from https://nodejs.org" >&2
    exit 1
fi

mkdir -p "$(dirname "$target")"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

# Compute next day for dukascopy-node's exclusive `to` arg.
next_day=$(date -d "$day + 1 day" +%Y-%m-%d 2>/dev/null || \
           date -j -v+1d -f %Y-%m-%d "$day" +%Y-%m-%d)

# Run dukascopy-node. Output column order: timestamp,askPrice,bidPrice,askVolume,bidVolume.
sym_lower=$(echo "$symbol" | tr '[:upper:]' '[:lower:]')
npx --yes dukascopy-node@^4 \
    -i "$sym_lower" \
    -from "$day" \
    -to "$next_day" \
    -t tick \
    -f csv \
    -d "$tmpdir" \
    -fn raw >/dev/null

# Reformat to qkt's 8-column schema. Output:
#   timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
# - price is the mid (bid+ask)/2.
# - volume is left blank (Dukascopy reports per-side volumes only).
{
    echo "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
    if [[ -f "$tmpdir/raw.csv" ]]; then
        awk -F',' -v sym="$symbol" '
            NR > 1 && NF >= 5 {
                mid = ($2 + $3) / 2.0
                printf "%s,%s,%s,,%s,%s,%s,%s\n", $1, sym, mid, $3, $2, $5, $4
            }
        ' "$tmpdir/raw.csv"
    fi
} | gzip > "$target"

echo "wrote $target"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/fetch-dukascopy.sh
```

- [ ] **Step 3: Sanity check**

Run: `bash scripts/fetch-dukascopy.sh 2>&1 | head -1`
Expected: `usage: scripts/fetch-dukascopy.sh SYMBOL YYYY-MM-DD TARGET_PATH`.

(Don't actually fetch — that requires network and dukascopy-node. The usage check is enough.)

- [ ] **Step 4: Run check (no behavior change)**

Run: `./gradlew check`
Expected: 246 tests pass, ktlint clean.

- [ ] **Step 5: Commit**

```bash
git add scripts/fetch-dukascopy.sh
git commit -m "chore(scripts): add fetch-dukascopy bash example"
```

---

## Task 19: data/sample/ fixtures + .gitignore

**Files:**
- Create: `data/.gitignore`
- Create: `data/sample/symbols/EURUSD/manifest.json`
- Create: `data/sample/symbols/EURUSD/2024-01-15.csv`
- Create: `data/sample/symbols/EURUSD/2024-01-16.csv`
- Create: `data/sample/symbols/XAUUSD/manifest.json`
- Create: `data/sample/symbols/XAUUSD/2024-01-15.csv`
- Create: `data/sample/symbols/XAUUSD/2024-01-16.csv`
- Create: `data/sample/symbols/BTCUSD/manifest.json`
- Create: `data/sample/symbols/BTCUSD/2024-01-15.csv`
- Create: `data/sample/symbols/BTCUSD/2024-01-16.csv`

No tests yet — these fixtures are exercised by integration tests in Task 23.

- [ ] **Step 1: Create `data/.gitignore`**

```
*
!.gitignore
!sample/
!sample/**
```

- [ ] **Step 2: Create EURUSD fixtures**

`data/sample/symbols/EURUSD/manifest.json`:
```json
{
    "schemaVersion": 1,
    "schema": "qkt-csv-v1",
    "symbol": "EURUSD",
    "ranges": [
        {
            "from": "2024-01-15",
            "to": "2024-01-17"
        }
    ],
    "lastUpdated": "2026-05-04T00:00:00Z"
}
```

`data/sample/symbols/EURUSD/2024-01-15.csv`:
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705276800000,EURUSD,1.08420000,,1.08410000,1.08430000,2.0,1.5
1705276860000,EURUSD,1.08425000,,1.08415000,1.08435000,2.0,1.5
1705276920000,EURUSD,1.08430000,,1.08420000,1.08440000,2.0,1.5
1705276980000,EURUSD,1.08428000,,1.08418000,1.08438000,2.0,1.5
1705277040000,EURUSD,1.08432000,,1.08422000,1.08442000,2.0,1.5
```

`data/sample/symbols/EURUSD/2024-01-16.csv`:
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705363200000,EURUSD,1.08500000,,1.08490000,1.08510000,2.0,1.5
1705363260000,EURUSD,1.08505000,,1.08495000,1.08515000,2.0,1.5
1705363320000,EURUSD,1.08510000,,1.08500000,1.08520000,2.0,1.5
1705363380000,EURUSD,1.08515000,,1.08505000,1.08525000,2.0,1.5
1705363440000,EURUSD,1.08520000,,1.08510000,1.08530000,2.0,1.5
```

- [ ] **Step 3: Create XAUUSD fixtures**

`data/sample/symbols/XAUUSD/manifest.json`:
```json
{
    "schemaVersion": 1,
    "schema": "qkt-csv-v1",
    "symbol": "XAUUSD",
    "ranges": [
        {
            "from": "2024-01-15",
            "to": "2024-01-17"
        }
    ],
    "lastUpdated": "2026-05-04T00:00:00Z"
}
```

`data/sample/symbols/XAUUSD/2024-01-15.csv`:
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705276800000,XAUUSD,2050.50000000,1.0,,,,
1705276860000,XAUUSD,2050.75000000,1.5,,,,
1705276920000,XAUUSD,2051.00000000,2.0,,,,
1705276980000,XAUUSD,2050.90000000,1.5,,,,
1705277040000,XAUUSD,2051.20000000,1.0,,,,
```

`data/sample/symbols/XAUUSD/2024-01-16.csv`:
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705363200000,XAUUSD,2055.00000000,1.0,,,,
1705363260000,XAUUSD,2055.50000000,1.5,,,,
1705363320000,XAUUSD,2056.00000000,2.0,,,,
1705363380000,XAUUSD,2055.80000000,1.5,,,,
1705363440000,XAUUSD,2056.50000000,1.0,,,,
```

- [ ] **Step 4: Create BTCUSD fixtures**

`data/sample/symbols/BTCUSD/manifest.json`:
```json
{
    "schemaVersion": 1,
    "schema": "qkt-csv-v1",
    "symbol": "BTCUSD",
    "ranges": [
        {
            "from": "2024-01-15",
            "to": "2024-01-17"
        }
    ],
    "lastUpdated": "2026-05-04T00:00:00Z"
}
```

`data/sample/symbols/BTCUSD/2024-01-15.csv`:
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1705276800000,BTCUSD,42500.00000000,0.5,,,,
1705276860000,BTCUSD,42550.00000000,1.0,,,,
1705276920000,BTCUSD,42600.00000000,0.8,,,,
1705276980000,BTCUSD,42580.00000000,0.6,,,,
1705277040000,BTCUSD,42620.00000000,0.7,,,,
```

`data/sample/symbols/BTCUSD/2024-01-16.csv` (header-only — represents an empty market day):
```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
```

- [ ] **Step 5: Verify everything**

Run: `find data -type f`
Expected: `.gitignore`, 9 CSV files, 3 manifest.json files = 13 files.

Run: `./gradlew check`
Expected: 246 tests pass (no new tests in this task).

- [ ] **Step 6: Commit**

```bash
git add data/.gitignore data/sample
git commit -m "chore(data): add sample CSV fixtures mirroring production layout"
```

---

## Task 20: HistoricalDataProvider interface + DataCapability

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/history/UnsupportedDataExceptionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsupportedDataExceptionTest {
    @Test
    fun `message includes capability and provider class`() {
        val ex = UnsupportedDataException(DataCapability.TICKS, "FakeProvider")
        assertThat(ex.message).contains("TICKS")
        assertThat(ex.message).contains("FakeProvider")
    }

    @Test
    fun `is a RuntimeException`() {
        val ex = UnsupportedDataException(DataCapability.CANDLES_INTRADAY, "X")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.history.UnsupportedDataExceptionTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `HistoricalDataProvider.kt`**

```kotlin
package com.qkt.marketdata.history

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

enum class DataCapability {
    TICKS,
    CANDLES_INTRADAY,
    CANDLES_DAILY,
}

class UnsupportedDataException(
    capability: DataCapability,
    providerClass: String,
) : RuntimeException("$providerClass does not support $capability")

interface HistoricalDataProvider {
    val capabilities: Set<DataCapability>

    fun ticks(symbol: String, range: TimeRange): Sequence<Tick>

    fun candles(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle>
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.history.UnsupportedDataExceptionTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 248 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/history/HistoricalDataProvider.kt src/test/kotlin/com/qkt/marketdata/history/UnsupportedDataExceptionTest.kt
git commit -m "feat(marketdata): add HistoricalDataProvider interface and capabilities"
```

---

## Task 21: StoreHistoricalDataProvider

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProvider.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProviderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.history

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.store.DataRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.DayRange
import com.qkt.marketdata.store.Manifest
import com.qkt.marketdata.store.ManifestStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreHistoricalDataProviderTest {
    @TempDir lateinit var dir: Path

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun seed() {
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        Files.writeString(
            symDir.resolve("2024-01-15.csv"),
            "$header\n1000,X,100,1,,,,\n2000,X,101,1,,,,\n3000,X,102,1,,,,",
        )
        ManifestStore(dir).write(Manifest(symbol = "X", ranges = listOf(DayRange("2024-01-15", "2024-01-16"))))
    }

    private fun providerAt(now: String): StoreHistoricalDataProvider {
        seed()
        val clock = FixedClock(time = Instant.parse(now).toEpochMilli())
        return StoreHistoricalDataProvider(DefaultDataStore(root = dir), clock)
    }

    @Test
    fun `capabilities advertise tick and candle support`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        assertThat(p.capabilities).containsExactlyInAnyOrder(
            DataCapability.TICKS,
            DataCapability.CANDLES_INTRADAY,
            DataCapability.CANDLES_DAILY,
        )
    }

    @Test
    fun `ticks returns ticks within range`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        val range = TimeRange(
            Instant.parse("2024-01-15T00:00:00Z"),
            Instant.parse("2024-01-16T00:00:00Z"),
        )
        val ts = p.ticks("X", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(1000L, 2000L, 3000L)
    }

    @Test
    fun `ticks excludes range to (half open)`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        val range = TimeRange(
            Instant.parse("2024-01-15T00:00:00Z"),
            Instant.ofEpochMilli(2000L),
        )
        val ts = p.ticks("X", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(1000L)
    }

    @Test
    fun `look ahead query throws`() {
        val p = providerAt("2024-01-15T00:00:00Z")
        val range = TimeRange(
            Instant.parse("2024-01-15T00:00:00Z"),
            Instant.parse("2024-01-16T00:00:00Z"),
        )
        assertThatThrownBy { p.ticks("X", range).toList() }
            .hasMessageContaining("look-ahead bias")
    }

    @Test
    fun `ticks across day boundaries pulls from multiple files`() {
        val symDir = dir.resolve("symbols").resolve("Y")
        Files.createDirectories(symDir)
        Files.writeString(symDir.resolve("2024-01-15.csv"), "$header\n1705276800000,Y,100,1,,,,")
        Files.writeString(symDir.resolve("2024-01-16.csv"), "$header\n1705363200000,Y,101,1,,,,")
        ManifestStore(dir).write(Manifest(symbol = "Y", ranges = listOf(DayRange("2024-01-15", "2024-01-17"))))
        val clock = FixedClock(time = Instant.parse("2024-01-17T00:00:00Z").toEpochMilli())
        val p = StoreHistoricalDataProvider(DefaultDataStore(root = dir), clock)
        val range = TimeRange(
            Instant.parse("2024-01-15T00:00:00Z"),
            Instant.parse("2024-01-17T00:00:00Z"),
        )
        val ts = p.ticks("Y", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(1705276800000L, 1705363200000L)
    }

    @Test
    fun `candles aggregates ticks via TimeWindow`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        val range = TimeRange(
            Instant.ofEpochMilli(1000L),
            Instant.ofEpochMilli(4000L),
        )
        val candles = p.candles("X", com.qkt.candles.TimeWindow(2_000L), range).toList()
        // Window of 2000ms: bucket [0, 2000) gets ts=1000, bucket [2000, 4000) gets ts=2000 + 3000.
        assertThat(candles).hasSize(2)
        assertThat(candles[0].close).isEqualByComparingTo(Money.of("100"))
        assertThat(candles[1].high).isEqualByComparingTo(Money.of("102"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.history.StoreHistoricalDataProviderTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `StoreHistoricalDataProvider.kt`**

```kotlin
package com.qkt.marketdata.history

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DataStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StoreHistoricalDataProvider(
    private val store: DataStore,
    private val clock: Clock,
) : HistoricalDataProvider {
    override val capabilities: Set<DataCapability> = setOf(
        DataCapability.TICKS,
        DataCapability.CANDLES_INTRADAY,
        DataCapability.CANDLES_DAILY,
    )

    override fun ticks(symbol: String, range: TimeRange): Sequence<Tick> {
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

    override fun candles(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle> = sequence {
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
                open = tick.price; high = tick.price; low = tick.price; close = tick.price
                volume = tick.volume ?: Money.ZERO
                hasData = true
                continue
            }
            if (tick.timestamp >= bucketEnd) {
                yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
                bucketStart = ws
                bucketEnd = ws + window.durationMs
                open = tick.price; high = tick.price; low = tick.price; close = tick.price
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
        val toInclusive = Instant.ofEpochMilli(range.to.toEpochMilli() - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusive)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.history.StoreHistoricalDataProviderTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 254 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProvider.kt src/test/kotlin/com/qkt/marketdata/history/StoreHistoricalDataProviderTest.kt
git commit -m "feat(marketdata): add StoreHistoricalDataProvider with look-ahead bias guard"
```

---

## Task 22: Reductions extension functions

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/history/Reductions.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/history/ReductionsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.history

import com.qkt.common.Money
import com.qkt.indicators.catalog.SMA
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReductionsTest {
    private fun tick(ts: Long, price: String) = Tick("X", Money.of(price), ts)

    private fun candle(o: String, h: String, l: String, c: String) =
        Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.ZERO, 0L, 1L)

    @Test
    fun `maxPrice and minPrice on tick sequence`() {
        val s = sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95"))
        assertThat(s.maxPrice()).isEqualByComparingTo(Money.of("110"))
        assertThat(sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95")).minPrice())
            .isEqualByComparingTo(Money.of("95"))
    }

    @Test
    fun `firstPrice and lastPrice on tick sequence`() {
        val s = sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95"))
        assertThat(s.firstPrice()).isEqualByComparingTo(Money.of("100"))
        assertThat(sequenceOf(tick(1, "100"), tick(2, "110"), tick(3, "95")).lastPrice())
            .isEqualByComparingTo(Money.of("95"))
    }

    @Test
    fun `reductions on empty sequence return null`() {
        val empty: Sequence<Tick> = emptySequence()
        assertThat(empty.maxPrice()).isNull()
        assertThat(emptySequence<Tick>().minPrice()).isNull()
        assertThat(emptySequence<Tick>().firstPrice()).isNull()
        assertThat(emptySequence<Tick>().lastPrice()).isNull()
    }

    @Test
    fun `highestHigh and lowestLow on candle sequence`() {
        val s = sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107"))
        assertThat(s.highestHigh()).isEqualByComparingTo(Money.of("108"))
        assertThat(sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107")).lowestLow())
            .isEqualByComparingTo(Money.of("98"))
    }

    @Test
    fun `firstOpen and lastClose on candle sequence`() {
        val s = sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107"))
        assertThat(s.firstOpen()).isEqualByComparingTo(Money.of("100"))
        assertThat(sequenceOf(candle("100", "105", "98", "102"), candle("102", "108", "101", "107")).lastClose())
            .isEqualByComparingTo(Money.of("107"))
    }

    @Test
    fun `runThrough feeds prices into Indicator and returns it`() {
        val sma = sequenceOf(Money.of("10"), Money.of("20"), Money.of("30")).runThrough(SMA(3))
        assertThat(sma.isReady).isTrue()
        assertThat(sma.value()).isEqualByComparingTo(Money.of("20"))
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.history.ReductionsTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `Reductions.kt`**

```kotlin
package com.qkt.marketdata.history

import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

fun Sequence<Tick>.maxPrice(): BigDecimal? = maxOfOrNull { it.price }

fun Sequence<Tick>.minPrice(): BigDecimal? = minOfOrNull { it.price }

fun Sequence<Tick>.firstPrice(): BigDecimal? = firstOrNull()?.price

fun Sequence<Tick>.lastPrice(): BigDecimal? = lastOrNull()?.price

fun Sequence<Candle>.highestHigh(): BigDecimal? = maxOfOrNull { it.high }

fun Sequence<Candle>.lowestLow(): BigDecimal? = minOfOrNull { it.low }

fun Sequence<Candle>.firstOpen(): BigDecimal? = firstOrNull()?.open

fun Sequence<Candle>.lastClose(): BigDecimal? = lastOrNull()?.close

fun <I : Indicator<BigDecimal>> Sequence<BigDecimal>.runThrough(indicator: I): I {
    forEach { indicator.update(it) }
    return indicator
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.history.ReductionsTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 260 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/history/Reductions.kt src/test/kotlin/com/qkt/marketdata/history/ReductionsTest.kt
git commit -m "feat(marketdata): add reduction extensions on Sequence Tick and Candle"
```

---

## Task 23: Backtest.fromStore + integration tests

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`
- Create: `src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.app

import com.qkt.common.Money
import com.qkt.marketdata.store.DataRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestFromStoreTest {
    private val sample = Path.of("data/sample")

    @Test
    fun `fromStore wires DataStore end to end against sample data`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
        val result = Backtest.fromStore(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 3, size = Money.of("1"))),
            rules = emptyList(),
            store = store,
            request = request,
        ).run()
        assertThat(result.tradeCount).isGreaterThan(0)
    }

    @Test
    fun `fromStore over multiple symbols interleaves trades by timestamp`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(
            symbols = listOf("EURUSD", "XAUUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
        val result = Backtest.fromStore(
            strategies = listOf(
                EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1),
                EveryNthTickBuyStrategy(symbol = "XAUUSD", n = 1),
            ),
            rules = emptyList(),
            store = store,
            request = request,
        ).run()
        // Sample data has 5 ticks per symbol per day x 2 days x 2 symbols = 20 fills.
        assertThat(result.tradeCount).isEqualTo(20)
    }

    @Test
    fun `running same backtest twice produces identical result`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-16T00:00:00Z"),
        )
        fun runOnce() = Backtest.fromStore(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2)),
            rules = emptyList(),
            store = store,
            request = request,
        ).run()
        val a = runOnce()
        val b = runOnce()
        assertThat(b.tradeCount).isEqualTo(a.tradeCount)
        assertThat(b.totalPnL).isEqualByComparingTo(a.totalPnL)
        assertThat(b.maxDrawdown).isEqualByComparingTo(a.maxDrawdown)
    }

    @Test
    fun `fromStore with null from to runs over intersection of cached ranges`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(symbols = listOf("EURUSD"))
        val result = Backtest.fromStore(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 1)),
            rules = emptyList(),
            store = store,
            request = request,
        ).run()
        // EURUSD has 5+5 = 10 ticks across the sample range.
        assertThat(result.tradeCount).isEqualTo(10)
    }

    @Test
    fun `BTCUSD empty Saturday produces no fills for that day`() {
        val store = DefaultDataStore(root = sample)
        val request = DataRequest(
            symbols = listOf("BTCUSD"),
            from = Instant.parse("2024-01-16T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
        val result = Backtest.fromStore(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "BTCUSD", n = 1)),
            rules = emptyList(),
            store = store,
            request = request,
        ).run()
        assertThat(result.tradeCount).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.BacktestFromStoreTest"`
Expected: compile failure — `Unresolved reference: fromStore`.

- [ ] **Step 3: Add `fromStore` factory to `Backtest.kt`**

Add inside the `Backtest` class (e.g., as a `companion object`):

```kotlin
    companion object {
        fun fromStore(
            strategies: List<Strategy>,
            rules: List<RiskRule> = emptyList(),
            store: com.qkt.marketdata.store.DataStore,
            request: com.qkt.marketdata.store.DataRequest,
            candleWindow: TimeWindow? = null,
        ): Backtest {
            val feed = store.openFeed(request)
            val initialTimestamp = request.from?.toEpochMilli() ?: 0L
            return Backtest(
                strategies = strategies,
                rules = rules,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = initialTimestamp,
            )
        }
    }
```

(Or import `DataStore` and `DataRequest` at the top of the file and use simple names.)

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.BacktestFromStoreTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: 265 tests, ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/Backtest.kt src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt
git commit -m "feat(app): add Backtest.fromStore factory for DataStore-backed runs"
```

---

## Task 24: README "Getting real data" section

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Read current README.md**

Run: `cat README.md | wc -l`
Note current line count.

- [ ] **Step 2: Append "Getting real data" section to README.md**

Append at the bottom (or place after the "Usage" / "Backtest" section if one exists):

```markdown
## Getting real data

Phase 6 ships a content-addressable on-disk data store at `~/.qkt/data/` (override via the `QKT_DATA_HOME` environment variable). Strategies query this store via `Backtest.fromStore(...)` or directly via `DataStore.openFeed(...)`. The store is empty on first install — populate it by either:

### Option 1: Auto-fetch via the bundled bash script (Dukascopy)

Requires Node.js 18+ and `dukascopy-node`:

```bash
npm install -g dukascopy-node
```

Then wire the fetcher when constructing your store:

```kotlin
val store = DefaultDataStore.fromEnv(
    fetcher = ScriptDataFetcher.dukascopy(),
)
val backtest = Backtest.fromStore(
    strategies = listOf(MyStrategy()),
    rules = listOf(MaxPositionSize("EURUSD", Money.of("3"))),
    store = store,
    request = DataRequest(
        symbols = listOf("EURUSD"),
        from = Instant.parse("2024-01-15T00:00:00Z"),
        to = Instant.parse("2024-01-22T00:00:00Z"),
    ),
).run()
```

The store will fetch missing days lazily on first run and cache them locally. Subsequent runs over the same range hit the cache and never touch the network.

### Option 2: Bring your own data

Drop CSV files matching the qkt schema into `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv` (or `.csv.gz`). Schema:

```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
```

Then run `./gradlew rebuildManifest` to populate manifests from the file list.

### Option 3: Sample data

The repo ships a tiny fixture set at `data/sample/symbols/` that mirrors the production layout. Point a store at it for tests and demos:

```kotlin
val store = DefaultDataStore(root = Path.of("data/sample"))
```
```

- [ ] **Step 3: Run check**

Run: `./gradlew check`
Expected: 265 tests pass.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: add Getting real data section"
```

---

## Task 25: Final verification

No new files. End-to-end verification of the phase. No commit.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. ~265 tests pass. ktlint clean.

- [ ] **Step 2: Run the demo unchanged**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10 (3 FILLED + 7 REJECTED — same as Phase 5).

- [ ] **Step 3: Verify file count**

Run: `find src -name "*.kt" -type f | wc -l`
Expected: 75 (Phase 5 baseline) + ~25 production + ~22 test = ~122 files. Acceptable range: 115–130.

- [ ] **Step 4: Verify line counts (file size cap)**

Run: `wc -l src/main/kotlin/com/qkt/marketdata/store/*.kt src/main/kotlin/com/qkt/marketdata/history/*.kt src/main/kotlin/com/qkt/marketdata/Csv*.kt src/main/kotlin/com/qkt/marketdata/Merging*.kt src/main/kotlin/com/qkt/marketdata/RangeClipped*.kt src/main/kotlin/com/qkt/marketdata/Concatenated*.kt src/main/kotlin/com/qkt/common/Time*.kt src/main/kotlin/com/qkt/indicators/IndicatorMap.kt | sort -n`

Expected: every file under 200 lines (200-line hard cap from project skill). The largest is likely `DefaultDataStore.kt` (~150 lines) or `CsvTickFeed.kt` (~120 lines).

- [ ] **Step 5: Verify ktlint cleanly**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. No formatting violations.

- [ ] **Step 6: Run the precheck script**

Run: `./scripts/precheck.sh`
Expected: all steps PASS.

- [ ] **Step 7: Verify acceptance criteria from spec §9**

- [ ] `Tick` extended with `bid`, `ask`, `bidVolume`, `askVolume` + `mid` / `spread` accessors.
- [ ] `TickFeed` widened to `AutoCloseable` with no-op default.
- [ ] All new feeds implemented: `CsvTickFeed`, `MergingTickFeed`, `RangeClippedTickFeed`, `ConcatenatedTickFeed`, `HistoricalTickFeed.fromCsv`.
- [ ] `CandleAggregator` regression tests for multi-symbol scenarios pass.
- [ ] `IndicatorMap<T>` ships and works.
- [ ] `TimeRange` + `TimeContext` ship with the full factory set.
- [ ] `DataRoot` + `DataRequest` + `Manifest` + `ManifestStore` + `DataStore` + `DefaultDataStore` ship.
- [ ] `DataFetcher` interface + `ScriptDataFetcher` ship.
- [ ] `scripts/fetch-dukascopy.sh` committed and executable.
- [ ] `data/sample/` mirrors production layout with EURUSD, XAUUSD, BTCUSD fixtures + per-symbol manifests.
- [ ] `data/.gitignore` configured.
- [ ] `HistoricalDataProvider` interface + `DataCapability` enum + `UnsupportedDataException` ship.
- [ ] `StoreHistoricalDataProvider` ships with look-ahead-bias enforcement.
- [ ] Reduction extensions ship.
- [ ] `Backtest.fromStore(...)` factory ships.
- [ ] README "Getting real data" section added.
- [ ] All 167 Phase 1-5 tests still pass.
- [ ] `./gradlew run` output unchanged from Phase 5.
- [ ] Determinism test (running same backtest twice produces identical result) passes.
- [ ] `kotlinx-serialization-json` is the only new dep.
- [ ] No coroutines, no HTTP client, no `.bi5` decoder in `src/main`.

When all checked, Phase 6 is done.

- [ ] **Step 8: No commit**

Verification only.

---

## Notes for the implementing engineer

- **Run `./gradlew check` after every TDD task.** Don't batch.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if `check` fails on it; restage and retry. Bake formatting into the feat commit when possible. If formatting changes are caught after the commit, create a separate `style(...)` commit.
- **Don't use mocks.** Anonymous `object : Interface { ... }` impls + `@TempDir` for filesystem fixtures + `FixedClock` for time + capture lists for callbacks. Real types throughout.
- **`isEqualByComparingTo` for BigDecimal asserts.** `isEqualTo` compares scale; `isEqualByComparingTo` compares value. Always the latter.
- **No `public` keyword.** No semicolons. No emojis. No AI references in code, comments, or commits.
- **Conventional commits subject only.** No body, no footer. Allowed types: `feat`, `fix`, `refactor`, `docs`, `test`, `style`, `build`, `chore`. Allowed scopes: `common`, `marketdata`, `execution`, `strategy`, `broker`, `engine`, `risk`, `app`, `indicators`, `candles`, `bus`, `events`, `pnl`, `positions`, `data` (file scope), `scripts`, `build`, `docs`, `skill`. (`marketdata` covers the new `store` and `history` subpackages.)
- **Each task ends in exactly one commit (sometimes two if a `style:` formatting cleanup is needed).** Don't squash across tasks.
- **`Money.CONTEXT` (DECIMAL64) and `Money.SCALE` (8) are the standard.** `MathContext.DECIMAL128` is allowed only inside `BollingerBands` variance and `VWAP` accumulator (Phase 5 conventions; not relevant in Phase 6 except possibly inside `StoreHistoricalDataProvider.candles` if precision concerns arise — they don't for the rolling aggregator pattern).
- **The `DefaultDataStore` task is the longest.** Allocate extra time. The `resolveRange`, `materializeMissing`, and `daysCovering` helpers are interlocking and need to be tested as a system, not just unit-tested.
- **Sample data fixtures must be valid against `CsvTickFeed`.** If you modify the row format, run `./gradlew test --tests CsvTickFeedTest` to verify before committing the sample.
- **Look-ahead-bias enforcement is critical.** Don't relax it. Tests that need a "wide-open" clock should use a `FixedClock` set to a far-future timestamp.
- **The bash script (`scripts/fetch-dukascopy.sh`) is not exercised by tests** (it would require network + Node.js). Verify manually by running the usage check (`bash scripts/fetch-dukascopy.sh` should print the usage line and exit 64).
- **`Backtest.fromStore` is the integration milestone.** Once Task 23 passes, the data subsystem is end-to-end functional. Subsequent tasks (24, 25) are docs and verification.
- **If a task in 6-9 fails ktlint on multiline expression formatting:** the cleanest fix is `./gradlew ktlintFormat` before staging. The fixed file may put method chains differently than the snippet here — that's fine; the behavior is identical.

---

End of plan.
