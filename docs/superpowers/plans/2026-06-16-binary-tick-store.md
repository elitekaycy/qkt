# Binary tick store — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:inline (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make multi-year backtests decode ~10–20× faster by adding a columnar binary tick day-file format that reconstructs the exact same `Tick` sequence the CSV path produces today.

**Architecture:** A shared `TickAssembler` builds the validated `Tick` from optional fields; both `CsvTickFeed` (string-parsed) and the new `BinaryTickFeed` (binary-decoded) route through it, guaranteeing identical output. A `BinaryTickWriter` and a `qkt data convert` command migrate the existing `.csv.gz` cache to `.bin`; `DefaultDataStore` prefers `.bin` and falls back to CSV. Engine arithmetic is untouched, so backtest=live parity holds by construction.

**Tech Stack:** Kotlin/JVM, Gradle, JUnit5, `java.nio` (ByteBuffer, little-endian), `BigDecimal` at `Money.SCALE = 8`. No new dependencies (codec is RAW little-endian `int64`).

**Spec:** `docs/superpowers/specs/2026-06-16-binary-tick-store-design.md`

---

### Task 1: Extract `TickAssembler` (shared, parity foundation)

The validation + `finalPrice` logic currently inlined in `CsvTickFeed.parseLine` becomes a shared object both feeds call. Pure refactor — existing behavior unchanged.

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/TickAssembler.kt`
- Modify: `src/main/kotlin/com/qkt/marketdata/CsvTickFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/TickAssemblerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TickAssemblerTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `derives mid price from bid and ask when price absent`() {
        val tick = TickAssembler.assemble(
            symbol = "XAUUSD", timestamp = 1_712_000_000_000L,
            price = null, volume = null,
            bid = bd("1711.50400000"), ask = bd("1712.00200000"),
            bidVolume = bd("0.00012000"), askVolume = bd("0.00018000"),
            location = "test:1",
        )
        assertEquals(bd("1711.75300000"), tick.price)
        assertEquals(bd("1711.50400000"), tick.bid)
        assertEquals(bd("1712.00200000"), tick.ask)
    }

    @Test
    fun `keeps explicit price over mid`() {
        val tick = TickAssembler.assemble(
            "EURUSD", 1L, price = bd("1.10000000"), volume = null,
            bid = bd("1.09000000"), ask = bd("1.11000000"),
            bidVolume = null, askVolume = null, location = "test:1",
        )
        assertEquals(bd("1.10000000"), tick.price)
    }

    @Test
    fun `rejects row with neither price nor bid-ask`() {
        assertThrows(IllegalStateException::class.java) {
            TickAssembler.assemble("X", 1L, null, null, null, null, null, null, "test:1")
        }
    }

    @Test
    fun `rejects bid greater than ask`() {
        assertThrows(IllegalStateException::class.java) {
            TickAssembler.assemble("X", 1L, null, null, bd("2.0"), bd("1.0"), null, null, "test:1")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.marketdata.TickAssemblerTest'`
Expected: FAIL — `TickAssembler` unresolved reference.

- [ ] **Step 3: Create `TickAssembler.kt`**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * Builds a validated [Tick] from already-parsed optional fields, applying qkt's data-integrity
 * rules and deriving the canonical trade price (the bid/ask midpoint when no explicit price is
 * given). Both [CsvTickFeed] (string-parsed) and [BinaryTickFeed] (binary-decoded) route through
 * here, so the two feeds cannot diverge — this shared assembly is what keeps binary-backed
 * backtests bit-identical to CSV-backed ones.
 *
 * e.g. assemble("XAUUSD", ts, price=null, bid=1711.504, ask=1712.002, ...) ->
 *      Tick(price = mid = 1711.753, bid = 1711.504, ask = 1712.002, ...)
 *
 * [location] is a human-readable origin (e.g. "file.csv:42") used only in error messages.
 */
object TickAssembler {
    fun assemble(
        symbol: String,
        timestamp: Long,
        price: BigDecimal?,
        volume: BigDecimal?,
        bid: BigDecimal?,
        ask: BigDecimal?,
        bidVolume: BigDecimal?,
        askVolume: BigDecimal?,
        location: String,
    ): Tick {
        check(symbol.isNotEmpty()) { "$location: empty symbol" }
        check(price != null || (bid != null && ask != null)) {
            "$location: row needs price OR (bid AND ask)"
        }
        if (bid != null && ask != null) {
            check(bid <= ask) { "$location: bid > ask: bid=$bid, ask=$ask" }
        }
        listOf(
            "price" to price, "bid" to bid, "ask" to ask,
            "volume" to volume, "bidVolume" to bidVolume, "askVolume" to askVolume,
        ).forEach { (name, v) ->
            if (v != null && v.signum() < 0) error("$location: negative $name: $v")
        }
        val finalPrice =
            price
                ?: bid!!
                    .add(ask!!, Money.CONTEXT)
                    .divide(BigDecimal(2), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
        return Tick(
            symbol = symbol,
            price = finalPrice,
            timestamp = timestamp,
            volume = volume,
            bid = bid,
            ask = ask,
            bidVolume = bidVolume,
            askVolume = askVolume,
        )
    }
}
```

- [ ] **Step 4: Refactor `CsvTickFeed.parseLine` to delegate**

Replace the body of `parseLine` (everything after the six `parseOpt` locals are computed) with a call to the assembler. The final `parseLine` becomes:

```kotlin
    private fun parseLine(line: String): Tick {
        val cols = line.split(",")
        check(cols.size == 8) { "$path:$lineNumber: expected 8 columns, got ${cols.size}: $line" }
        val ts =
            cols[0].toLongOrNull()
                ?: error("$path:$lineNumber: invalid timestamp: '${cols[0]}'")
        return TickAssembler.assemble(
            symbol = cols[1],
            timestamp = ts,
            price = parseOpt(cols[2], "price"),
            volume = parseOpt(cols[3], "volume"),
            bid = parseOpt(cols[4], "bid"),
            ask = parseOpt(cols[5], "ask"),
            bidVolume = parseOpt(cols[6], "bidVolume"),
            askVolume = parseOpt(cols[7], "askVolume"),
            location = "$path:$lineNumber",
        )
    }
```

Leave `parseOpt`, `openReader`, the header check, `next()`, and the monotonic-timestamp check unchanged.

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests 'com.qkt.marketdata.TickAssemblerTest' --tests 'com.qkt.marketdata.CsvTickFeedTest'`
Expected: PASS — both the new assembler test and all existing CSV feed tests are green (proves the refactor preserved behavior).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/TickAssembler.kt src/main/kotlin/com/qkt/marketdata/CsvTickFeed.kt src/test/kotlin/com/qkt/marketdata/TickAssemblerTest.kt
git commit -m "refactor(marketdata): extract shared TickAssembler"
```

---

### Task 2: Binary format header

The on-disk format constants and the header read/write, shared by writer and feed.

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryTickFormat.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickFormatTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BinaryTickFormatTest {
    @Test
    fun `header round-trips through a buffer`() {
        val header = BinaryTickFormat.Header(
            symbol = "XAUUSD",
            tickCount = 1234,
            presenceFlags = (1 shl BinaryTickFormat.COL_PRICE) or (1 shl BinaryTickFormat.COL_BID),
        )
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        BinaryTickFormat.writeHeader(buf, header)
        buf.flip()
        val read = BinaryTickFormat.readHeader(buf)
        assertEquals(header, read)
        assertEquals(BinaryTickFormat.SCALE, read.scale)
    }

    @Test
    fun `column present check reads the flag bits`() {
        val flags = 1 shl BinaryTickFormat.COL_ASK
        assertEquals(true, BinaryTickFormat.isPresent(flags, BinaryTickFormat.COL_ASK))
        assertEquals(false, BinaryTickFormat.isPresent(flags, BinaryTickFormat.COL_BID))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickFormatTest'`
Expected: FAIL — `BinaryTickFormat` unresolved.

- [ ] **Step 3: Create `BinaryTickFormat.kt`**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * On-disk layout of a binary tick day-file (`<date>.bin`), format `qkt-tick-bin-v1`.
 *
 * A file is a little-endian header followed by a columnar body: one `int64` block per stored
 * column, each holding [Header.tickCount] values in tick order. Values are scaled integers
 * (`BigDecimal` unscaled value at scale [SCALE], so e.g. 1712.00200000 -> 171200200000); an absent
 * cell within a present column is [NULL_SENTINEL]. The timestamp column is always present and is
 * not flagged. A column entirely absent from the data is omitted and its presence bit is 0.
 *
 * The format records its [Header.codec] so readers stay format-driven; v1 ships [CODEC_RAW]
 * (uncompressed). Reconstructing a value is `BigDecimal.valueOf(stored, SCALE)`, which reproduces
 * the exact `BigDecimal` the CSV path parsed (the source has exactly [SCALE] fractional digits).
 */
object BinaryTickFormat {
    val MAGIC = byteArrayOf('Q'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    const val VERSION = 1
    const val SCALE = Money.SCALE // 8
    const val CODEC_RAW = 0
    const val NULL_SENTINEL = Long.MIN_VALUE

    // Value-column bit positions in the presence flags (timestamp is implicit, always present).
    const val COL_PRICE = 0
    const val COL_VOLUME = 1
    const val COL_BID = 2
    const val COL_ASK = 3
    const val COL_BID_VOLUME = 4
    const val COL_ASK_VOLUME = 5
    const val COL_COUNT = 6

    data class Header(
        val symbol: String,
        val tickCount: Int,
        val presenceFlags: Int,
        val version: Int = VERSION,
        val scale: Int = SCALE,
        val codec: Int = CODEC_RAW,
    )

    fun isPresent(flags: Int, col: Int): Boolean = (flags and (1 shl col)) != 0

    fun writeHeader(buf: ByteBuffer, h: Header) {
        buf.put(MAGIC)
        buf.putInt(h.version)
        buf.putInt(h.scale)
        buf.putInt(h.codec)
        val sym = h.symbol.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(sym.size)
        buf.put(sym)
        buf.putInt(h.tickCount)
        buf.putInt(h.presenceFlags)
    }

    fun readHeader(buf: ByteBuffer): Header {
        val magic = ByteArray(4).also { buf.get(it) }
        check(magic.contentEquals(MAGIC)) { "bad magic: not a qkt-tick-bin file" }
        val version = buf.int
        check(version == VERSION) { "unsupported binary tick version: $version" }
        val scale = buf.int
        val codec = buf.int
        check(codec == CODEC_RAW) { "unsupported binary tick codec: $codec" }
        val symLen = buf.int
        val sym = ByteArray(symLen).also { buf.get(it) }
        val tickCount = buf.int
        val presenceFlags = buf.int
        return Header(
            symbol = String(sym, StandardCharsets.UTF_8),
            tickCount = tickCount,
            presenceFlags = presenceFlags,
            version = version,
            scale = scale,
            codec = codec,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickFormatTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/BinaryTickFormat.kt src/test/kotlin/com/qkt/marketdata/BinaryTickFormatTest.kt
git commit -m "feat(marketdata): add binary tick format header"
```

---

### Task 3: `BinaryTickWriter` and `BinaryTickFeed` (round-trip)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryTickWriter.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt`
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BinaryTickRoundTripTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    private fun drain(feed: TickFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        feed.use { while (true) { val t = it.next() ?: break; out.add(t) } }
        return out
    }

    @Test
    fun `writer then feed reproduces ticks exactly`(@TempDir dir: Path) {
        val ticks = listOf(
            TickAssembler.assemble("XAUUSD", 1_712_000_000_000L, null, null,
                bd("1711.50400000"), bd("1712.00200000"), bd("0.00012000"), bd("0.00018000"), "t:1"),
            TickAssembler.assemble("XAUUSD", 1_712_000_000_050L, null, null,
                bd("1711.53400000"), bd("1712.00600000"), bd("0.00018000"), bd("0.00012000"), "t:2"),
        )
        val file = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(file, "XAUUSD", ticks)
        assertEquals(ticks, drain(BinaryTickFeed(file)))
    }

    @Test
    fun `empty tick list writes a readable empty file`(@TempDir dir: Path) {
        val file = dir.resolve("2024-01-06.bin")
        BinaryTickWriter().write(file, "XAUUSD", emptyList())
        assertEquals(emptyList<Tick>(), drain(BinaryTickFeed(file)))
        assertEquals(true, Files.exists(file))
    }

    @Test
    fun `non-monotonic timestamps fail loud`(@TempDir dir: Path) {
        val file = dir.resolve("2024-01-05.bin")
        val w = BinaryTickWriter()
        // write directly with descending timestamps by hand-building ticks
        val ticks = listOf(
            TickAssembler.assemble("X", 100L, bd("1.0"), null, null, null, null, null, "t:1"),
            TickAssembler.assemble("X", 50L, bd("1.0"), null, null, null, null, null, "t:2"),
        )
        w.write(file, "X", ticks)
        val feed = BinaryTickFeed(file)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            drain(feed)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickRoundTripTest'`
Expected: FAIL — `BinaryTickWriter` / `BinaryTickFeed` unresolved.

- [ ] **Step 3: Create `BinaryTickWriter.kt`**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes a tick sequence to a binary day-file ([BinaryTickFormat]). Columns present in the body are
 * exactly those for which at least one tick has a non-null value (the price column is always present
 * because every [Tick] has a price). Writes to a temp file and atomically moves it into place,
 * matching the tick store's write discipline.
 */
class BinaryTickWriter {
    fun write(target: Path, symbol: String, ticks: List<Tick>) {
        Files.createDirectories(target.parent)
        var flags = 0
        flags = flags or (1 shl BinaryTickFormat.COL_PRICE)
        if (ticks.any { it.volume != null }) flags = flags or (1 shl BinaryTickFormat.COL_VOLUME)
        if (ticks.any { it.bid != null }) flags = flags or (1 shl BinaryTickFormat.COL_BID)
        if (ticks.any { it.ask != null }) flags = flags or (1 shl BinaryTickFormat.COL_ASK)
        if (ticks.any { it.bidVolume != null }) flags = flags or (1 shl BinaryTickFormat.COL_BID_VOLUME)
        if (ticks.any { it.askVolume != null }) flags = flags or (1 shl BinaryTickFormat.COL_ASK_VOLUME)

        val header = BinaryTickFormat.Header(symbol = symbol, tickCount = ticks.size, presenceFlags = flags)
        val columns = presentColumns(flags)
        // header (bounded) + timestamp column + one int64 block per present column
        val bodyLongs = ticks.size.toLong() * (1 + columns.size)
        val cap = 64 + symbol.toByteArray().size + (bodyLongs * Long.SIZE_BYTES).toInt()
        val buf = ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN)

        BinaryTickFormat.writeHeader(buf, header)
        for (t in ticks) buf.putLong(t.timestamp)
        for (col in columns) for (t in ticks) buf.putLong(scaled(field(t, col)))

        buf.flip()
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.newByteChannel(
            tmp,
            setOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING),
        ).use { it.write(buf) }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun presentColumns(flags: Int): List<Int> =
        (0 until BinaryTickFormat.COL_COUNT).filter { BinaryTickFormat.isPresent(flags, it) }

    private fun field(t: Tick, col: Int): BigDecimal? =
        when (col) {
            BinaryTickFormat.COL_PRICE -> t.price
            BinaryTickFormat.COL_VOLUME -> t.volume
            BinaryTickFormat.COL_BID -> t.bid
            BinaryTickFormat.COL_ASK -> t.ask
            BinaryTickFormat.COL_BID_VOLUME -> t.bidVolume
            BinaryTickFormat.COL_ASK_VOLUME -> t.askVolume
            else -> error("unknown column $col")
        }

    private fun scaled(bd: BigDecimal?): Long =
        if (bd == null) {
            BinaryTickFormat.NULL_SENTINEL
        } else {
            bd.setScale(Money.SCALE, Money.ROUNDING).unscaledValue().longValueExact()
        }
}
```

- [ ] **Step 4: Create `BinaryTickFeed.kt`**

```kotlin
package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Streams [Tick]s from a binary day-file ([BinaryTickFormat]). Reconstructs each value with
 * `BigDecimal.valueOf(stored, scale)` (no string parsing) and routes through [TickAssembler], so it
 * yields the exact same `Tick` sequence as [CsvTickFeed] over the same data. Reads the whole file
 * into memory (a day is a few MB) and emits ticks in order; enforces the same monotonic-timestamp
 * contract and fails loud on a corrupt/truncated file.
 */
class BinaryTickFeed(
    private val path: Path,
) : TickFeed {
    private val header: BinaryTickFormat.Header
    private val timestamps: LongArray
    private val columns: Map<Int, LongArray>
    private var index: Int = 0
    private var lastTimestamp: Long = Long.MIN_VALUE

    init {
        val bytes = Files.readAllBytes(path)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header = BinaryTickFormat.readHeader(buf)
        val n = header.tickCount
        timestamps = LongArray(n) { buf.long }
        val cols = mutableMapOf<Int, LongArray>()
        for (col in 0 until BinaryTickFormat.COL_COUNT) {
            if (BinaryTickFormat.isPresent(header.presenceFlags, col)) {
                cols[col] = LongArray(n) { buf.long }
            }
        }
        columns = cols
    }

    override fun next(): Tick? {
        if (index >= header.tickCount) return null
        val i = index++
        val ts = timestamps[i]
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
            location = "$path:${i + 1}",
        )
    }

    private fun decode(col: Int, i: Int): BigDecimal? {
        val arr = columns[col] ?: return null
        val v = arr[i]
        return if (v == BinaryTickFormat.NULL_SENTINEL) null else BigDecimal.valueOf(v, header.scale)
    }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickRoundTripTest'`
Expected: PASS — round-trip reproduces ticks, empty file works, non-monotonic fails loud.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/BinaryTickWriter.kt src/main/kotlin/com/qkt/marketdata/BinaryTickFeed.kt src/test/kotlin/com/qkt/marketdata/BinaryTickRoundTripTest.kt
git commit -m "feat(marketdata): add binary tick writer and feed"
```

---

### Task 4: Parity test — CSV feed vs binary feed

The core guarantee: convert a real CSV day to `.bin` and assert both feeds yield identical `Tick` sequences.

**Files:**
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickParityTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.marketdata

import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickParityTest {
    private fun drain(feed: TickFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        feed.use { while (true) { val t = it.next() ?: break; out.add(t) } }
        return out
    }

    /** Writes a gz CSV day file in the exact schema the dukascopy converter produces. */
    private fun writeCsvGz(path: Path, rows: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                w.write(CsvTickFeed.EXPECTED_HEADER + "\n")
                rows.forEach { w.write(it + "\n") }
            }
        }
    }

    @Test
    fun `binary feed is bit-identical to csv feed`(@TempDir dir: Path) {
        val csv = dir.resolve("2024-01-04.csv.gz")
        // ms-epoch, blank price/volume, bid/ask/vols at 8dp — the real cache schema.
        writeCsvGz(csv, listOf(
            "1712000000000,XAUUSD,,,1711.50400000,1712.00200000,0.00012000,0.00018000",
            "1712000000050,XAUUSD,,,1711.53400000,1712.00600000,0.00018000,0.00012000",
            "1712000000090,XAUUSD,,,1711.40000000,1711.90000000,0.00010000,0.00010000",
        ))
        val viaCsv = drain(CsvTickFeed(csv))

        val bin = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(bin, "XAUUSD", viaCsv)
        val viaBin = drain(BinaryTickFeed(bin))

        assertEquals(viaCsv, viaBin)
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickParityTest'`
Expected: PASS — the two feeds produce equal `Tick` lists (`Tick.equals` compares every `BigDecimal` field, scale included).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/marketdata/BinaryTickParityTest.kt
git commit -m "test(marketdata): assert binary feed parity with csv feed"
```

---

### Task 5: `DefaultDataStore` prefers `.bin`

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt` (`dayFile` ~29-41 and `openFeed` ~47-53)
- Test: `src/test/kotlin/com/qkt/marketdata/store/DataStoreBinaryFeedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.marketdata.store

import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickAssembler
import com.qkt.marketdata.source.MarketRequest
import com.qkt.common.Money
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreBinaryFeedTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `openFeed reads a bin day via BinaryTickFeed`(@TempDir root: Path) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        val ticks = listOf(
            TickAssembler.assemble("XAUUSD", 1_712_016_000_000L, null, null,
                bd("1711.50400000"), bd("1712.00200000"), null, null, "t:1"),
        )
        BinaryTickWriter().write(symDir.resolve("2024-04-02.bin"), "XAUUSD", ticks)

        val store = DefaultDataStore(root = root)
        val feed = store.openFeed(
            MarketRequest(
                symbols = listOf("XAUUSD"),
                from = Instant.parse("2024-04-02T00:00:00Z"),
                to = Instant.parse("2024-04-03T00:00:00Z"),
            ),
        )
        val out = mutableListOf<Tick>()
        feed.use { while (true) { val t = it.next() ?: break; out.add(t) } }
        assertEquals(ticks, out)
    }
}
```

(If `MarketRequest`'s constructor differs, match its actual signature in `com.qkt.marketdata.source.MarketRequest`; the test only needs `symbols`, `from`, `to`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.marketdata.store.DataStoreBinaryFeedTest'`
Expected: FAIL — `dayFile` returns no `.bin`, or `openFeed` wraps it in `CsvTickFeed` and throws on the binary header.

- [ ] **Step 3: Modify `dayFile` to prefer `.bin`**

```kotlin
    override fun dayFile(
        symbol: String,
        day: LocalDate,
    ): Path? {
        val symDir = root.resolve("symbols").resolve(symbol)
        val bin = symDir.resolve("$day.bin")
        val gz = symDir.resolve("$day.csv.gz")
        val flat = symDir.resolve("$day.csv")
        return when {
            Files.exists(bin) -> bin
            Files.exists(gz) -> gz
            Files.exists(flat) -> flat
            else -> null
        }
    }
```

- [ ] **Step 4: Modify `openFeed` to pick the feed by extension**

Change the per-day factory mapping (currently `{ CsvTickFeed(path) }`) to:

```kotlin
        val perSymbol =
            request.symbols.map { sym ->
                val days = daysCovering(fromMs, toMs)
                val factories: List<() -> TickFeed> =
                    days.mapNotNull { dayFile(sym, it) }.map { path ->
                        {
                            if (path.fileName.toString().endsWith(".bin")) {
                                com.qkt.marketdata.BinaryTickFeed(path)
                            } else {
                                CsvTickFeed(path)
                            }
                        }
                    }
                ConcatenatedTickFeed(factories)
            }
```

Add `import com.qkt.marketdata.BinaryTickFeed` to the imports (or use the fully-qualified name as above).

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests 'com.qkt.marketdata.store.DataStoreBinaryFeedTest' --tests 'com.qkt.marketdata.store.*'`
Expected: PASS — the `.bin` day opens via `BinaryTickFeed`; existing store tests still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt src/test/kotlin/com/qkt/marketdata/store/DataStoreBinaryFeedTest.kt
git commit -m "feat(marketdata): prefer binary day-files in the data store"
```

---

### Task 6: `qkt data convert` command

Migrates a symbol's `.csv.gz` cache to `.bin`, idempotently.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DataCommand.kt`
- Test: `src/test/kotlin/com/qkt/cli/DataCommandConvertTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import com.qkt.marketdata.BinaryTickFeed
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataCommandConvertTest {
    private fun writeCsvGz(path: Path, rows: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                w.write(CsvTickFeed.EXPECTED_HEADER + "\n")
                rows.forEach { w.write(it + "\n") }
            }
        }
    }

    private fun drain(feed: BinaryTickFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        feed.use { while (true) { val t = it.next() ?: break; out.add(t) } }
        return out
    }

    @Test
    fun `convert writes a bin file per csv day and is idempotent`(@TempDir root: Path) {
        val symDir = root.resolve("symbols").resolve("XAUUSD")
        writeCsvGz(symDir.resolve("2024-04-02.csv.gz"), listOf(
            "1712016000000,XAUUSD,,,1711.50400000,1712.00200000,0.00012000,0.00018000",
        ))

        val rc = DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--data-root", root.toString()))).run()
        assertEquals(ExitCodes.SUCCESS, rc)
        assertTrue(Files.exists(symDir.resolve("2024-04-02.bin")))
        assertEquals(1, drain(BinaryTickFeed(symDir.resolve("2024-04-02.bin"))).size)

        // second run is a no-op (already converted)
        val rc2 = DataCommand(Args(arrayOf("data", "convert", "XAUUSD", "--data-root", root.toString()))).run()
        assertEquals(ExitCodes.SUCCESS, rc2)
    }
}
```

(Confirm how `Args` parses positionals/subcommand. `args.positional(0)` returns `"convert"` and `positional(1)` returns `"XAUUSD"` given the existing `data verify <symbol>` shape — mirror it. If `Args` is constructed differently in existing command tests, copy that construction.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.qkt.cli.DataCommandConvertTest'`
Expected: FAIL — `data convert` is an unknown action.

- [ ] **Step 3: Add the `convert` action to `DataCommand`**

In the `when (val action = args.positional(0))` add `"convert" -> convert()` and update the usage strings to mention `convert`. Then add:

```kotlin
    private fun convert(): Int {
        val symbol =
            args.positional(1) ?: run {
                System.err.println("qkt: missing symbol. usage: qkt data convert <symbol> [--from <date>] [--to <date>] [--prune] [--data-root <dir>]")
                return ExitCodes.ARG_ERROR
            }
        val root = com.qkt.marketdata.store.DataRoot.forDataRoot(args.option("data-root"))
        val symDir = root.resolve("symbols").resolve(symbol)
        if (!Files.isDirectory(symDir)) {
            System.err.println("qkt: no cached tick data for '$symbol' at $symDir")
            return ExitCodes.USER_ERROR
        }
        val from = args.option("from")?.let { java.time.LocalDate.parse(it) }
        val to = args.option("to")?.let { java.time.LocalDate.parse(it) }
        val prune = args.flag("prune")

        val csvDays =
            Files.list(symDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".csv.gz") }
                    .sorted()
                    .toList()
            }
        val writer = com.qkt.marketdata.BinaryTickWriter()
        var converted = 0
        var skipped = 0
        for (csv in csvDays) {
            val day =
                java.time.LocalDate.parse(
                    csv.fileName.toString().removeSuffix(".csv.gz"),
                )
            if (from != null && day.isBefore(from)) continue
            if (to != null && !day.isBefore(to)) continue
            val bin = symDir.resolve("$day.bin")
            if (Files.exists(bin)) { skipped++; continue }
            val ticks = mutableListOf<com.qkt.marketdata.Tick>()
            com.qkt.marketdata.CsvTickFeed(csv).use { feed ->
                while (true) { val t = feed.next() ?: break; ticks.add(t) }
            }
            writer.write(bin, symbol, ticks)
            if (prune) Files.deleteIfExists(csv)
            converted++
        }
        println("qkt data convert: $symbol — converted=$converted skipped=$skipped prune=$prune")
        return ExitCodes.SUCCESS
    }
```

(If `Args` exposes boolean flags via a method other than `flag(...)`, use the project's existing accessor — check another command that reads a `--flag`.)

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests 'com.qkt.cli.DataCommandConvertTest'`
Expected: PASS — `.bin` written, idempotent on re-run.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/DataCommand.kt src/test/kotlin/com/qkt/cli/DataCommandConvertTest.kt
git commit -m "feat(cli): add qkt data convert to migrate the cache to binary"
```

---

### Task 7: End-to-end backtest parity + decode benchmark

Prove a real backtest is unchanged with `.bin`, and quantify the read speedup.

**Files:**
- Test: `src/test/kotlin/com/qkt/marketdata/BinaryTickDecodeBenchmarkTest.kt`

- [ ] **Step 1: Write the benchmark + scale test**

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickDecodeBenchmarkTest {
    private fun drain(feed: TickFeed): Int {
        var n = 0
        feed.use { while (it.next() != null) n++ }
        return n
    }

    @Test
    fun `binary decode reproduces csv at scale and is not slower`(@TempDir dir: Path) {
        val n = 200_000
        val csv = dir.resolve("2024-01-04.csv.gz")
        Files.createDirectories(csv.parent)
        GZIPOutputStream(Files.newOutputStream(csv)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                w.write(CsvTickFeed.EXPECTED_HEADER + "\n")
                var ts = 1_712_000_000_000L
                repeat(n) { i ->
                    val bid = 1700_00000000L + i
                    val ask = bid + 50000L
                    w.write("$ts,XAUUSD,,,${BigDecimal.valueOf(bid, 8).toPlainString()}," +
                        "${BigDecimal.valueOf(ask, 8).toPlainString()},0.00010000,0.00010000\n")
                    ts += 10
                }
            }
        }

        val csvTicks = mutableListOf<Tick>()
        CsvTickFeed(csv).use { while (true) { val t = it.next() ?: break; csvTicks.add(t) } }

        val bin = dir.resolve("2024-01-04.bin")
        BinaryTickWriter().write(bin, "XAUUSD", csvTicks)

        // correctness at scale
        val binTicks = mutableListOf<Tick>()
        BinaryTickFeed(bin).use { while (true) { val t = it.next() ?: break; binTicks.add(t) } }
        assertEquals(csvTicks, binTicks)

        // timing (informational, printed): warm once, then measure
        drain(CsvTickFeed(csv)); drain(BinaryTickFeed(bin))
        val tCsv = timeMs { drain(CsvTickFeed(csv)) }
        val tBin = timeMs { drain(BinaryTickFeed(bin)) }
        println("decode $n ticks: csv=${tCsv}ms bin=${tBin}ms speedup=${"%.1f".format(tCsv.toDouble() / tBin)}x")
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
```

- [ ] **Step 2: Run it and record the speedup**

Run: `./gradlew test --tests 'com.qkt.marketdata.BinaryTickDecodeBenchmarkTest'`
Expected: PASS; the test log prints `decode 200000 ticks: csv=...ms bin=...ms speedup=...x`. Record the speedup in the PR description. (Target ≥10×; if it is materially lower, that is the signal to add a faster codec / mmap before the format migration — note it, do not silently accept.)

- [ ] **Step 3: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` — all modules compile, ktlint clean, every test green.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/marketdata/BinaryTickDecodeBenchmarkTest.kt
git commit -m "test(marketdata): benchmark binary vs csv tick decode"
```

---

## Self-review

- **Spec coverage:** `TickAssembler` (T1) → spec component 1; binary format (T2) → component 2; writer+feed (T3) → components 3–4; parity test (T4) + end-to-end/benchmark (T7) → spec testing + success criteria; `DataStore` precedence (T5) → component 6; converter (T6) → component 5. Out-of-scope items (fixed-point pipeline, parallelism, bars, Parquet) are intentionally not tasked.
- **Type consistency:** `BinaryTickFormat.Header(symbol, tickCount, presenceFlags, version, scale, codec)`, `isPresent(flags, col)`, `writeHeader/readHeader`, `COL_*` constants, `NULL_SENTINEL`, `BinaryTickWriter().write(target, symbol, ticks)`, `BinaryTickFeed(path)` are used identically across tasks. `TickAssembler.assemble(...)` signature is fixed in T1 and reused verbatim in T3/T5.
- **Placeholder scan:** every code step shows complete code; the only flagged uncertainties are the exact `Args`/`MarketRequest` constructor shapes, called out inline with how to confirm against existing usages — not silent gaps.

## Open implementation notes (not blockers)

- **Codec:** v1 is `CODEC_RAW` (uncompressed `int64`). If the benchmark shows decode is still IO-bound or disk size is a concern on the research box, add an LZ4/Zstd codec id behind the existing `Header.codec` field without breaking the format. Decide by measurement.
- **`Args`/`MarketRequest` shapes:** confirm against an existing command test and `com.qkt.marketdata.source.MarketRequest` before writing T5/T6 tests; the plan's usages assume the shapes visible in `DataCommand` (`positional`, `option`, `flag`).
