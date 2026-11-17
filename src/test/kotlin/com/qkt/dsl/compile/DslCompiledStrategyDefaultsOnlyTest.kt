package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.OrderOutcome
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A DSL strategy that overrides nothing optional answers every optional hook with its neutral default. */
class DslCompiledStrategyDefaultsOnlyTest {
    private val strategy =
        object : DslCompiledStrategy {
            override val declaredStreams: Map<String, HubKey> = emptyMap()
            override val retentionByKey: Map<HubKey, Int> = emptyMap()
            override val pendingStacks = PendingStacks()

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    @Test
    fun `optional capabilities default to none`() {
        assertThat(strategy.adHoc).isNull()
        assertThat(strategy.orderIds).isNull()
        assertThat(strategy.exitHookReferences()).isEmpty()
        assertThat(strategy.multiPositionPerSymbolSymbols).isEmpty()
        assertThat(strategy.volumeRequiringSymbols).isEmpty()
        assertThat(strategy.markSymbols).isEmpty()
        assertThat(strategy.optionMarkSymbols).isEmpty()
        assertThat(strategy.usesBookSizing).isFalse()
        assertThat(strategy.quoteFieldStreams).isEmpty()
        assertThat(strategy.onOrderSubmitted(Signal.Suppressed("x", "r"), "id")).isNull()
        assertThat(strategy.onExitOrderUnfilled("id")).isNull()
    }

    @Test
    fun `optional notifications are no-ops`() {
        strategy.onOrderOutcome(OrderOutcome.Rejected("id", "x", 1L))
        strategy.resumeOrderIds(listOf("a"))
        strategy.observeCandleEvaluations { _, _, _, _ -> }
        strategy.observeRuleDecisions { }
        strategy.clearRuleEdges()
        strategy.onPositionStateChanged("x", true, 1L)
        strategy.onOrderRejected("id")
        strategy.onOrderFilled("id")
        strategy.onOrderTerminal("id")
        val one = BigDecimal.ONE
        strategy.onCandle(Candle("X", one, one, one, one, BigDecimal.ZERO, 0L, 1L), testStrategyContext()) { }
        assertThat(strategy.declaredStreams).isEmpty()
    }
}
