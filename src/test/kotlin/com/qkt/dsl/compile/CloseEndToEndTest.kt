package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.strategy
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CloseEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    @Test
    fun `CLOSE fires when condition holds while in position`() {
        // Prices: enter long at start (close>105), then drop below 90 → CLOSE.
        val sample =
            ticks(
                listOf("100", "108", "110", "115", "100", "95", "90", "85", "82", "85"),
            )

        val ast =
            strategy("close_on_drop", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (btc.close lt 90.bd))
                    then { closeStream(btc) }
                }
            }

        val strat = AstCompiler().compile(ast)
        val result =
            Backtest(
                strategies = listOf("close_on_drop" to strat),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val sides = result.trades.map { it.trade.side }
        assertThat(sides).contains(Side.BUY)
        assertThat(sides).contains(Side.SELL)
        assertThat(result.trades).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `CLOSE_ALL flattens any open position via the DSL`() {
        // Same shape but uses closeAll.
        val sample =
            ticks(
                listOf("100", "108", "110", "115", "100", "95", "90", "85", "82", "85"),
            )

        val ast =
            strategy("close_all_on_drop", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (btc.close lt 90.bd))
                    then { closeAll() }
                }
            }

        val strat = AstCompiler().compile(ast)
        val result =
            Backtest(
                strategies = listOf("close_all_on_drop" to strat),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val sides = result.trades.map { it.trade.side }
        assertThat(sides).contains(Side.BUY)
        assertThat(sides).contains(Side.SELL)
    }
}
