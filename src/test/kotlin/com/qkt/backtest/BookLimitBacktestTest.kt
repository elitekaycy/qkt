package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.book.BookLimits
import com.qkt.risk.book.BookRiskConfig
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookLimitBacktestTest {
    private fun buyer(
        buyAt: Int,
        qty: String,
    ): Strategy =
        object : Strategy {
            private var seen = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == buyAt) emit(Signal.Buy("X", Money.of(qty)))
            }
        }

    private fun ticks() = (1..12).map { Tick("X", Money.of("100"), it * 60_000L) }

    @Test
    fun `book gross cap rejects the order that would breach it`() {
        // capital 10000, gross cap 0.03x => 300. alpha buys 3@100=300 (ok); beta's 3@100 would push
        // the book to 600 > 300 -> rejected with a book-gross reason.
        val result =
            Backtest(
                strategies = listOf("alpha" to buyer(2, "3"), "beta" to buyer(4, "3")),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
                bookRiskConfig = BookRiskConfig(limits = BookLimits(maxGrossExposure = BigDecimal("0.03"))),
            ).run()

        assertThat(result.rejections.any { it.reason.contains("book gross") }).isTrue()
    }

    @Test
    fun `no book config means no book rejections`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to buyer(2, "3"), "beta" to buyer(4, "3")),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
            ).run()

        assertThat(result.rejections.none { it.reason.contains("book gross") }).isTrue()
    }
}
