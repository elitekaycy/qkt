package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Live broker routing for a strategy whose symbol set subscribes members while it runs. */
class SessionBrokersMovingSetRoutesTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = null

            override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
        }
    private val account: BrokerFactory = { bus, clock, tracker, _, _ -> PaperBroker(bus, clock, tracker) }

    @Test
    fun `every symbol of a moving set's venue routes to its account, members it adds later included`() {
        val host = DynamicSetFixtures.plan(minutes = 1).strategy(emptyMap(), live = true) as DynamicSetHost
        val broker =
            SessionBrokers(listOf("rotation" to host), emptyList(), mapOf("backtest" to account), null)
                .buildBroker(PaperBroker(bus, clock, tracker), bus, clock, tracker, positions)

        assertThat(host.declaredStreams.values.map { it.qktSymbol }).containsExactly("BACKTEST:A")
        assertThat(broker.supports("BACKTEST:A")).isTrue()
        assertThat(broker.supports("BACKTEST:C")).isTrue()
        assertThat(broker.supports("OTHER:C")).isFalse()
    }
}
