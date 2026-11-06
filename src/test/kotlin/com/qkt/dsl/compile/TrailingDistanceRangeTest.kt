package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A trailing distance or percent out of range is refused when literal and skips the order when computed. */
class TrailingDistanceRangeTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            Candle("BACKTEST:XAUUSD", one, one, one, BigDecimal("100"), BigDecimal.ZERO, 0L, 1L),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(),
        )
    private val close = StreamFieldRef("g", "close")

    private fun num(v: String) = NumLit(BigDecimal(v))

    private fun minus(
        l: ExprAst,
        r: String,
    ) = BinaryOp(BinOp.SUB, l, num(r))

    private fun fire(orderType: OrderTypeAst): List<Signal> =
        ActionCompiler(
            ExprCompiler(),
        ).compile(Buy("g", ActionOpts(sizing = SizeQty(num("1")), orderType = orderType)))(ctx)

    @Test
    fun `computed distances and percents outside the range skip the order`() {
        assertThat(fire(TrailingBy(StreamFieldRef("g", "bid")))).isEmpty()
        assertThat(fire(TrailingBy(minus(close, "150")))).isEmpty()
        assertThat(fire(TrailingPct(close))).isEmpty()
        assertThat(fire(TrailingPct(minus(close, "120")))).isEmpty()
    }

    @Test
    fun `a computed percent inside the range places a percent trailing stop`() {
        val req = (fire(TrailingPct(minus(close, "98"))).single() as Signal.Submit).request as OrderRequest.TrailingStop

        assertThat(req.trailAmount).isEqualByComparingTo("2")
    }

    @Test
    fun `literal distances and percents outside the range are refused at compile time`() {
        assertThatThrownBy {
            fire(
                TrailingBy(num("0")),
            )
        }.hasMessageContaining("TRAILING BY must be greater than 0, was 0")
        assertThatThrownBy { fire(TrailingPct(num("100"))) }
            .hasMessageContaining("TRAILING PCT must be greater than 0 and less than 100, was 100")
        assertThatThrownBy { fire(TrailingPct(num("0"))) }.hasMessageContaining("TRAILING PCT must be greater than 0")
    }
}
