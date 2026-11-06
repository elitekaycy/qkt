package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [ChildPriceResolver] price forms that resolve to nothing, the armed-trail price and the kind checks. */
class ChildPriceResolverEdgeTest {
    private val resolver = ChildPriceResolver(ExprCompiler())
    private val one = BigDecimal.ONE
    private val ec =
        EvalContext(
            candle = Candle("BACKTEST:BTCUSDT", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val entry = BigDecimal("100")

    /** The bar has no bid, so `btc.bid` is undefined. */
    private val undefined = StreamFieldRef("btc", "bid")

    private fun num(v: String) = NumLit(BigDecimal(v))

    @Test
    fun `an armed trail priced directly sits one trail distance behind the entry`() {
        val trail = resolver.compile(ChildArmedTrail(num("4"), num("10")), ChildKind.STOP_LOSS)

        assertThat(trail.evaluate(ec, Side.BUY, entry, null)).isEqualByComparingTo("96")
        assertThat(trail.evaluate(ec, Side.SELL, entry, null)).isEqualByComparingTo("104")
        assertThat(resolver.compile(ChildArmedTrail(undefined, num("10")), ChildKind.STOP_LOSS).evaluate(ec, Side.BUY, entry, null))
            .isNull()
    }

    @Test
    fun `an armed trail and a stop ratchet are stop-loss only`() {
        val ratchet = SteppedStopAst(listOf(StopStepAst(num("5"), num("1"))))

        assertThatThrownBy { resolver.compile(ChildArmedTrail(num("4"), num("10")), ChildKind.TAKE_PROFIT) }
            .hasMessageContaining("ChildArmedTrail is only valid for STOP LOSS (got TAKE_PROFIT)")
        assertThatThrownBy { resolver.compile(ChildBy(num("4"), ratchet), ChildKind.TAKE_PROFIT) }
            .hasMessageContaining("stop ratchets are only valid for STOP LOSS")
    }

    @Test
    fun `a BY distance that is undefined or not positive at fire time resolves to nothing`() {
        val by = resolver.compile(ChildBy(undefined), ChildKind.TAKE_PROFIT)
        val negative = resolver.compile(ChildBy(StreamFieldRef("btc", "close") - 5), ChildKind.TAKE_PROFIT)

        assertThat(by.evaluate(ec, Side.SELL, entry, null)).isNull()
        assertThat(negative.evaluate(ec, Side.SELL, entry, null)).isNull()
    }

    @Test
    fun `a percent stop loss resolves to a fixed stop, or to nothing while the percent is undefined`() {
        val pct = resolver.compileStopLoss(ChildPct(num("2"))) as CompiledStopLoss.Dynamic
        val unresolved = resolver.compileStopLoss(ChildPct(undefined)) as CompiledStopLoss.Dynamic

        assertThat((pct.evaluate(ec, Side.SELL, entry) as StopLossSpec.Fixed).price).isEqualByComparingTo("102")
        assertThat(unresolved.evaluate(ec, Side.SELL, entry)).isNull()
    }

    private operator fun StreamFieldRef.minus(v: Int) =
        com.qkt.dsl.ast.BinaryOp(com.qkt.dsl.ast.BinOp.SUB, this, num(v.toString()))
}
