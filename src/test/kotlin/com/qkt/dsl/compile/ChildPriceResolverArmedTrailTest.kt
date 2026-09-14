package com.qkt.dsl.compile

import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #48 — bracket-action compile path for ChildArmedTrail. Static at compile time
 * (distance and threshold are NumLit literals); the engine-managed arming behaviour
 * is OrderManager's responsibility — this test only pins the resolver's output shape.
 */
class ChildPriceResolverArmedTrailTest {
    private val resolver = ChildPriceResolver(ExprCompiler())

    @Test
    fun `ChildArmedTrail compiles to CompiledStopLoss Static with the parsed distance and threshold`() {
        val ast =
            ChildArmedTrail(
                trailDistance = NumLit(BigDecimal("5")),
                mfeThreshold = NumLit(BigDecimal("10")),
            )
        val compiled = resolver.compileStopLoss(ast)
        assertThat(compiled).isInstanceOf(CompiledStopLoss.Static::class.java)
        val spec = (compiled as CompiledStopLoss.Static).spec
        assertThat(spec).isInstanceOf(StopLossSpec.ArmedTrail::class.java)
        val armed = spec as StopLossSpec.ArmedTrail
        assertThat(armed.trailDistance).isEqualByComparingTo("5")
        assertThat(armed.mfeThreshold).isEqualByComparingTo("10")
    }

    private fun ec(close: String) =
        EvalContext(
            candle =
                com.qkt.marketdata.Candle(
                    "BACKTEST:G",
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal.ZERO,
                    0L,
                    60_000L,
                ),
            streams = mapOf("g" to HubKey("BACKTEST", "G", "1m")),
            lets = emptyMap(),
            strategyContext = com.qkt.strategy.testStrategyContext(),
        )

    @Test
    fun `an expression trail distance and threshold are evaluated when the order is built (#1116)`() {
        val ast =
            ChildArmedTrail(
                trailDistance = StreamFieldRef("g", "close"),
                mfeThreshold = NumLit(BigDecimal("10")),
            )
        val compiled = resolver.compileStopLoss(ast)
        assertThat(compiled).isInstanceOf(CompiledStopLoss.Dynamic::class.java)
        val spec =
            (compiled as CompiledStopLoss.Dynamic).evaluate(
                ec("4.5"),
                com.qkt.common.Side.BUY,
                BigDecimal("100"),
            )
        assertThat(
            spec,
        ).isEqualTo(StopLossSpec.ArmedTrail(trailDistance = BigDecimal("4.5"), mfeThreshold = BigDecimal("10")))
    }

    @Test
    fun `an expression that evaluates to a value the spec rejects skips the order instead of throwing`() {
        val ast =
            ChildArmedTrail(
                trailDistance = NumLit(BigDecimal("5")),
                mfeThreshold = StreamFieldRef("g", "close"),
            )
        val compiled = resolver.compileStopLoss(ast) as CompiledStopLoss.Dynamic
        // A negative MFE threshold is invalid: no stop spec, so the order is skipped.
        assertThat(compiled.evaluate(ec("-1"), com.qkt.common.Side.BUY, BigDecimal("100"))).isNull()
    }

    @Test
    fun `a STACK bracket still requires literal trail operands`() {
        val ast =
            ChildArmedTrail(
                trailDistance = StreamFieldRef("g", "close"),
                mfeThreshold = NumLit(BigDecimal("10")),
            )
        assertThatThrownBy { resolver.compileStopLoss(ast, allowExpressionDistances = false) }
            .hasMessageContainingAll("TRAILING <distance>", "numeric literal", "STACK")
    }

    @Test
    fun `non-armed ChildAt compiles to CompiledStopLoss Dynamic`() {
        val ast = ChildAt(NumLit(BigDecimal("100")))
        val compiled = resolver.compileStopLoss(ast)
        assertThat(compiled).isInstanceOf(CompiledStopLoss.Dynamic::class.java)
    }
}
