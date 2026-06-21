package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskIntegrationTest {
    private fun buyThenSell(
        buyAt: Int,
        sellAt: Int,
    ): Strategy =
        object : Strategy {
            private var seen = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == buyAt) emit(Signal.Buy("X", Money.of("1")))
                if (seen == sellAt) emit(Signal.Sell("X", Money.of("1")))
            }
        }

    private fun ticks() = (1..12).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    @Test
    fun `two-strategy backtest produces a book-risk series with exposure`() {
        val result =
            Backtest(
                strategies = listOf("alpha" to buyThenSell(2, 8), "beta" to buyThenSell(3, 9)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
            ).run()

        val br = result.bookRisk
        assertThat(br).isNotNull
        assertThat(br!!.series).isNotEmpty
        assertThat(br.maxGrossExposure).isGreaterThan(BigDecimal.ZERO)
        assertThat(br.maxGrossExposure).isGreaterThanOrEqualTo(br.maxNetExposure)
    }

    @Test
    fun `single-strategy backtest has null book risk`() {
        val result =
            Backtest(
                strategies = listOf("solo" to buyThenSell(2, 8)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
                startingBalance = BigDecimal("10000"),
            ).run()

        assertThat(result.bookRisk).isNull()
    }
}
