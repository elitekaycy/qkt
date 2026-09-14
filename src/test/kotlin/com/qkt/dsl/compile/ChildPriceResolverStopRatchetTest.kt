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

    private fun ec(close: String) =
        EvalContext(
            candle =
                com.qkt.marketdata.Candle(
                    "BACKTEST:X",
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal(close),
                    BigDecimal.ZERO,
                    0L,
                    60_000L,
                ),
            streams = mapOf("x" to HubKey("BACKTEST", "X", "1m")),
            lets = emptyMap(),
            strategyContext = com.qkt.strategy.testStrategyContext(),
        )

    @Test
    fun `stepped stop operands may be expressions, fixed when the order is built (#1116)`() {
        val half =
            com.qkt.dsl.ast.BinaryOp(
                com.qkt.dsl.ast.BinOp.DIV,
                StreamFieldRef("x", "close"),
                NumLit(BigDecimal("2")),
            )
        val child =
            ChildBy(
                StreamFieldRef("x", "close"),
                SteppedStopAst(listOf(StopStepAst(half, NumLit(BigDecimal.ZERO)))),
            )
        val compiled = resolver.compileStopLoss(child) as CompiledStopLoss.Dynamic
        val spec = compiled.evaluate(ec("40"), com.qkt.common.Side.BUY, BigDecimal("4300")) as StopLossSpec.SteppedStop
        assertThat(spec.initialDistance).isEqualByComparingTo("40")
        assertThat(spec.steps.single().mfeThreshold).isEqualByComparingTo("20")
        assertThat(spec.steps.single().profitDistance).isEqualByComparingTo("0")
    }

    @Test
    fun `time-tightening operands may be expressions and an invalid evaluation yields no stop`() {
        val child =
            ChildBy(
                StreamFieldRef("x", "close"),
                TimeTightenAst(NumLit(BigDecimal("10")), DurationAst(900_000L), NumLit(BigDecimal("20"))),
            )
        val compiled = resolver.compileStopLoss(child) as CompiledStopLoss.Dynamic
        val ok = compiled.evaluate(ec("60"), com.qkt.common.Side.SELL, BigDecimal("4300")) as StopLossSpec.TimeTighten
        assertThat(ok.initialDistance).isEqualByComparingTo("60")
        // An initial distance below the floor is rejected by the spec: skip, do not throw.
        assertThat(compiled.evaluate(ec("15"), com.qkt.common.Side.SELL, BigDecimal("4300"))).isNull()
    }

    @Test
    fun `all-literal ratchets still validate at compile time`() {
        val child =
            ChildBy(
                NumLit(BigDecimal("50")),
                SteppedStopAst(listOf(StopStepAst(NumLit(BigDecimal("-1")), NumLit(BigDecimal.ZERO)))),
            )
        assertThatThrownBy { resolver.compileStopLoss(child) }.isInstanceOf(IllegalArgumentException::class.java)
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
