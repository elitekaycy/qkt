package com.qkt.dsl.compile

import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.NumLit
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

    @Test
    fun `non-literal trail distance is rejected at compile time`() {
        val ast =
            ChildArmedTrail(
                trailDistance = com.qkt.dsl.ast.StreamFieldRef("g", "close"),
                mfeThreshold = NumLit(BigDecimal("10")),
            )
        assertThatThrownBy { resolver.compileStopLoss(ast) }
            .hasMessageContaining("literal")
    }

    @Test
    fun `non-literal threshold is rejected at compile time`() {
        val ast =
            ChildArmedTrail(
                trailDistance = NumLit(BigDecimal("5")),
                mfeThreshold = com.qkt.dsl.ast.StreamFieldRef("g", "close"),
            )
        assertThatThrownBy { resolver.compileStopLoss(ast) }
            .hasMessageContaining("literal")
    }

    @Test
    fun `non-armed ChildAt compiles to CompiledStopLoss Dynamic`() {
        val ast = com.qkt.dsl.ast.ChildAt(NumLit(BigDecimal("100")))
        val compiled = resolver.compileStopLoss(ast)
        assertThat(compiled).isInstanceOf(CompiledStopLoss.Dynamic::class.java)
    }
}
