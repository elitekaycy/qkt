package com.qkt.app

import com.qkt.broker.Broker
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
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/** A shared netting account's position is several strategies' net: startup reconcile trusts each book. */
class LiveSessionAccountWideReconcileTest {
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

    private object EmptySource : MarketSource {
        override val name: String = "Empty"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {}
            }
    }

    private fun session(
        accountWide: Boolean,
        persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
        options: com.qkt.derivatives.options.chain.OptionChainFixture? = null,
    ): LiveSession {
        val streams =
            mapOf("x" to HubKey(broker = "DERIBIT", symbol = "BTC_X", timeframe = "5m")) +
                listOfNotNull(options?.let { "chain" to HubKey("OPTIONS", "DERIBIT.BTC_USDC", "5m") })
        val factory: BrokerFactory = { bus, clock, priceTracker, _, _ ->
            object : Broker by PaperBroker(bus, clock, priceTracker) {
                override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> =
                    mapOf(
                        "DERIBIT:BTC_X" to
                            listOf(com.qkt.positions.Position("DERIBIT:BTC_X", BigDecimal("0.3"), BigDecimal("600"))),
                    )

                override fun isAccountWide(symbol: String): Boolean = accountWide
            }
        }
        return LiveSession(
            strategies =
                listOf(
                    "alpha" to StubDslStrategy(streams),
                ),
            source = EmptySource,
            symbols = streams.values.map { it.qktSymbol },
            clock = FixedClock(time = 0L),
            brokerFactories = mapOf("deribit" to factory),
            persistor = persistor,
            instrumentRegistry = options?.registry,
        )
    }

    @Test
    fun `an account-wide position held by other strategies does not refuse this strategy's start`() {
        assertThat(catchThrowable { session(accountWide = false).start() }).isInstanceOf(ReconcileException::class.java)

        val handle = session(accountWide = true).start()

        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }

    @Test
    fun `an option leg of a fed root is restored from its persisted book on an account-wide venue`(
        @org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path,
    ) {
        val options =
            com.qkt.derivatives.options.chain
                .OptionChainFixture(dir.resolve("data"))
        val leg = options.symbol
        val persistor = com.qkt.persistence.FileStatePersistor(dir.resolve("state"))
        val book = com.qkt.positions.LegBook(leg)
        book.add(
            com.qkt.positions.PositionLeg(
                "L1",
                leg,
                com.qkt.common.Side.SELL,
                BigDecimal("0.1"),
                BigDecimal("640"),
                0L,
                com.qkt.positions.LegRole.INDEPENDENT,
            ),
        )
        persistor.saveLegBook("alpha", leg, book)
        assertThat(persistor.legBookSymbols("alpha")).containsExactly(leg)

        val handle = session(accountWide = true, persistor = persistor, options = options).start()

        assertThat(handle.positionsFor("alpha").single { it.symbol == leg }.quantity).isEqualByComparingTo("-0.1")
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }
}
