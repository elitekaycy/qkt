package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.riskFrac
import com.qkt.dsl.kotlin.strategy
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultsEndToEndTest {
    @Test
    fun `RISK 1 percent with DEFAULTS bracket and 10000 equity yields qty 20`() {
        val ast =
            strategy("risk_pct", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                defaults {
                    stopLoss = childBy(5.bd)
                    takeProfit = childRr(3.bd)
                }
                rule {
                    whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
                    then { buy(btc, sizing = riskFrac(0.01.bd)) }
                }
            }
        val strategy = AstCompiler().compile(ast)

        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = BigDecimal("10000")

                override fun balance(): BigDecimal = BigDecimal("10000")
            }
        val ctx = testStrategyContext(pnl = pnl)

        val captured = mutableListOf<Signal>()
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
        val br = submits.first().request as OrderRequest.Bracket
        assertThat(br.quantity).isEqualByComparingTo("20")
        assertThat((br.stopLoss as com.qkt.execution.StopLossSpec.Fixed).price).isEqualByComparingTo("105")
        assertThat(br.takeProfit).isEqualByComparingTo("125")
        assertThat(br.side).isEqualTo(Side.BUY)
    }
}
