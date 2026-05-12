package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.Stop
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class OcoEntryCompileTest {
    private val candle =
        Candle(
            "XAUUSD",
            BigDecimal("2000"),
            BigDecimal("2000"),
            BigDecimal("2000"),
            BigDecimal("2000"),
            BigDecimal.ZERO,
            0L,
            300_000L,
        )

    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("gold" to HubKey("BACKTEST", "XAUUSD", "5m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `OcoEntry compiles to a single Submit wrapping StandaloneOCO with both legs`() {
        val action =
            OcoEntry(
                leg1 =
                    Buy(
                        stream = "gold",
                        opts =
                            ActionOpts(
                                sizing = SizeQty(NumLit(BigDecimal("0.20"))),
                                orderType = Stop(NumLit(BigDecimal("2010"))),
                            ),
                    ),
                leg2 =
                    Sell(
                        stream = "gold",
                        opts =
                            ActionOpts(
                                sizing = SizeQty(NumLit(BigDecimal("0.20"))),
                                orderType = Stop(NumLit(BigDecimal("1990"))),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val oco = (sigs[0] as Signal.Submit).request as OrderRequest.StandaloneOCO
        val buy = oco.leg1 as OrderRequest.Stop
        val sell = oco.leg2 as OrderRequest.Stop
        assertThat(buy.side).isEqualTo(Side.BUY)
        assertThat(sell.side).isEqualTo(Side.SELL)
        assertThat(buy.stopPrice).isEqualByComparingTo("2010")
        assertThat(sell.stopPrice).isEqualByComparingTo("1990")
    }
}
