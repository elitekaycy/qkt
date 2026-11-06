package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [SequenceRuntime] routes bars by alias and symbol; non-boolean stages are false, unknown names unstarted. */
class SequenceRuntimeRoutingTest {
    private var first: Value = Value.of(true)

    private val runtime =
        SequenceRuntime(
            listOf(
                CompiledSequence(
                    name = "sweep",
                    streamAlias = "gold",
                    streamSymbol = "BACKTEST:XAUUSD",
                    stages =
                        listOf(
                            CompiledSequenceStage("a", null, CompiledExpr { first }),
                            CompiledSequenceStage("b", null, CompiledExpr { Value.of(false) }),
                        ),
                    referencedAliases = setOf("gold"),
                ),
            ),
        )

    private fun feed(
        symbol: String,
        alias: String? = null,
    ) {
        val one = BigDecimal.ONE
        val candle = Candle(symbol, one, one, one, one, BigDecimal.ZERO, 0L, 1L)
        runtime.onCandle(
            candle,
            EvalContext(candle, emptyMap(), emptyMap(), testStrategyContext()),
            streamAlias = alias,
        )
    }

    @Test
    fun `bars for another alias or symbol do not advance the sequence`() {
        feed("BACKTEST:XAUUSD", alias = "silver")
        feed("BACKTEST:XAGUSD")

        assertThat(runtime.stage("sweep")).isZero

        feed("BACKTEST:XAUUSD", alias = "gold")
        assertThat(runtime.stage("sweep")).isEqualTo(1)
    }

    @Test
    fun `a stage condition that is not boolean counts as false`() {
        first = Value.Num(BigDecimal.ONE)

        feed("BACKTEST:XAUUSD")

        assertThat(runtime.stage("sweep")).isZero
    }

    @Test
    fun `an unknown sequence reads as unstarted`() {
        assertThat(runtime.complete("nope")).isFalse
        assertThat(runtime.stagePrice("nope", "a")).isNull()
        assertThat(runtime.stageTime("sweep", "zz")).isNull()
    }
}
