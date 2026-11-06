package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarCompletenessWeeklyTest {
    private val week = TimeWindow(604_800_000L)

    @Test
    fun `a weekly timeframe covers the day holding its bar and misses trading days without one`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        val monday = LocalDate.parse("2026-01-05")
        val at = monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val one = BigDecimal.ONE
        store.writeDay(
            "BACKTEST",
            "BTCUSDT",
            week,
            monday,
            listOf(Candle("BACKTEST:BTCUSDT", one, one, one, one, one, at, at + week.durationMs)),
        )

        val coverage =
            BarCompletenessValidator.validate(
                store,
                "BACKTEST",
                "BTCUSDT",
                week,
                monday,
                monday.plusDays(2),
                TradingCalendar.crypto(),
            )

        assertThat(coverage.requestedTradingDays).isEqualTo(3)
        assertThat(coverage.coveredTradingDays).isEqualTo(1)
        assertThat(coverage.missingDays).containsExactly(monday.plusDays(1), monday.plusDays(2))
        assertThat(coverage.days.filter { it.day != monday }.map { it.detail }).containsOnly("no bars")
    }
}
