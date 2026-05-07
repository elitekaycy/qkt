package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.minus
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.runMax
import com.qkt.dsl.kotlin.sinceOpen
import com.qkt.dsl.kotlin.strategy
import com.qkt.indicators.catalog.EMA
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SnapshotEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private class TrailingStopRef(
        private val symbol: String,
        fastPeriod: Int,
        slowPeriod: Int,
        private val drop: BigDecimal,
    ) : Strategy {
        private val fast = EMA(fastPeriod)
        private val slow = EMA(slowPeriod)
        private var prevFastAbove: Boolean? = null
        private var runningMax: BigDecimal? = null
        private var prevQty: BigDecimal = BigDecimal.ZERO

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
            val curQty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            // Reset running max on transitions (matches DSL SINCE OPEN reset semantics)
            val wasZero = prevQty.signum() == 0
            val isZero = curQty.signum() == 0
            if (wasZero && !isZero) runningMax = null
            if (!wasZero && isZero) runningMax = null
            prevQty = curQty

            fast.update(candle.close)
            slow.update(candle.close)
            if (!fast.isReady || !slow.isReady) return

            // Update running max while in position
            if (curQty.signum() != 0) {
                runningMax = runningMax?.let { it.max(candle.close) } ?: candle.close
            }

            val above = fast.value()!! > slow.value()!!
            val prev = prevFastAbove
            prevFastAbove = above

            // Exit when long and close drops more than `drop` from running max
            if (curQty > BigDecimal.ZERO) {
                val rm = runningMax
                if (rm != null && candle.close < rm.subtract(drop)) {
                    emit(Signal.Sell(symbol, BigDecimal.ONE))
                    return
                }
            }

            // Entry: cross above + flat
            if (prev == false && above && curQty.signum() == 0) {
                emit(Signal.Buy(symbol, BigDecimal.ONE))
            }
        }
    }

    @Test
    fun `dsl trailing stop equals handwritten reference`() {
        val sample =
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

        val ast =
            strategy("trail", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 3))
                val slow by letting(ema(btc.close, period = 7))
                val hwm by letting(runMax(btc.close, sinceOpen))
                rule {
                    whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
                    then { buy(btc, qty = 1.bd) }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (btc.close lt (hwm - 5.bd)))
                    then { sell(btc, qty = 1.bd) }
                }
            }

        val dslStrategy = AstCompiler().compile(ast)
        val refStrategy = TrailingStopRef("BTCUSDT", 3, 7, BigDecimal("5"))

        val dsl =
            Backtest(
                strategies = listOf("trail" to dslStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val ref =
            Backtest(
                strategies = listOf("trail" to refStrategy),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(dsl.global.totalPnL).isEqualByComparingTo(ref.global.totalPnL)
        assertThat(dsl.trades.map { it.trade.symbol to it.trade.side })
            .isEqualTo(ref.trades.map { it.trade.symbol to it.trade.side })
        assertThat(dsl.trades).isNotEmpty
    }
}
