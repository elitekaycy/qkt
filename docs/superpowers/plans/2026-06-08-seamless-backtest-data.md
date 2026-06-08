# Seamless Backtest Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `qkt backtest <strategy> --from X --to Y` auto-acquire its market data from dukascopy (public, no broker/gateway), reuse cache, fill gaps, and refuse to run on incomplete data unless `--allow-incomplete`.

**Architecture:** A `DukascopyTickFetcher` implements the existing `DataFetcher` seam, so `DefaultDataStore.materializeMissing` auto-fetches missing days. A pure `TickCompletenessValidator` checks session-hour coverage against the trading calendar. A `BacktestDataProvisioner` orchestrates fetch → validate → repair → hard-fail, wired into `BacktestCommand` ahead of the existing replay.

**Tech Stack:** Kotlin, Gradle (version catalog), okhttp (HTTP), org.tukaani:xz (LZMA decode), JUnit 5 + AssertJ, real-type tests (no mocks).

Spec: `docs/superpowers/specs/2026-06-08-seamless-backtest-data-design.md`. Branch: `issue337-seamless-backtest-data`. Conventions: subject-only conventional commits (scope `marketdata`/`app`), `./gradlew ktlintFormat` before each commit, KDoc on every public type.

---

## File structure

| File | Responsibility |
|---|---|
| `gradle/libs.versions.toml`, `build.gradle.kts` | Add the `xz` LZMA dependency |
| `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt` | Bare symbol → dukascopy instrument name + price scale |
| `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickDecoder.kt` | Decode one hour: LZMA-decompress + parse 20-byte records → ticks |
| `src/main/kotlin/com/qkt/marketdata/store/dukascopy/HourDownloader.kt` | Interface + okhttp impl: fetch one hour's `.bi5` bytes (null on 404) |
| `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickFetcher.kt` | `DataFetcher`: assemble 24 hours into a day CSV.gz |
| `src/main/kotlin/com/qkt/marketdata/store/TickCompletenessValidator.kt` | Session-hour coverage check → per-day status report |
| `src/main/kotlin/com/qkt/backtest/BacktestDataProvisioner.kt` | Orchestrate ensure → validate → repair → fail |
| `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` (modify) | Default to dukascopy fetcher; `--no-fetch`/`--allow-incomplete`; call provisioner |

Tests mirror under `src/test/kotlin/...`.

---

## Task 1: Add the LZMA dependency

**Files:**
- Modify: `gradle/libs.versions.toml:13` (the `[libraries]` block)
- Modify: `build.gradle.kts:25` (the `dependencies` block)

- [ ] **Step 1: Add the catalog entry**

In `gradle/libs.versions.toml`, under `[libraries]`, add:

```toml
xz = { module = "org.tukaani:xz", version = "1.9" }
```

- [ ] **Step 2: Wire it into the build**

In `build.gradle.kts`, in the `dependencies { }` block, after `implementation(libs.okhttp)`:

```kotlin
    implementation(libs.xz)
```

- [ ] **Step 3: Verify it resolves**

Run: `./gradlew dependencies --configuration runtimeClasspath --no-daemon | grep tukaani`
Expected: a line containing `org.tukaani:xz:1.9`.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add org.tukaani:xz for dukascopy lzma decode"
```

---

## Task 2: DukascopyInstrument map

Maps a bare qkt symbol (`XAUUSD`) to the dukascopy instrument name and the integer→price divisor (XAUUSD prices arrive as integer thousandths → divide by 1000).

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrumentTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.store.dukascopy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DukascopyInstrumentTest {
    @Test
    fun `resolves a known metal symbol`() {
        val i = DukascopyInstrument.of("XAUUSD")
        assertThat(i.dukascopyName).isEqualTo("XAUUSD")
        assertThat(i.priceDivisor).isEqualTo(1000L)
    }

    @Test
    fun `resolves a known fx symbol`() {
        assertThat(DukascopyInstrument.of("EURUSD").priceDivisor).isEqualTo(100000L)
    }

    @Test
    fun `unknown symbol fails with a clear message`() {
        assertThatThrownBy { DukascopyInstrument.of("WAT") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no dukascopy mapping for WAT")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyInstrumentTest" --no-daemon`
Expected: FAIL — `DukascopyInstrument` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.marketdata.store.dukascopy

/**
 * A dukascopy instrument: its feed name and the divisor that turns the feed's integer prices into
 * real prices. Dukascopy sends prices as scaled integers — e.g. XAUUSD as thousandths, so a raw
 * `2345670` is `2345.670` (divide by 1000); EURUSD as 1e-5, so `109876` is `1.09876`.
 */
