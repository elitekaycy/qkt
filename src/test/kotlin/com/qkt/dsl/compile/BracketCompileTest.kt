package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class BracketCompileTest {
    private val candle =
        Candle(
            "BTCUSDT",
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
    fun `BUY market with BRACKET BY-RR builds Bracket OrderRequest`() {
        val action =
            Buy(
                stream = "btc",
                opts =
                    ActionOpts(
                        sizing = SizeQty(NumLit(BigDecimal.ONE)),
                        bracket =
                            BracketAst(
                                stopLoss = ChildBy(NumLit(BigDecimal("5"))),
                                takeProfit = ChildRr(NumLit(BigDecimal("3"))),
                            ),
                    ),
            )
        val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
        assertThat(sigs).hasSize(1)
        val sig = sigs[0] as Signal.Submit
        val br = sig.request as OrderRequest.Bracket
        assertThat(br.stopLoss).isEqualByComparingTo("95")
        assertThat(br.takeProfit).isEqualByComparingTo("115")
        assertThat(br.entry).isInstanceOf(OrderRequest.Market::class.java)
    }
}
