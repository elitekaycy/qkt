package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * An `ON_FILL` parent or child, or a `TIMES` count, that cannot be evaluated this bar emits nothing
 * instead of a half-built order; malformed children are rejected at compile time.
 */
class OtoAndTimesSkipTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            candle = Candle("BACKTEST:BTCUSDT", one, one, one, BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            streams = mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private val close = StreamFieldRef("b", "close")

    /** The bar has no bid, so `b.bid` is undefined. */
    private val bid = StreamFieldRef("b", "bid")

    private fun qty(v: String) = SizeQty(NumLit(BigDecimal(v)))

    private fun fire(action: ActionAst): List<Signal> = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)

    private fun oto(
        parent: ActionOpts,
        vararg children: ActionAst,
    ) = Buy("b", parent.copy(onFill = children.toList()))

    private val child = Sell("b", ActionOpts(sizing = qty("1")))

    @Test
    fun `an OTO parent whose entry price, size or limit leg is undefined emits nothing`() {
        assertThat(fire(oto(ActionOpts(sizing = qty("1"), orderType = Limit(bid)), child))).isEmpty()
        assertThat(fire(oto(ActionOpts(sizing = SizeQty(bid)), child))).isEmpty()
        assertThat(fire(oto(ActionOpts(sizing = qty("1"), orderType = StopLimit(close, bid)), child))).isEmpty()
    }

    @Test
    fun `an OTO whose child price is undefined emits nothing, and one that resolves submits the group`() {
        val unpriced = Sell("b", ActionOpts(sizing = qty("1"), orderType = Limit(bid)))

        assertThat(fire(oto(ActionOpts(sizing = qty("1")), unpriced))).isEmpty()
        assertThat(fire(oto(ActionOpts(sizing = qty("1")), child)).single()).isInstanceOf(Signal.Submit::class.java)
    }

    @Test
    fun `ON_FILL children must be plain BUY or SELL orders`() {
        val parent = ActionOpts(sizing = qty("1"))
        val bracket = BracketAst(ChildBy(NumLit(one)), ChildBy(NumLit(one)))
        val stack = StackSpacing(2, NumLit(one), StackDirection.TRADE_DIRECTION)

        assertThatThrownBy { fire(oto(parent, Close("b"))) }
            .hasMessageContaining("ON_FILL children must be BUY or SELL actions; got Close")
        assertThatThrownBy { fire(oto(parent, Sell("b", ActionOpts(sizing = qty("1"), bracket = bracket)))) }
            .hasMessageContaining("ON_FILL children cannot carry a BRACKET or OCO in v1.")
        assertThatThrownBy { fire(oto(parent, Sell("b", ActionOpts(sizing = qty("1"), stack = stack)))) }
            .hasMessageContaining("ON_FILL children cannot carry STACK/STACK_AT in v1.")
    }

    private fun times(count: ExprAst) = Buy("b", ActionOpts(sizing = qty("1"), times = count))

    @Test
    fun `TIMES repeats the entry, emits nothing while undefined and suppresses counts above the cap`() {
        assertThat(fire(times(NumLit(BigDecimal("2.9"))))).hasSize(2)
        assertThat(fire(times(bid))).isEmpty()

        val capped = fire(times(NumLit(ActionCompiler.MAX_TIMES.add(one)))).single() as Signal.Suppressed

        assertThat(capped.symbol).isEqualTo("BACKTEST:BTCUSDT")
        assertThat(capped.reason).contains("above the ${ActionCompiler.MAX_TIMES} repetition cap")
    }

    @Test
    fun `a non-numeric TIMES count fails when the rule fires`() {
        assertThatThrownBy { fire(times(BoolLit(true))) }.hasMessageContaining("TIMES must be numeric")
    }
}
