package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.TickAssembler
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
    fun `ticks resolves a broker-prefixed symbol to the bare store and stamps the prefixed id`() {
        val src = sourceAt("2024-01-16T00:00:00Z")
        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        val ticks = src.ticks("BACKTEST:X", range).toList()
        assertThat(ticks.map { it.timestamp }).containsExactly(day15 + 1000L, day15 + 31_000L, day15 + 61_000L)
        assertThat(ticks.map { it.symbol }).allMatch { it == "BACKTEST:X" }
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

    @Test
    fun `ticks reads a binary day-file through the store`() {
        val symDir = dir.resolve("symbols").resolve("B")
        Files.createDirectories(symDir)
        val ticks =
            listOf(
                TickAssembler.assemble(
                    "B",
                    day15 + 1000L,
                    Money.of("100"),
                    Money.of("1"),
                    null,
                    null,
                    null,
                    null,
                    "t:1",
                ),
                TickAssembler.assemble(
                    "B",
                    day15 + 31_000L,
                    Money.of("101"),
                    Money.of("1"),
                    null,
                    null,
                    null,
                    null,
                    "t:2",
                ),
            )
        BinaryTickWriter().write(symDir.resolve("2024-01-15.bin"), "B", ticks)
        val clock = FixedClock(time = Instant.parse("2024-01-16T00:00:00Z").toEpochMilli())
        val src = LocalMarketSource(DefaultDataStore(root = dir, clock = clock), clock)
        val range = TimeRange(Instant.parse("2024-01-15T00:00:00Z"), Instant.parse("2024-01-16T00:00:00Z"))
        val ts = src.ticks("B", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(day15 + 1000L, day15 + 31_000L)
    }
}
