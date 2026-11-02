package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.common.FixedClock
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import org.junit.jupiter.api.Test

/** A live session must accept read-only hub data streams without asking for a broker to route them to. */
class LiveSessionHubStreamTest {
    private class StubDslStrategy(
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

    private object EmptyFeed : TickFeed {
        override fun next(): Tick? = null

        override fun close() {}
    }

    private object EmptySource : MarketSource {
        override val name: String = "Empty"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = EmptyFeed
    }

    @Test
    fun `a hub data stream needs no broker factory and the session starts`() {
        // A HUB: stream is read-only data from a qkt-data-hub store, like a CHAIN: stream; the
        // prefix check must not demand a broker for it, or no strategy reading hub facts can run live.
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf(
                        "gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m"),
                        "cal/at" to HubKey(broker = "HUB", symbol = "cal.us_high.USD/at", timeframe = "1d"),
                    ),
            )
        val paperFactory: BrokerFactory = { bus, clock, priceTracker, _, _ -> PaperBroker(bus, clock, priceTracker) }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD", "HUB:cal.us_high.USD/at"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("exness" to paperFactory),
            )

        val handle = session.start()
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }
}
