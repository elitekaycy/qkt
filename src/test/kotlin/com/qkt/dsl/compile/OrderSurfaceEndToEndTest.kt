package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.bracket
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.limitAt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.riskAbs
import com.qkt.dsl.kotlin.strategy
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderSurfaceEndToEndTest {
    @Test
    fun `LIMIT entry with BRACKET emits Submit Bracket signal with correct prices`() {
        val ast =
            strategy("limit_bracket", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
                    then {
                        buy(
                            stream = btc,
                            sizing = riskAbs(50.bd),
                            orderType = limitAt(99.bd),
                            bracket =
                                bracket(
                                    stopLoss = childBy(5.bd),
                                    takeProfit = childRr(3.bd),
                                ),
                        )
                    }
                }
            }
        val strategy = AstCompiler().compile(ast)

        val captured = mutableListOf<Signal>()
        val ctx = testStrategyContext()
        val c =
            Candle(
                "BACKTEST:BTCUSDT",
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal.ZERO,
                0L,
                60_000L,
            )
        strategy.onCandle(c, ctx, captured::add)

        val submits = captured.filterIsInstance<Signal.Submit>()
        assertThat(submits).isNotEmpty
        val bracketSig = submits.first { it.request is OrderRequest.Bracket }
        val br = bracketSig.request as OrderRequest.Bracket
        // entry = limit at 99 (fixed), stop loss BY 5 → 94, take profit RR 3 → 99 + 3*5 = 114
        assertThat((br.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("94")
        assertThat(br.takeProfit).isEqualByComparingTo("114")
        assertThat(br.entry).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat(br.side).isEqualTo(Side.BUY)
        // RISK $50 / stop distance 5 = qty 10
        assertThat(br.quantity).isEqualByComparingTo("10")
    }
}
