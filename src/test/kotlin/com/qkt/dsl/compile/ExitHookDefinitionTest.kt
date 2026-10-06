package com.qkt.dsl.compile

import com.qkt.execution.ExitReason
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExitHookDefinitionTest {
    private val ran = mutableListOf<String>()

    private fun hook(name: String): (EvalContext) -> List<Signal> =
        {
            ran += name
            emptyList()
        }

    private val definition =
        CompiledExitHookDefinition(
            ExitHookRef("d", "f"),
            listOf(hook("stop")),
            listOf(hook("tp")),
            listOf(hook("close")),
        )
    private val context =
        EvalContext(
            candle =
                Candle(
                    "X",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `a liquidation runs the ON_CLOSE actions, as an expiry and a failed roll do`() {
        for (reason in listOf(ExitReason.LIQUIDATION, ExitReason.EXPIRY, ExitReason.ROLL_FAILED)) {
            definition.execute(reason, context)
        }

        assertThat(ran).containsExactly("close", "close", "close")
    }
}
