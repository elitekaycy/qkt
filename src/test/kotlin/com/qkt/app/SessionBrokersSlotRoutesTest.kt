package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.instrument.SlotFamilyFixture.symbol
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Live broker routing for a strategy trading an event slot: the slot's account takes every contract of its family. */
class SessionBrokersSlotRoutesTest {
    private class Streams(
        override val declaredStreams: Map<String, HubKey>,
    ) : DslCompiledStrategy {
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks: PendingStacks = PendingStacks()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}
    }

    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = null

            override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
        }
    private val kalshi: BrokerFactory = { bus, clock, tracker, _, _ -> PaperBroker(bus, clock, tracker) }

    private fun route(streams: Map<String, HubKey>) =
        SessionBrokers(
            listOf("s" to Streams(streams)),
            emptyList(),
            mapOf("kalshi" to kalshi),
            SlotFamilyFixture.registry(),
        ).buildBroker(PaperBroker(bus, clock, tracker), bus, clock, tracker, positions)

    @Test
    fun `a slot routes every contract of its family to its account, and nothing else`() {
        val broker = route(mapOf("p" to HubKey("KALSHI", "KXS@front.p70", "1m")))

        assertThat(broker.supports(symbol(1, 2))).isTrue()
        assertThat(broker.supports(symbol(3, 4))).isTrue()
        assertThat(broker.supports("KALSHI:KXO_H1_T1")).isFalse()
    }

    @Test
    fun `a session declaring a contract but no slot routes that contract alone`() {
        val broker = route(mapOf("k" to HubKey("KALSHI", "KXS_H1_T1", "1m")))

        assertThat(broker.supports(symbol(1, 1))).isTrue()
        assertThat(broker.supports(symbol(1, 2))).isFalse()
    }
}
