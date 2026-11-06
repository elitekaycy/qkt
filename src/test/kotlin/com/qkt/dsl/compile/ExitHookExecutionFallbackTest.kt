package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An exit hook prices off the exit fill before any bar closed, and refuses stale or unbound runs. */
class ExitHookExecutionFallbackTest {
    private val strategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    """
                    STRATEGY hook VERSION 1
                    SYMBOLS gold = TEST:XAUUSD EVERY 1m
                    RULES
                      WHEN gold.close > 100 THEN BUY gold SIZING 1
                        ON_STOP { SELL gold SIZING 1 ORDER_TYPE = LIMIT AT gold.close + 1 }
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value,
        ) as DslCompiledStrategy

    private val ref = strategy.exitHookReferences().values.single()
    private val exit = ExitContext(BigDecimal("120"), Side.SELL, BigDecimal.ONE, BigDecimal("-5"), ExitReason.STOP)

    @Test
    fun `before any bar closed the hook prices off the exit fill`() {
        val hub = CandleHub()
        hub.register(strategy.declaredStreams.getValue("gold"), 2, "s")
        strategy.bindToHub(hub, testStrategyContext()) {}

        val child = strategy.executeExitHook(ref, exit, timestampMs = 5_000L).single() as Signal.Submit

        assertThat((child.request as OrderRequest.Limit).limitPrice).isEqualByComparingTo("121")
    }

    @Test
    fun `a hook that is not bound to a hub refuses to run`() {
        assertThatThrownBy { strategy.executeExitHook(ref, exit, 0L) }
            .hasMessageContaining("CompiledStrategy must be bound before an exit hook can execute")
    }

    @Test
    fun `a stale persisted hook reference refuses to run`() {
        assertThatThrownBy { strategy.executeExitHook(ref.copy(fingerprint = "changed"), exit, 0L) }
            .hasMessageContaining("Exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed")
    }
}
