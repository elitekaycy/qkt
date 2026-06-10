package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HandwrittenEquivalenceTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private val sample =
        ticks(
            listOf(
                "100",
                "101",
                "102",
                "104",
                "108",
                "112",
                "115",
                "120",
                "118",
                "115",
                "112",
                "110",
                "108",
                "105",
                "100",
                "95",
                "92",
                "90",
                "88",
                "85",
                "80",
                "82",
                "85",
                "90",
                "95",
                "100",
                "108",
                "115",
                "120",
                "125",
            ),
        )

    /**
     * Hand-written equivalent of the two DSL threshold rules. Rule actions are
     * edge-driven, so each side keeps its own edge memory: it emits on the bar the
     * condition turns true and stays silent while it holds.
     */
    private class ThresholdStrategy(
        private val symbol: String,
        private val threshold: BigDecimal,
        private val qty: BigDecimal,
    ) : Strategy {
        private var wasAbove = false
        private var wasBelow = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}

        override fun onCandle(
            candle: Candle,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (candle.symbol != symbol) return
            val above = candle.close > threshold
            val below = candle.close < threshold
            if (above && !wasAbove) emit(Signal.Buy(symbol, qty))
            if (below && !wasBelow) emit(Signal.Sell(symbol, qty))
            wasAbove = above
            wasBelow = below
        }
    }

    @Test
    fun `dsl threshold strategy equals handwritten over the same fixture`() {
        val ast =
            strategy("threshold", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever(btc.close lt 100.bd)
                    then { sell(btc, qty = 1.bd) }
                }
            }

        val dslStrategy = AstCompiler().compile(ast)
        val handStrategy =
            ThresholdStrategy(symbol = "BACKTEST:BTCUSDT", threshold = BigDecimal("100"), qty = BigDecimal.ONE)

        val dslResult =
            Backtest(
                strategies = listOf("threshold" to dslStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val handResult =
            Backtest(
                strategies = listOf("threshold" to handStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(dslResult.global.totalPnL).isEqualByComparingTo(handResult.global.totalPnL)
        assertThat(dslResult.trades.map { it.trade.symbol to it.trade.side })
            .isEqualTo(handResult.trades.map { it.trade.symbol to it.trade.side })
        assertThat(dslResult.trades).isNotEmpty
    }
}
