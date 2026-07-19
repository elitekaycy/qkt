package com.qkt.dsl.compile

import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.TimeTightenAst
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPriceResolverStopRatchetTest {
    private val resolver = ChildPriceResolver(ExprCompiler())

    @Test
    fun `stepped stop compiles to a static stop spec`() {
        val compiled =
            resolver.compileStopLoss(
                ChildBy(
                    NumLit(BigDecimal("50")),
                    SteppedStopAst(
                        listOf(
                            StopStepAst(NumLit(BigDecimal("30")), NumLit(BigDecimal.ZERO)),
                            StopStepAst(NumLit(BigDecimal("70")), NumLit(BigDecimal("40"))),
                        ),
                    ),
                ),
            ) as CompiledStopLoss.Static

        val spec = compiled.spec as StopLossSpec.SteppedStop
        assertThat(spec.initialDistance).isEqualByComparingTo("50")
        assertThat(spec.steps.map { it.mfeThreshold }).containsExactly(BigDecimal("30"), BigDecimal("70"))
        assertThat(spec.steps.map { it.profitDistance }).containsExactly(BigDecimal.ZERO, BigDecimal("40"))
    }

    @Test
    fun `time tightening compiles to a static stop spec`() {
        val compiled =
            resolver.compileStopLoss(
                ChildBy(
                    NumLit(BigDecimal("60")),
                    TimeTightenAst(
                        NumLit(BigDecimal("10")),
                        DurationAst(900_000L),
                        NumLit(BigDecimal("20")),
                    ),
                ),
            ) as CompiledStopLoss.Static

        val spec = compiled.spec as StopLossSpec.TimeTighten
        assertThat(spec.initialDistance).isEqualByComparingTo("60")
        assertThat(spec.tightenBy).isEqualByComparingTo("10")
        assertThat(spec.intervalMs).isEqualTo(900_000L)
        assertThat(spec.floorDistance).isEqualByComparingTo("20")
    }

    @Test
    fun `ratchet operands must be compile-time constants`() {
        val child =
            ChildBy(
                NumLit(BigDecimal("50")),
                SteppedStopAst(
                    listOf(
                        StopStepAst(StreamFieldRef("x", "close"), NumLit(BigDecimal.ZERO)),
                    ),
                ),
            )

        assertThatThrownBy { resolver.compileStopLoss(child) }
            .hasMessageContainingAll("MFE threshold", "numeric literal")
    }

    @Test
    fun `stepped thresholds must be strictly increasing`() {
        val child =
            ChildBy(
                NumLit(BigDecimal("50")),
                SteppedStopAst(
                    listOf(
                        StopStepAst(NumLit(BigDecimal("70")), NumLit(BigDecimal.ZERO)),
                        StopStepAst(NumLit(BigDecimal("30")), NumLit(BigDecimal("10"))),
                    ),
                ),
            )

        assertThatThrownBy { resolver.compileStopLoss(child) }
            .hasMessageContaining("strictly increasing")
    }
}
