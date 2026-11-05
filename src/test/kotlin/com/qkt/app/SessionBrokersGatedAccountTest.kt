package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.GatedBrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A strategy trading an account the daemon has not verified yet builds no broker at all (#1642). */
class SessionBrokersGatedAccountTest {
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
    private val built = mutableListOf<String>()

    private fun factory(name: String): BrokerFactory =
        { bus, clock, tracker, _, _ ->
            built += name
            PaperBroker(bus, clock, tracker)
        }

    private var refusal: String? = "account alpaca is not verified (unreachable since boot): gw-alpaca"
    private val factories =
        mapOf("exness" to factory("exness"), "alpaca" to GatedBrokerFactory(factory("alpaca")) { refusal })
    private val streams =
        mapOf("gold" to HubKey("EXNESS", "EXNESS:XAUUSD", "1m"), "aapl" to HubKey("ALPACA", "ALPACA:AAPL", "1m"))

    private fun build() =
        SessionBrokers(listOf("s" to Streams(streams)), emptyList(), factories, null)
            .buildBroker(PaperBroker(bus, clock, tracker), bus, clock, tracker, positions)

    @Test
    fun `an unverified account refuses the session before any of its brokers is built`() {
        assertThatThrownBy { build() }.hasMessageContaining("account alpaca is not verified")

        assertThat(built).isEmpty()
    }

    @Test
    fun `once the account verifies the same strategy builds both brokers`() {
        refusal = null

        build()

        assertThat(built).containsExactlyInAnyOrder("exness", "alpaca")
    }
}
