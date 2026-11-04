package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.DefaultDataStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `ticks/<LABEL>/<SYMBOL>/` holds one venue's ticks; a bare `symbols/<SYMBOL>/` store stays the fallback. */
class VenueTickStoreTest {
    @TempDir lateinit var dir: Path

    private val day = Instant.parse("2026-10-28T00:00:00Z").toEpochMilli()
    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    private fun writeDay(
        dir: Path,
        price: String,
    ) {
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("2026-10-28.csv"),
            "$header\n${day + 1_000},AAPL,$price,,,,,\n${day + 61_000},AAPL,$price,,,,,\n",
        )
    }

    private fun source(): LocalMarketSource {
        val clock = FixedClock(time = day + 86_400_000)
        return LocalMarketSource(DefaultDataStore(root = dir, clock = clock), clock)
    }

    private fun prices(ticks: Sequence<Tick>): List<String> =
        ticks
            .map {
                it.price.stripTrailingZeros().toPlainString()
            }.toList()

    private val range = TimeRange(Instant.ofEpochMilli(day), Instant.ofEpochMilli(day + 86_400_000))

    @Test
    fun `two venues sharing a bare symbol each replay their own ticks`() {
        writeDay(dir.resolve("ticks/EXNESS/AAPL"), "270.10")
        writeDay(dir.resolve("ticks/ALPACA/AAPL"), "270.05")

        val src = source()

        assertThat(prices(src.ticks("EXNESS:AAPL", range))).containsExactly("270.1", "270.1")
        assertThat(prices(src.ticks("ALPACA:AAPL", range))).containsExactly("270.05", "270.05")
        assertThat(
            src
                .ticks("ALPACA:AAPL", range)
                .map { it.symbol }
                .distinct()
                .toList(),
        ).containsExactly("ALPACA:AAPL")
        assertThat(prices(src.tickSlice("EXNESS:AAPL", day, day + 2_000))).containsExactly("270.1")
    }

    @Test
    fun `a venue without its own directory reads the bare-symbol store`() {
        writeDay(dir.resolve("ticks/EXNESS/AAPL"), "270.10")
        writeDay(dir.resolve("symbols/AAPL"), "269.00")

        val src = source()

        assertThat(prices(src.ticks("BACKTEST:AAPL", range))).containsExactly("269", "269")
        assertThat(prices(src.ticks("AAPL", range))).containsExactly("269", "269")
    }
}
