package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BarCompletenessValidator
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `--bars` reads a source's imported or fetched bars as well as bars built from ticks: a day's built `.bin` when
 * there is one, else that day's `.csv`. Before, imported bars were invisible to `--bars`, and the run was told to
 * build bars from ticks the source never had.
 */
class PrebuiltBarBarsModeTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val tuesday = monday.plusDays(1)

    private fun bars(
        day: LocalDate,
        close: String,
    ): List<Candle> {
        val start = day.toEpochDay() * 86_400_000L
        return (0..2).map { i ->
            Candle(
                "BINANCE:BTCUSDT",
                Money.of("100"),
                Money.of("110"),
                Money.of("90"),
                Money.of(close),
                Money.of("1"),
                start + i * 60_000L,
                start + (i + 1) * 60_000L,
            )
        }
    }

    private fun range() =
        TimeRange(
            Instant.ofEpochMilli(monday.toEpochDay() * 86_400_000L),
            Instant.ofEpochMilli(tuesday.plusDays(1).toEpochDay() * 86_400_000L),
        )

    @Test
    fun `imported csv bars are read in bars mode and count as coverage`(
        @TempDir root: Path,
    ) {
        val csv = LocalBarStore(root)
        csv.writeDay("BINANCE", "BTCUSDT", "1m", monday, bars(monday, "101"))
        csv.writeDay("BINANCE", "BTCUSDT", "1m", tuesday, bars(tuesday, "102"))
        val bin = BinaryBarStore(root)

        val read = PrebuiltBarReader(csv, bin).bars("BINANCE:BTCUSDT", TimeWindow.ONE_MINUTE, range())?.toList()
        assertThat(read).hasSize(6)
        assertThat(
            read?.map { it.close.stripTrailingZeros().toPlainString() }?.distinct(),
        ).containsExactly("101", "102")

        val coverage =
            BarCompletenessValidator.validate(
                bin,
                "BINANCE",
                "BTCUSDT",
                TimeWindow.ONE_MINUTE,
                monday,
                tuesday,
                TradingCalendar.crypto(),
                csv,
            )
        assertThat(coverage.missingDays).isEmpty()
    }

    @Test
    fun `a built day wins over the same day's csv, and a csv-only day still fills its gap`(
        @TempDir root: Path,
    ) {
        val csv = LocalBarStore(root)
        csv.writeDay("BINANCE", "BTCUSDT", "1m", monday, bars(monday, "101"))
        csv.writeDay("BINANCE", "BTCUSDT", "1m", tuesday, bars(tuesday, "102"))
        val bin = BinaryBarStore(root)
        bin.writeDay("BINANCE", "BTCUSDT", TimeWindow.ONE_MINUTE, monday, bars(monday, "201"))

        val read = PrebuiltBarReader(csv, bin).bars("BINANCE:BTCUSDT", TimeWindow.ONE_MINUTE, range())?.toList()
        assertThat(
            read?.map {
                it.close.stripTrailingZeros().toPlainString()
            },
        ).containsExactly("201", "201", "201", "102", "102", "102")
    }

    @Test
    fun `without the csv store coverage still counts only built days`(
        @TempDir root: Path,
    ) {
        LocalBarStore(root).writeDay("BINANCE", "BTCUSDT", "1m", monday, bars(monday, "101"))
        val coverage =
            BarCompletenessValidator.validate(
                BinaryBarStore(root),
                "BINANCE",
                "BTCUSDT",
                TimeWindow.ONE_MINUTE,
                monday,
                monday,
                TradingCalendar.crypto(),
            )
        assertThat(coverage.missingDays).containsExactly(monday)
    }
}
