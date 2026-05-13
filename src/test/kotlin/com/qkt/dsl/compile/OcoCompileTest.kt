package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class OcoCompileTest {
    private val candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal("100"),
            BigDecimal.ZERO,
            0L,
            1L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `BUY with OCO STOP-LIMIT builds StandaloneOCO with opposite-side legs`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal.ONE)),
                        oco =
                            OcoAst(
                                stop = ChildAt(NumLit(BigDecimal("90"))),
                                limit = ChildAt(NumLit(BigDecimal("110"))),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val req = (sigs[0] as Signal.Submit).request as OrderRequest.StandaloneOCO
        val stopLeg = req.leg1 as OrderRequest.Stop
        val limitLeg = req.leg2 as OrderRequest.Limit
        assertThat(stopLeg.stopPrice).isEqualByComparingTo("90")
        assertThat(limitLeg.limitPrice).isEqualByComparingTo("110")
        assertThat(stopLeg.side).isEqualTo(Side.SELL)
        assertThat(limitLeg.side).isEqualTo(Side.SELL)
    }
}
