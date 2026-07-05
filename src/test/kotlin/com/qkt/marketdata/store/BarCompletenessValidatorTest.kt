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

class BarCompletenessValidatorTest {
    @Test
    fun `reports every missing trading day`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        val first = LocalDate.parse("2026-01-01")
        val start = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        store.writeDay(
            "BACKTEST",
            "BTCUSDT",
            TimeWindow.ONE_MINUTE,
            first,
            listOf(
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    start,
                    start + 60_000L,
                ),
            ),
        )

        val coverage =
            BarCompletenessValidator.validate(
                store,
                "BACKTEST",
                "BTCUSDT",
                TimeWindow.ONE_MINUTE,
                first,
                first.plusDays(1),
                TradingCalendar.crypto(),
            )

        assertThat(coverage.requestedTradingDays).isEqualTo(2)
        assertThat(coverage.coveredTradingDays).isEqualTo(1)
        assertThat(coverage.missingDays).containsExactly(first.plusDays(1))
    }
}
