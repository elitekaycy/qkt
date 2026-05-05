package com.qkt.marketdata.history

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

class StoreHistoricalDataProviderTest {
    @TempDir lateinit var dir: Path

    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private val day15: Long = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private fun seed() {
        val symDir = dir.resolve("symbols").resolve("X")
        Files.createDirectories(symDir)
        val rows =
            "$header\n" +
                "${day15 + 1000L},X,100,1,,,,\n" +
                "${day15 + 2000L},X,101,1,,,,\n" +
                "${day15 + 3000L},X,102,1,,,,"
        Files.writeString(symDir.resolve("2024-01-15.csv"), rows)
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
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-16T00:00:00Z"),
            )
        val ts = p.ticks("X", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(day15 + 1000L, day15 + 2000L, day15 + 3000L)
    }

    @Test
    fun `ticks excludes range to half open`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.ofEpochMilli(day15 + 2000L),
            )
        val ts = p.ticks("X", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(day15 + 1000L)
    }

    @Test
    fun `look ahead query throws`() {
        val p = providerAt("2024-01-15T00:00:00Z")
        val range =
            TimeRange(
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
        val range =
            TimeRange(
                Instant.parse("2024-01-15T00:00:00Z"),
                Instant.parse("2024-01-17T00:00:00Z"),
            )
        val ts = p.ticks("Y", range).toList().map { it.timestamp }
        assertThat(ts).containsExactly(1705276800000L, 1705363200000L)
    }

    @Test
    fun `candles aggregates ticks via TimeWindow`() {
        val p = providerAt("2024-01-16T00:00:00Z")
        val range =
            TimeRange(
                Instant.ofEpochMilli(day15 + 1000L),
                Instant.ofEpochMilli(day15 + 4000L),
            )
        val candles = p.candles("X", TimeWindow(2_000L), range).toList()
        assertThat(candles).hasSize(2)
        assertThat(candles[0].close).isEqualByComparingTo(Money.of("100"))
        assertThat(candles[1].high).isEqualByComparingTo(Money.of("102"))
    }
}
