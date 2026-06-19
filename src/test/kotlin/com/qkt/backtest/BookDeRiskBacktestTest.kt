package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.book.BookRiskConfig
import com.qkt.risk.book.DeRisk
import com.qkt.risk.book.Rung
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookDeRiskBacktestTest {
    private fun buyer(buyAt: Int): Strategy =
        object : Strategy {
            private var seen = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == buyAt) emit(Signal.Buy("X", Money.of("1")))
            }
        }

    // Price holds at 110 then falls, so the first entry goes underwater and the book draws down.
    private fun ticks() =
        (1..12).map { i ->
            val px = if (i <= 3) 110 else 110 - (i - 3) * 2
            Tick("X", Money.of(px.toString()), i * 60_000L)
        }

    private val ladder = DeRisk(listOf(Rung(BigDecimal("0.0001"), BigDecimal("0.0"))))

    @Test
    fun `book de-risk suppresses new risk after the book draws down`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to buyer(2), "beta" to buyer(6)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
                bookRiskConfig = BookRiskConfig(deRisk = ladder),
            ).run()

        assertThat(result.rejections.any { it.reason.contains("book de-risk") }).isTrue()
    }

    @Test
    fun `no de-risk config means no de-risk suppression`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to buyer(2), "beta" to buyer(6)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
            ).run()

        assertThat(result.rejections.none { it.reason.contains("book de-risk") }).isTrue()
    }
}