data class DukascopyInstrument(
    val dukascopyName: String,
    val priceDivisor: Long,
) {
    companion object {
        // Divisor = 10^decimals. Metals/indices 3 dp; most FX 5 dp; JPY pairs 3 dp.
        private val TABLE: Map<String, DukascopyInstrument> =
            buildMap {
                fun put(sym: String, divisor: Long) = put(sym, DukascopyInstrument(sym, divisor))
                put("XAUUSD", 1000L)
                put("XAGUSD", 1000L)
                listOf("EURUSD", "GBPUSD", "AUDUSD", "NZDUSD", "USDCHF", "USDCAD").forEach { put(it, 100000L) }
                listOf("USDJPY", "EURJPY", "GBPJPY").forEach { put(it, 1000L) }
            }

        /** The instrument for a bare qkt symbol (no `NAME:` prefix), or fail if unmapped. */
        fun of(bareSymbol: String): DukascopyInstrument =
            TABLE[bareSymbol]
                ?: error("no dukascopy mapping for $bareSymbol; add it to DukascopyInstrument or pass --no-fetch")
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyInstrumentTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrument.kt \
        src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyInstrumentTest.kt
git commit -m "feat(marketdata): add dukascopy instrument symbol-to-scale map"
```

---

## Task 3: DukascopyTickDecoder

Decodes one hour. Two responsibilities, kept separate so the record parsing is testable without LZMA: `decompress(bytes)` (thin xz wrapper) and `decodeRecords(decompressed, hourStartMs, divisor)` (pure).

Each record is 20 bytes big-endian: `int32 msFromHourStart, int32 ask, int32 bid, float32 askVol, float32 bidVol`.

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickDecoder.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickDecoderTest.kt`

- [ ] **Step 1: Write the failing test** (hand-built bytes — no real `.bi5` needed for the parser)

```kotlin
package com.qkt.marketdata.store.dukascopy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DukascopyTickDecoderTest {
    private fun record(
        msOffset: Int,
        ask: Int,
        bid: Int,
        askVol: Float,
        bidVol: Float,
    ): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            writeInt(msOffset) // big-endian by default
            writeInt(ask)
            writeInt(bid)
            writeFloat(askVol)
            writeFloat(bidVol)
        }
        return b.toByteArray()
    }

    @Test
    fun `decodes records to ticks with scaled prices and absolute timestamps`() {
        val hourStartMs = 1_700_000_000_000L
        val bytes = record(0, 2_345_670, 2_345_650, 1.5f, 2.0f) + record(1500, 2_345_680, 2_345_660, 0.5f, 0.25f)

        val ticks = DukascopyTickDecoder.decodeRecords(bytes, hourStartMs, divisor = 1000L, symbol = "XAUUSD")

        assertThat(ticks).hasSize(2)
        assertThat(ticks[0].timestamp).isEqualTo(hourStartMs)
        assertThat(ticks[0].ask).isEqualByComparingTo("2345.67")
        assertThat(ticks[0].bid).isEqualByComparingTo("2345.65")
        assertThat(ticks[1].timestamp).isEqualTo(hourStartMs + 1500L)
        assertThat(ticks[1].bid).isEqualByComparingTo("2345.66")
    }

    @Test
    fun `empty input yields no ticks`() {
        assertThat(DukascopyTickDecoder.decodeRecords(ByteArray(0), 0L, 1000L, "XAUUSD")).isEmpty()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyTickDecoderTest" --no-daemon`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.marketdata.store.dukascopy

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import org.tukaani.xz.LZMAInputStream

/**
 * Decodes one dukascopy hour file (`*h_ticks.bi5`).
 *
 * The file is an LZMA-compressed stream of fixed 20-byte big-endian records:
 * `int32 msFromHourStart, int32 ask, int32 bid, float32 askVolume, float32 bidVolume`. Integer
 * prices are scaled by the instrument divisor (XAUUSD ÷1000 → `2345670` = `2345.670`).
 */
object DukascopyTickDecoder {
    private const val RECORD_BYTES = 20

    /** Inflate the raw `.bi5` (LZMA-alone) bytes to the record stream. */
    fun decompress(bi5: ByteArray): ByteArray =
        LZMAInputStream(ByteArrayInputStream(bi5)).use { it.readBytes() }

    /**
     * Parse [decompressed] records into ticks, stamping each with `hourStartMs + msFromHourStart`
     * and scaling prices by [divisor]. [symbol] is the bare qkt symbol stamped on each tick.
     */
    fun decodeRecords(
        decompressed: ByteArray,
        hourStartMs: Long,
        divisor: Long,
        symbol: String,
    ): List<Tick> {
        val count = decompressed.size / RECORD_BYTES
        if (count == 0) return emptyList()
        val buf = ByteBuffer.wrap(decompressed) // big-endian default
        val div = BigDecimal(divisor)
        val out = ArrayList<Tick>(count)
        repeat(count) {
            val msOffset = buf.int
            val askRaw = buf.int
            val bidRaw = buf.int
            val askVol = buf.float
            val bidVol = buf.float
            val ask = BigDecimal(askRaw).divide(div, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            val bid = BigDecimal(bidRaw).divide(div, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            out.add(
                Tick(
                    symbol = symbol,
                    price = bid.add(ask, Money.CONTEXT).divide(BigDecimal(2), Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING),
                    timestamp = hourStartMs + msOffset,
                    volume = null,
                    bid = bid,
                    ask = ask,
                    bidVolume = BigDecimal(bidVol.toDouble()).setScale(Money.SCALE, Money.ROUNDING),
                    askVolume = BigDecimal(askVol.toDouble()).setScale(Money.SCALE, Money.ROUNDING),
                ),
            )
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyTickDecoderTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickDecoder.kt \
        src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickDecoderTest.kt
git commit -m "feat(marketdata): decode dukascopy bi5 tick records"
```

---

## Task 4: HourDownloader (interface + okhttp impl)

Isolates network I/O so the fetcher is testable with a fake. The interface returns `null` for a missing hour (404 — weekend/closed), distinct from a thrown error (network failure).

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/dukascopy/HourDownloader.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/dukascopy/HourDownloaderTest.kt`

- [ ] **Step 1: Write the failing test** (uses okhttp's `MockWebServer`, already a test dep)

```kotlin
package com.qkt.marketdata.store.dukascopy

import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HourDownloaderTest {
    @Test
    fun `returns bytes for a present hour and null for 404`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val dl = OkHttpHourDownloader(baseUrl = server.url("/").toString().removeSuffix("/"))
        val present = dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 9)
        val absent = dl.download("XAUUSD", LocalDate.of(2024, 3, 5), hour = 10)

        assertThat(present).containsExactly(1, 2, 3)
        assertThat(absent).isNull()

        // Month is zero-indexed in the dukascopy path: March -> /02/.
        assertThat(server.takeRequest().path).isEqualTo("/XAUUSD/2024/02/05/09h_ticks.bi5")
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.HourDownloaderTest" --no-daemon`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.marketdata.store.dukascopy

import java.time.LocalDate
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads one dukascopy hour file, or null when the hour has no file (404). */
interface HourDownloader {
    fun download(
        instrument: String,
        day: LocalDate,
        hour: Int,
    ): ByteArray?
}

/**
 * okhttp-backed [HourDownloader] against the dukascopy datafeed. The URL month is zero-indexed
 * (January = `00`), matching dukascopy's path scheme.
 *
 * e.g. `download("XAUUSD", 2024-03-05, 9)` →
 * `https://datafeed.dukascopy.com/datafeed/XAUUSD/2024/02/05/09h_ticks.bi5`.
 */
class OkHttpHourDownloader(
    private val baseUrl: String = "https://datafeed.dukascopy.com/datafeed",
    private val http: OkHttpClient = OkHttpClient(),
) : HourDownloader {
    override fun download(
        instrument: String,
        day: LocalDate,
        hour: Int,
    ): ByteArray? {
        val mm = (day.monthValue - 1).toString().padStart(2, '0')
        val dd = day.dayOfMonth.toString().padStart(2, '0')
        val hh = hour.toString().padStart(2, '0')
        val url = "$baseUrl/$instrument/${day.year}/$mm/$dd/${hh}h_ticks.bi5"
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.code == 404) return null
            check(resp.isSuccessful) { "dukascopy fetch failed: HTTP ${resp.code} for $url" }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return if (bytes.isEmpty()) null else bytes
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.HourDownloaderTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/marketdata/store/dukascopy/HourDownloader.kt \
        src/test/kotlin/com/qkt/marketdata/store/dukascopy/HourDownloaderTest.kt
git commit -m "feat(marketdata): add dukascopy hour downloader"
```

---

## Task 5: DukascopyTickFetcher

Implements the existing `DataFetcher` (`fetch(symbol, day, target)`). Assembles 24 hours into the day file, writing the canonical gzipped CSV with header
`timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`.

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickFetcher.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickFetcherTest.kt`

- [ ] **Step 1: Write the failing test** (fake `HourDownloader` returns LZMA-compressed hand-built records; no network)

```kotlin
package com.qkt.marketdata.store.dukascopy

import com.qkt.marketdata.CsvTickFeed
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

class DukascopyTickFetcherTest {
    private fun lzmaRecord(msOffset: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            writeInt(msOffset)
            writeInt(2_345_670)
            writeInt(2_345_650)
            writeFloat(1f)
            writeFloat(1f)
        }
        val rawBytes = raw.toByteArray()
        val out = ByteArrayOutputStream()
        LZMAOutputStream(out, LZMA2Options(), rawBytes.size.toLong()).use { it.write(rawBytes) }
        return out.toByteArray()
    }

    private class FakeDownloader(private val present: Set<Int>) : HourDownloader {
        override fun download(instrument: String, day: LocalDate, hour: Int): ByteArray? =
            if (hour in present) lzmaRecord(hour * 1000) else null
    }

    @Test
    fun `assembles present hours into a sorted gzip day file`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("2024-03-05.csv.gz")
        DukascopyTickFetcher(FakeDownloader(present = setOf(8, 9, 13)))
            .fetch("XAUUSD", LocalDate.of(2024, 3, 5), target)

        assertThat(Files.exists(target)).isTrue()
        val ticks = generateSequence { CsvTickFeed(target).let { feed -> feed } }.first().let { feed ->
            buildList { while (true) { add(feed.next() ?: break) } }
        }
        assertThat(ticks).hasSize(3)
        assertThat(ticks.map { it.timestamp }).isSorted()
        assertThat(ticks[0].symbol).isEqualTo("XAUUSD")
        assertThat(ticks[0].bid).isEqualByComparingTo("2345.65")
    }

    @Test
    fun `a day with no present hours writes an empty (header-only) file`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("2024-03-09.csv.gz")
        DukascopyTickFetcher(FakeDownloader(present = emptySet()))
            .fetch("XAUUSD", LocalDate.of(2024, 3, 9), target)
        assertThat(Files.exists(target)).isTrue()
        assertThat(CsvTickFeed(target).next()).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyTickFetcherTest" --no-daemon`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.marketdata.store.dukascopy

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DataFetcher
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream

/**
 * A [DataFetcher] that pulls one UTC day of ticks from dukascopy and writes the canonical
 * `symbols/<SYM>/<day>.csv.gz` file. Downloads all 24 hour files, decodes each, concatenates in
 * time order, and writes the standard 8-column tick CSV. A day with no data still writes a
 * header-only file, so the store records the day as fetched and does not retry it endlessly.
 */
class DukascopyTickFetcher(
    private val downloader: HourDownloader = OkHttpHourDownloader(),
) : DataFetcher {
    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        val bare = symbol.substringAfter(':')
        val instrument = DukascopyInstrument.of(bare)
        val dayStartMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val ticks = ArrayList<Tick>()
        for (hour in 0..23) {
            val bi5 = downloader.download(instrument.dukascopyName, day, hour) ?: continue
            val decompressed = DukascopyTickDecoder.decompress(bi5)
            ticks += DukascopyTickDecoder.decodeRecords(
                decompressed = decompressed,
                hourStartMs = dayStartMs + hour * 3_600_000L,
                divisor = instrument.priceDivisor,
                symbol = bare,
            )
        }
        ticks.sortBy { it.timestamp }
        write(target, ticks)
    }

    private fun write(
        target: Path,
        ticks: List<Tick>,
    ) {
        Files.createDirectories(target.parent)
        writer(target).use { w ->
            w.write(CsvTickFeed.EXPECTED_HEADER)
            w.newLine()
            for (t in ticks) {
                // timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume — price left blank, mid derived.
                w.write("${t.timestamp},${t.symbol},,,${t.bid},${t.ask},${t.bidVolume},${t.askVolume}")
                w.newLine()
            }
        }
    }

    private fun writer(target: Path): BufferedWriter =
        BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(target)), Charsets.UTF_8))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.store.dukascopy.DukascopyTickFetcherTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickFetcher.kt \
        src/test/kotlin/com/qkt/marketdata/store/dukascopy/DukascopyTickFetcherTest.kt
git commit -m "feat(marketdata): assemble dukascopy day files via DataFetcher"
```

---

## Task 6: TickCompletenessValidator

Pure check over stored day files. For each calendar day in the range, the **session hours** are the UTC hours whose start is in session per the symbol's `TradingCalendar`. Rule:

- No session hours → **non-trading** day (no data expected).
- Day file missing or empty on a trading day → **missing**.
- Otherwise, every **interior** session hour (session hours excluding the day's first and last, which can be legitimately thin at the open/close boundary) must contain ≥1 tick; any empty interior hour → **incomplete** (lists the empty hours).
- All interior session hours covered → **complete**.

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/store/TickCompletenessValidator.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/store/TickCompletenessValidatorTest.kt`

- [ ] **Step 1: Write the failing test** (real `FxCalendar`, real temp CSV files via the store)

```kotlin
package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TickCompletenessValidatorTest {
    private fun writeDay(
        root: Path,
        symbol: String,
        day: LocalDate,
        hoursWithTicks: List<Int>,
    ) {
        val dir = root.resolve("symbols").resolve(symbol)
        Files.createDirectories(dir)
        val lines = mutableListOf(CsvTickFeed.EXPECTED_HEADER)
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        for (h in hoursWithTicks) {
            lines += "${dayStart + h * 3_600_000L},$symbol,,,100.00,100.02,1.00,1.00"
        }
        Files.writeString(dir.resolve("$day.csv"), lines.joinToString("\n") + "\n")
    }

    @Test
    fun `a fully covered fx week day is complete`(
        @TempDir tmp: Path,
    ) {
        // Wednesday 2024-03-06: FX open all day. Write a tick in every hour 0..23.
        writeDay(tmp, "EURUSD", LocalDate.of(2024, 3, 6), (0..23).toList())
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store = store,
                symbol = "EURUSD",
                from = LocalDate.of(2024, 3, 6),
                to = LocalDate.of(2024, 3, 6),
                calendar = TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isFalse()
    }

    @Test
    fun `a weekend day expects no data`(
        @TempDir tmp: Path,
    ) {
        // Saturday 2024-03-09: FX closed. No file at all — must not be a hole.
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store, "EURUSD", LocalDate.of(2024, 3, 9), LocalDate.of(2024, 3, 9), TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isFalse()
    }

    @Test
    fun `a missing trading day is a hole`(
        @TempDir tmp: Path,
    ) {
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store, "EURUSD", LocalDate.of(2024, 3, 6), LocalDate.of(2024, 3, 6), TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isTrue()
        assertThat(report.days.single().status).isEqualTo(DayCompleteness.Status.MISSING)
    }

    @Test
    fun `an interior empty hour on a trading day is incomplete`(
        @TempDir tmp: Path,
    ) {
        // All hours except 12 — an interior hole.
        writeDay(tmp, "EURUSD", LocalDate.of(2024, 3, 6), (0..23).filter { it != 12 })
        val store = DefaultDataStore(root = tmp)
        val report =
            TickCompletenessValidator.validate(
                store, "EURUSD", LocalDate.of(2024, 3, 6), LocalDate.of(2024, 3, 6), TradingCalendar.fxDefault(),
            )
        assertThat(report.hasHoles).isTrue()
        assertThat(report.days.single().status).isEqualTo(DayCompleteness.Status.INCOMPLETE)
        assertThat(report.days.single().emptyHours).contains(12)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.marketdata.store.TickCompletenessValidatorTest" --no-daemon`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Completeness of one day's tick data. */
data class DayCompleteness(
    val day: LocalDate,
    val status: Status,
    val emptyHours: List<Int> = emptyList(),
) {
    enum class Status { NON_TRADING, COMPLETE, INCOMPLETE, MISSING }
}

/** Completeness report for a symbol over a date range. */
data class CompletenessReport(
    val symbol: String,
    val days: List<DayCompleteness>,
) {
    /** Days that should have data but don't (missing or interior-incomplete). */
    val holes: List<DayCompleteness>
        get() = days.filter { it.status == DayCompleteness.Status.MISSING || it.status == DayCompleteness.Status.INCOMPLETE }

    val hasHoles: Boolean get() = holes.isNotEmpty()
}

/**
 * Validates that the locally stored ticks cover the trading sessions in a date range.
 *
 * For each day, the session hours are the UTC hours whose start the [TradingCalendar] reports
 * in session. A trading day must have a tick in every *interior* session hour (the first and last
 * session hour of the day are exempt, since the open/close boundary can be legitimately thin). A
 * trading day with no ticks is MISSING; a non-trading day (no session hours) expects nothing.
 */
object TickCompletenessValidator {
    fun validate(
        store: DataStore,
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        calendar: TradingCalendar,
    ): CompletenessReport {
        val days = mutableListOf<DayCompleteness>()
        var day = from
        while (!day.isAfter(to)) {
            days += classify(store, symbol, day, calendar)
            day = day.plusDays(1)
        }
        return CompletenessReport(symbol, days)
    }

    private fun classify(
        store: DataStore,
        symbol: String,
        day: LocalDate,
        calendar: TradingCalendar,
    ): DayCompleteness {
        val sessionHours =
            (0..23).filter { h ->
                val start = day.atStartOfDay(ZoneOffset.UTC).plusHours(h.toLong()).toInstant()
                calendar.isInSession(symbol, start)
            }
        if (sessionHours.isEmpty()) return DayCompleteness(day, DayCompleteness.Status.NON_TRADING)

        val hoursWithTicks = hoursWithTicks(store, symbol, day)
        if (hoursWithTicks.isEmpty()) return DayCompleteness(day, DayCompleteness.Status.MISSING)

        // Exempt the boundary session hours; require coverage of the interior ones.
        val interior = sessionHours.drop(1).dropLast(1)
        val emptyInterior = interior.filter { it !in hoursWithTicks }
        return if (emptyInterior.isEmpty()) {
            DayCompleteness(day, DayCompleteness.Status.COMPLETE)
        } else {
            DayCompleteness(day, DayCompleteness.Status.INCOMPLETE, emptyInterior)
        }
    }

    /** The set of UTC hours (0..23) that contain at least one tick in the stored day file. */
    private fun hoursWithTicks(
        store: DataStore,
        symbol: String,
        day: LocalDate,
    ): Set<Int> {
        val path = store.dayFile(symbol, day) ?: return emptySet()
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hours = mutableSetOf<Int>()
        CsvTickFeed(path).use { feed ->
            while (true) {
                val t = feed.next() ?: break
                hours += ((t.timestamp - dayStart) / 3_600_000L).toInt()
            }
        }
        return hours
    }
}
```

Note: `DataStore.dayFile` already returns the `.csv`/`.csv.gz` path or null, and `CsvTickFeed` implements `Closeable` (`use {}` works). `Instant` import is unused — remove if ktlint flags it.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.marketdata.store.TickCompletenessValidatorTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/marketdata/store/TickCompletenessValidator.kt \
        src/test/kotlin/com/qkt/marketdata/store/TickCompletenessValidatorTest.kt
git commit -m "feat(marketdata): validate tick completeness against the trading calendar"
```

---

## Task 7: BacktestDataProvisioner

Orchestrates per-symbol: prefetch missing days (the store's existing `materializeMissing` via the injected fetcher) → validate → repair incomplete days once (delete + refetch) → hard-fail unless allowed.

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/BacktestDataProvisioner.kt`
- Test: `src/test/kotlin/com/qkt/backtest/BacktestDataProvisionerTest.kt`

- [ ] **Step 1: Write the failing test** (fake `DataFetcher`, real `DefaultDataStore`, real `FxCalendar`)

```kotlin
package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestDataProvisionerTest {
    /** Fetcher that writes a full 24-hour day for the listed days, and an empty file otherwise. */
    private class FullDayFetcher(private val fullDays: Set<LocalDate>) : DataFetcher {
        val fetched = mutableListOf<LocalDate>()

        override fun fetch(symbol: String, day: LocalDate, target: Path) {
            fetched += day
            Files.createDirectories(target.parent)
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
            if (day in fullDays) {
                for (h in 0..23) sb.append("${dayStart + h * 3_600_000L},EURUSD,,,1.10,1.10,1.0,1.0\n")
            }
            GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
        }
    }

    private fun stream(sym: String) = ProvisionStream(broker = "BACKTEST", bareSymbol = sym)

    @Test
    fun `fetches missing days then passes validation`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6) // Wednesday
        val fetcher = FullDayFetcher(fullDays = setOf(day))
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = fetcher))

        provisioner.ensure(
            streams = listOf(stream("EURUSD")),
            from = day, to = day,
            fetchEnabled = true, allowIncomplete = false,
            calendarFor = { TradingCalendar.fxDefault() },
        )

        assertThat(fetcher.fetched).contains(day)
    }

    @Test
    fun `a genuine hole hard-fails unless allowed`(
        @TempDir tmp: Path,
    ) {
        val day = LocalDate.of(2024, 3, 6)
        val fetcher = FullDayFetcher(fullDays = emptySet()) // writes empty files -> missing
        val provisioner = BacktestDataProvisioner(store = DefaultDataStore(root = tmp, fetcher = fetcher))

        assertThatThrownBy {
            provisioner.ensure(
                listOf(stream("EURUSD")), day, day,
                fetchEnabled = true, allowIncomplete = false,
                calendarFor = { TradingCalendar.fxDefault() },
            )
        }.isInstanceOf(IncompleteDataException::class.java).hasMessageContaining("EURUSD")

        // With the override it returns normally.
        provisioner.ensure(
            listOf(stream("EURUSD")), day, day,
            fetchEnabled = true, allowIncomplete = true,
            calendarFor = { TradingCalendar.fxDefault() },
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.backtest.BacktestDataProvisionerTest" --no-daemon`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.backtest

import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.TickCompletenessValidator
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/** A symbol the backtest needs data for. [bareSymbol] has no `NAME:` prefix (e.g. `XAUUSD`). */
data class ProvisionStream(
    val broker: String,
    val bareSymbol: String,
)

/** Thrown when, after fetching, the data still has holes and the caller did not allow incompleteness. */
class IncompleteDataException(
    message: String,
) : RuntimeException(message)

/**
 * Makes the local tick store complete for a backtest before it runs: fetch missing days (via the
 * store's configured fetcher), validate session-hour coverage against the trading calendar, repair
 * any incomplete day once (delete + refetch), then fail loud on a remaining hole unless allowed.
 *
 * Operates on **bare** symbols, matching how `LocalMarketSource` keys the tick store.
 */
class BacktestDataProvisioner(
    private val store: DefaultDataStore,
) {
    fun ensure(
        streams: List<ProvisionStream>,
        from: LocalDate,
        to: LocalDate,
        fetchEnabled: Boolean,
        allowIncomplete: Boolean,
        calendarFor: (String) -> TradingCalendar,
    ) {
        val fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

        for (s in streams.distinctBy { it.bareSymbol }) {
            val request = MarketRequest(symbols = listOf(s.bareSymbol), from = fromInstant, to = toInstant)
            if (fetchEnabled) store.prefetch(request)

            var report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))

            if (report.hasHoles && fetchEnabled) {
                // A prior interrupted fetch can leave a day partial. Delete + refetch those once.
                for (hole in report.holes) deleteDay(s.bareSymbol, hole.day)
                store.prefetch(request)
                report = TickCompletenessValidator.validate(store, s.bareSymbol, from, to, calendarFor(s.bareSymbol))
            }

            if (report.hasHoles && !allowIncomplete) {
                throw IncompleteDataException(describe(report.symbol, report.holes))
            }
            if (report.hasHoles) {
                System.err.println("qkt: WARNING — running with incomplete data:\n${describe(report.symbol, report.holes)}")
            }
        }
    }

    private fun deleteDay(
        symbol: String,
        day: LocalDate,
    ) {
        store.dayFile(symbol, day)?.let { Files.deleteIfExists(it) }
        // Drop the day from the manifest so prefetch re-materializes it.
        val manifest = store.manifest(symbol)
        if (manifest.ranges.isNotEmpty()) store.rebuildManifests()
    }

    private fun describe(
        symbol: String,
        holes: List<com.qkt.marketdata.store.DayCompleteness>,
    ): String =
        buildString {
            append("incomplete data for ").append(symbol).append(":\n")
            for (h in holes) {
                append("  ").append(h.day).append("  ").append(h.status.name.lowercase())
                if (h.emptyHours.isNotEmpty()) append(" (empty hours ").append(h.emptyHours.joinToString(",")).append(")")
                append('\n')
            }
            append("  re-run with --allow-incomplete to proceed anyway")
        }
}
```

Note: `deleteDay` deletes the stale file and rebuilds the manifest from on-disk files (so the deleted day drops out of coverage and `prefetch` refetches it). This reuses `DefaultDataStore.rebuildManifests()`, which already rescans `symbols/` and rewrites each manifest.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.backtest.BacktestDataProvisionerTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/backtest/BacktestDataProvisioner.kt \
        src/test/kotlin/com/qkt/backtest/BacktestDataProvisionerTest.kt
git commit -m "feat(backtesting): provision and validate backtest data before replay"
```

---

## Task 8: Wire into BacktestCommand

Default the store's fetcher to dukascopy, derive streams from the AST, run the provisioner before the replay, and add the two flags.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` (the `DefaultDataStore` construction at ~line 90, and add the provisioner call before `Backtest.fromStore`)
- Test: `src/test/kotlin/com/qkt/cli/BacktestCommandDataTest.kt`

- [ ] **Step 1: Write the failing integration test**

This drives the command's provisioning through a strategy file and a temp data root, injecting a fake fetcher via a seam. Add a package-visible constructor parameter `fetcherOverride: DataFetcher? = null` to `BacktestCommand` for test injection (production passes null → dukascopy).

```kotlin
package com.qkt.cli

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestCommandDataTest {
    private class FullDayFetcher : DataFetcher {
        override fun fetch(symbol: String, day: LocalDate, target: Path) {
            Files.createDirectories(target.parent)
            val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
            for (h in 0..23) sb.append("${dayStart + h * 3_600_000L},XAUUSD,,,2345.65,2345.67,1.0,1.0\n")
            GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
        }
    }

    @Test
    fun `auto-fetches and runs without a separate fetch step`(
        @TempDir tmp: Path,
    ) {
        val strat = tmp.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 5m
            RULES
              WHEN NOW.minute_utc = 0 THEN LOG "tick"
            """.trimIndent(),
        )
        val args = Args(listOf(
            strat.toString(),
            "--from", "2024-03-06", "--to", "2024-03-06",
            "--data-root", tmp.resolve("data").toString(),
        ))
        val code = BacktestCommand(args, fetcherOverride = FullDayFetcher()).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        // Data landed under the bare-keyed tick store.
        assertThat(Files.exists(tmp.resolve("data/symbols/XAUUSD/2024-03-06.csv.gz"))).isTrue()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.BacktestCommandDataTest" --no-daemon`
Expected: FAIL — `fetcherOverride` parameter does not exist / provisioning not wired.

- [ ] **Step 3: Implement the wiring**

In `BacktestCommand.kt`:

1. Add the injection seam to the class header:

```kotlin
class BacktestCommand(
    private val args: Args,
    private val fetcherOverride: com.qkt.marketdata.store.DataFetcher? = null,
) {
```

2. Replace the existing store construction. Find:

```kotlin
        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher)
```

Replace with (default the fetcher to dukascopy unless `--no-fetch`, honoring the legacy `--fetcher dukascopy` script path and the test override):

```kotlin
        val noFetch = args.flag("no-fetch")
        val storeFetcher: com.qkt.marketdata.store.DataFetcher? =
            when {
                noFetch -> null
                fetcherOverride != null -> fetcherOverride
                fetcher != null -> fetcher // legacy --fetcher script path still honored
                else -> com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher()
            }
        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = storeFetcher)
```

3. Immediately before `val request = MarketRequest(...)` (or before `Backtest.fromStore`), add the provisioning call. Insert:

```kotlin
        val provisionStreams =
            ast.streams.map { com.qkt.backtest.ProvisionStream(broker = it.broker, bareSymbol = it.symbol) }
        val calendars =
            com.qkt.broker.mt5.SymbolCalendars(
                rules = listOf(
                    com.qkt.broker.mt5.SymbolCalendars.Rule("BTC*", com.qkt.common.TradingCalendar.crypto()),
                    com.qkt.broker.mt5.SymbolCalendars.Rule("ETH*", com.qkt.common.TradingCalendar.crypto()),
                    com.qkt.broker.mt5.SymbolCalendars.Rule("*USDT", com.qkt.common.TradingCalendar.crypto()),
                ),
                default = com.qkt.common.TradingCalendar.fxDefault(),
            )
        try {
            com.qkt.backtest
                .BacktestDataProvisioner(store)
                .ensure(
                    streams = provisionStreams,
                    from = LocalDate.ofInstant(from, ZoneOffset.UTC),
                    to = LocalDate.ofInstant(to, ZoneOffset.UTC).minusDays(if (to == from) 0 else 1),
                    fetchEnabled = !noFetch,
                    allowIncomplete = args.flag("allow-incomplete"),
                    calendarFor = { calendars.calendarFor(it) },
                )
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }
```

Note on the `to` date: `from`/`to` are parsed as `Instant`. The provisioner takes inclusive `LocalDate` bounds, so convert `from`'s date and `to`'s date. If callers pass `--to` as an exclusive end-of-range instant, subtract a day; for a same-day backtest (`from == to`) keep the single day. Verify against `parseInstant` semantics during implementation and adjust the `minusDays` guard so the integration test's single-day case (`2024-03-06`..`2024-03-06`) provisions exactly that day.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.BacktestCommandDataTest" --no-daemon`
Expected: PASS. If the date-bound conversion is off by a day, fix the `LocalDate.ofInstant(...)` bounds until the asserted file path exists.

- [ ] **Step 5: Run the full backtest CLI test suite for regressions**

Run: `./gradlew test --tests "com.qkt.cli.*" --no-daemon`
Expected: PASS (the legacy `--fetcher`/script path still works; new flags are additive).

- [ ] **Step 6: Format + commit**

```bash
./gradlew ktlintFormat --no-daemon -q
git add src/main/kotlin/com/qkt/cli/BacktestCommand.kt \
        src/test/kotlin/com/qkt/cli/BacktestCommandDataTest.kt
git commit -m "feat(app): seamless backtest data auto-fetch via dukascopy"
```

---

## Task 9: Document the seam and limitations

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` (extend the class KDoc usage line — flags)
- Create: `docs/operations/backtest-data.md`

- [ ] **Step 1: Write the docs page**

```markdown
# Backtest data

`qkt backtest <strategy> --from <date> --to <date>` acquires its data automatically — no
separate fetch step, no broker running.

## How it works
1. The needed symbols are read from the strategy's `SYMBOLS` block.
2. Missing UTC days are downloaded from dukascopy (public tick data) into
   `~/.qkt/data/symbols/<SYMBOL>/<day>.csv.gz`. Days already on disk are reused.
3. Coverage is validated against the symbol's trading calendar at session-hour granularity.
   A missing trading day, or an empty interior session hour, is a hole.
4. On a hole the backtest refuses to run, listing the gaps. Re-run with `--allow-incomplete`
   to proceed anyway.

## Flags
- `--no-fetch` — use only cached data (still validated; fails on holes).
- `--allow-incomplete` — run despite missing/incomplete days; prints what is ignored.

## Limitations
- Dukascopy is an independent feed, not your broker's exact ticks — prices/spreads differ
  slightly from your venue. Broker-exact validation is a future gateway source.
- Coverage today: FX majors and metals (e.g. XAUUSD). An unmapped symbol fails fast; add it to
  `DukascopyInstrument`.
```

- [ ] **Step 2: Commit**

```bash
git add docs/operations/backtest-data.md
git commit -m "docs: seamless backtest data acquisition"
```

---

## Final: full build + push

- [ ] **Step 1: Full build**

Run: `./gradlew ktlintFormat build --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin issue337-seamless-backtest-data
gh pr create --base dev --head issue337-seamless-backtest-data \
  --title "feat(app): seamless backtest data auto-fetch via dukascopy" \
  --body "Refs #337, #142. Spec: docs/superpowers/specs/2026-06-08-seamless-backtest-data-design.md. Plan: docs/superpowers/plans/2026-06-08-seamless-backtest-data.md."
```

- [ ] **Step 3: Watch CI to green**, then merge.

---

## Self-review notes (coverage check)

- Spec D1 (dukascopy default source): Tasks 2–5, 8. ✓
- Spec D2 (hard-fail + `--allow-incomplete`): Tasks 7, 8. ✓
- Reuse `DefaultDataStore` `materializeMissing`/`DataFetcher` seam: Tasks 5, 7 (`store.prefetch`). ✓
- Session-hour completeness validator: Task 6. ✓
- Repair-incomplete-once: Task 7 (`deleteDay` + re-prefetch). ✓
- `--no-fetch` offline but still validates: Task 8 wiring (`fetchEnabled = !noFetch`, provisioner validates regardless). ✓
- LZMA dep: Task 1. ✓
- Limitations documented: Task 9. ✓
- Deferred (not in this plan, per spec non-goals): gateway/histdata sources, `ChainedFetcher`, tick-precision beyond dukascopy.
