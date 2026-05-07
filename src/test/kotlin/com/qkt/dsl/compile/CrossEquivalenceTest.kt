package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.crossesBelow
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import com.qkt.strategy.EmaCrossoverStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrossEquivalenceTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private val sample =
        ticks(
            listOf(
                "100", "101", "102", "104", "108", "112", "115", "120", "118", "115",
                "112", "110", "108", "105", "100", "95", "92", "90", "88", "85",
                "80", "82", "85", "90", "95", "100", "108", "115", "120", "125",
            ),
        )

    @Test
    fun `dsl ema crossover with CROSSES equals handwritten`() {
        val ast =
            strategy("ema_x", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 3))
                val slow by letting(ema(btc.close, period = 7))
                rule {
                    whenever(fast crossesAbove slow)
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever(fast crossesBelow slow)
                    then { sell(btc, qty = 1.bd) }
                }
            }

        val dslStrategy = AstCompiler().compile(ast)
        val handStrategy = EmaCrossoverStrategy(symbol = "BTCUSDT", fastPeriod = 3, slowPeriod = 7)

        val dslResult =
            Backtest(
                strategies = listOf("ema_x" to dslStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val handResult =
            Backtest(
                strategies = listOf("ema_x" to handStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(dslResult.global.totalPnL).isEqualByComparingTo(handResult.global.totalPnL)
        assertThat(dslResult.trades.map { it.trade.symbol to it.trade.side })
            .isEqualTo(handResult.trades.map { it.trade.symbol to it.trade.side })
    }
}
