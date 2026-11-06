package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Layered STACK entries sized at their trigger price, and STACK / OCO exits skipped while a price is undefined. */
class StackAndOcoExitSkipTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            candle = Candle("BACKTEST:BTCUSDT", one, one, one, BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            streams = mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    /** The bar has no bid, so `b.bid` is undefined. */
    private val bid = StreamFieldRef("b", "bid")

    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun entryTimes(
        op: BinOp,
        v: String,
    ) = BinaryOp(op, StackEntryRef, num(v))

    private fun stack(
        second: StackLayer,
        bracket: BracketAst? = null,
        first: SizingAst = SizeQty(num("1")),
    ) = Buy("b", ActionOpts(stack = StackLayers(listOf(StackLayer(first), second)), bracket = bracket))

    private fun fire(action: Buy): List<Signal> = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)

    private fun layerQuantities(at: ExprAst): List<BigDecimal?> {
        val req = (fire(stack(StackLayer(SizeNotional(num("1000")), at = at))).single() as Signal.Submit).request
        return (req as OrderRequest.Stack).plan.layers.map { it.resolvedQuantity }
    }

    @Test
    fun `a notional layer is sized at its multiplied or divided trigger price`() {
        assertThat(layerQuantities(entryTimes(BinOp.MUL, "2"))[1]).isEqualByComparingTo("5")
        assertThat(layerQuantities(entryTimes(BinOp.DIV, "2"))[1]).isEqualByComparingTo("20")
    }

    @Test
    fun `a layer trigger must be literal arithmetic on the entry`() {
        assertThatThrownBy { fire(stack(StackLayer(SizeQty(num("1")), at = bid))) }
            .hasMessageContaining("unsupported trigger expression type: StreamFieldRef")
    }

    @Test
    fun `a stack whose layer size, stop or frozen target is undefined emits nothing`() {
        val layer = StackLayer(SizeQty(num("1")), at = entryTimes(BinOp.SUB, "5"))

        assertThat(fire(stack(layer, first = SizeQty(bid)))).isEmpty()
        assertThat(fire(stack(layer, BracketAst(ChildAt(bid), ChildBy(num("5")))))).isEmpty()
        assertThat(fire(stack(layer, BracketAst(ChildBy(num("5")), ChildAt(bid))))).isEmpty()
    }

    @Test
    fun `an OCO exit whose stop or limit price is undefined skips the entry`() {
        fun entry(oco: OcoAst) = Buy("b", ActionOpts(sizing = SizeQty(num("1")), oco = oco))

        assertThat(fire(entry(OcoAst(ChildAt(bid), ChildAt(num("110")))))).isEmpty()
        assertThat(fire(entry(OcoAst(ChildAt(num("90")), ChildAt(bid))))).isEmpty()
        assertThat(fire(entry(OcoAst(ChildAt(num("90")), ChildAt(num("110"))))).single()).isInstanceOf(Signal.Submit::class.java)
    }
}
