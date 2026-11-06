package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `OCO_ENTRY` legs must each produce one pending order; a leg that produces none skips the pair. */
class OcoEntryLegShapeTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            candle = Candle("BACKTEST:BTCUSDT", one, one, one, BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            streams = mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val size = SizeQty(NumLit(one))

    private fun num(v: String) = NumLit(BigDecimal(v))

    private val buyStop = Buy("b", ActionOpts(sizing = size, orderType = Stop(num("105"))))
    private val sellStop = Sell("b", ActionOpts(sizing = size, orderType = Stop(num("95"))))
    private val marketBuy = Buy("b", ActionOpts(sizing = size))

    private fun fire(
        leg1: ActionAst,
        leg2: ActionAst,
    ): (EvalContext) -> List<Signal> = ActionCompiler(ExprCompiler()).compile(OcoEntry(leg1, leg2))

    @Test
    fun `two pending legs are submitted as one standalone OCO keyed on the first leg`() {
        val oco = (fire(buyStop, sellStop)(ctx).single() as Signal.Submit).request as OrderRequest.StandaloneOCO

        assertThat((oco.leg1 as OrderRequest.Stop).stopPrice).isEqualByComparingTo("105")
        assertThat((oco.leg2 as OrderRequest.Stop).stopPrice).isEqualByComparingTo("95")
        assertThat(oco.side).isEqualTo(oco.leg1.side)
    }

    @Test
    fun `a leg whose price is undefined skips the pair on every bar`() {
        val unpriced = Sell("b", ActionOpts(sizing = size, orderType = Limit(StreamFieldRef("b", "bid"))))
        val oco = fire(buyStop, unpriced)

        assertThat(oco(ctx)).isEmpty()
        assertThat(oco(ctx)).isEmpty()
    }

    @Test
    fun `a leg that is not an entry is rejected when the pair fires`() {
        assertThatThrownBy { fire(Close("b"), sellStop)(ctx) }
            .hasMessageContaining("OCO_ENTRY leg1 must compile to exactly one Signal.Submit, got [CancelPendingForSymbol")
    }

    @Test
    fun `a market leg is rejected because it is not a pending order`() {
        assertThatThrownBy { fire(marketBuy, sellStop)(ctx) }
            .hasMessageContaining("OCO_ENTRY leg1 must compile to exactly one Signal.Submit")
        assertThatThrownBy { fire(buyStop, marketBuy)(ctx) }
            .hasMessageContaining("OCO_ENTRY leg2 must compile to exactly one Signal.Submit")
    }
}
