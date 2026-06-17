package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The multi-strategy sweep (whole grid in one engine, shared decode/aggregation) must produce the
 * same per-combo sweep metrics as running each combo as its own backtest. Combos trade at a
 * parameter-controlled tick so they genuinely differ — a vacuous all-equal result would prove nothing.
 */
class MultiStrategySweepParityTest {
    private fun ticks() = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    private fun buyAtThenSell(buyTick: Int): Strategy {
        var seen = 0
        return object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == buyTick) emit(Signal.Buy("X", Money.of("1")))
                if (seen == 9) emit(Signal.Sell("X", Money.of("1")))
            }
        }
    }

    private val combos = listOf("b2" to 2, "b3" to 3, "b4" to 4)

    private fun solo(buyTick: Int) =
        Backtest(
            strategies = listOf("s" to buyAtThenSell(buyTick)),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `multi-strategy sweep matches per-combo on sweep metrics`() {
        val perCombo =
            BacktestSweep(
                configs = combos,
                backtestFactory = { _, bt -> solo(bt) },
            ).run().runs.associateBy { it.label }

        val multi =
            MultiStrategySweep(
                combos = combos,
                overridesOf = { mapOf("bt" to it.toString()) },
                backtestFor = { labeled ->
                    Backtest(
                        strategies = labeled.map { (label, ov) -> label to buyAtThenSell(ov.getValue("bt").toInt()) },
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
            ).run().runs.associateBy { it.label }

        val distinctPnls =
            perCombo.values
                .map {
                    it.result.global.totalPnL
                        .toPlainString()
                }.toSet()
        assertThat(distinctPnls.size).isGreaterThan(1) // combos genuinely differ
        assertThat(multi.keys).isEqualTo(perCombo.keys)
        for (label in perCombo.keys) {
            val a = perCombo.getValue(label).result.global
            val b = multi.getValue(label).result.global
            assertThat(b.tradeCount).isEqualTo(a.tradeCount)
            assertThat(b.totalPnL).isEqualByComparingTo(a.totalPnL)
            assertThat(b.sharpeRatio).isEqualTo(a.sharpeRatio)
            assertThat(b.maxDrawdown).isEqualByComparingTo(a.maxDrawdown)
        }
    }
}
