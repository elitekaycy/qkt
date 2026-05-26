package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.observability.LatencyStage
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestLatencyTrackingTest {
    private fun ticks(count: Int): List<Tick> =
        (1..count).map { i ->
            Tick(
                symbol = "BACKTEST:BTCUSDT",
                price = Money.of((30_000 + i).toString()),
                timestamp = i * 60_000L,
            )
        }

    private fun buyOnEachTick(): Strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                emit(Signal.Buy(tick.symbol, Money.of("0.001")))
            }
        }

    @Test
    fun `latencyEnabled false produces a null report on the result`() {
        val result =
            Backtest(
                strategies = listOf("s1" to buyOnEachTick()),
                ticks = ticks(5),
                candleWindow = TimeWindow.ONE_MINUTE,
                latencyEnabled = false,
            ).run()
        assertThat(result.trades).isNotEmpty()
        assertThat(result.latencyReport).isNull()
    }

    @Test
    fun `latencyEnabled true populates the report with non-zero stage samples`() {
        val result =
            Backtest(
                strategies = listOf("s1" to buyOnEachTick()),
                ticks = ticks(5),
                candleWindow = TimeWindow.ONE_MINUTE,
                latencyEnabled = true,
            ).run()

        assertThat(result.trades.size).isEqualTo(5)

        val report = result.latencyReport
        assertThat(report).isNotNull()
        assertThat(report!!.enabled).isTrue()
        val byStage = report.strategies["s1"]
        assertThat(byStage).isNotNull()

        // SIGNAL_TO_SUBMISSION fires once per emit; expect 5 samples for 5 ticks.
        val s2s = byStage!![LatencyStage.SIGNAL_TO_SUBMISSION]!!
        assertThat(s2s.count).isGreaterThanOrEqualTo(5)
        assertThat(s2s.maxNanos).isGreaterThan(0L)

        // SUBMISSION_TO_FILL bridges OrderEvent → OrderFilled; PaperBroker fills
        // synchronously so we expect one sample per fill.
        val s2f = byStage[LatencyStage.SUBMISSION_TO_FILL]!!
        assertThat(s2f.count).isGreaterThanOrEqualTo(5)
        assertThat(s2f.maxNanos).isGreaterThan(0L)
    }
}
