package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A hand-written [DslCompiledStrategy] gets inert defaults for every optional runtime hook, and the
 * no-sequence view reports nothing started.
 */
class DslCompiledStrategyDefaultsTest {
    private val strategy =
        object : DslCompiledStrategy {
            override val declaredStreams: Map<String, HubKey> = emptyMap()
            override val retentionByKey: Map<HubKey, Int> = emptyMap()
            override val pendingStacks = PendingStacks()

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    @Test
    fun `optional hooks default to no ad-hoc actions, no exit hooks and no quote-field streams`() {
        val exit = ExitContext(BigDecimal.TEN, Side.SELL, BigDecimal.ONE, BigDecimal.ZERO, ExitReason.STOP)

        assertThat(strategy.adHoc).isNull()
        assertThat(strategy.exitHookReferences()).isEmpty()
        assertThat(strategy.executeExitHook(ExitHookRef("d", "f"), exit, 0L)).isEmpty()
        assertThat(strategy.quoteFieldStreams).isEmpty()
    }

    @Test
    fun `the no-sequence view reports every sequence unstarted`() {
        assertThat(NoopSequenceStateView.stage("any")).isZero
        assertThat(NoopSequenceStateView.complete("any")).isFalse
        assertThat(NoopSequenceStateView.stagePrice("any", "s")).isNull()
        assertThat(NoopSequenceStateView.stageTime("any", "s")).isNull()
    }
}
