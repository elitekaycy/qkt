package com.qkt.dsl.compile

import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** SEQUENCE accessors compiled by [SequenceAccessorCompiler]. */
class SequenceAccessorCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    @Test
    fun `SEQUENCE accessors read sequence runtime view`() {
        val sequences =
            object : SequenceStateView {
                override fun stage(sequence: String): Int = if (sequence == "sweep") 2 else 0

                override fun complete(sequence: String): Boolean = sequence == "sweep"

                override fun stagePrice(
                    sequence: String,
                    stage: String,
                ): BigDecimal? = if (sequence == "sweep" && stage == "swept") BigDecimal("98.50") else null

                override fun stageTime(
                    sequence: String,
                    stage: String,
                ): Long? = if (sequence == "sweep" && stage == "swept") 1_000L else null
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
                sequences = sequences,
            )

        fun num(accessor: SequenceAccessor) = (ExprCompiler().compile(accessor).evaluate(ec) as Value.Num).v

        assertThat(num(SequenceAccessor("sweep", null, "stage"))).isEqualByComparingTo("2")
        assertThat((ExprCompiler().compile(SequenceAccessor("sweep", null, "complete")).evaluate(ec) as Value.Bool).v)
            .isTrue
        assertThat(num(SequenceAccessor("sweep", "swept", "price"))).isEqualByComparingTo("98.50")
        assertThat(num(SequenceAccessor("sweep", "swept", "time"))).isEqualByComparingTo("1000")
    }
}
