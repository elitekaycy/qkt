package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.InstrumentProvider
import com.qkt.broker.PaperBroker
import com.qkt.broker.ServerTimeZoneProvider
import com.qkt.broker.TicketAttributionProvider
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The live session gets contract specs, the broker server clock and recovered ticket owners by
 * asking each broker for the ability — never by checking which connector built it. A broker
 * from a connector qkt has never heard of works the same as MT5.
 */
class LiveSessionBrokerAbilitiesTest {
    private val meta =
        InstrumentMeta(
            qktSymbol = "FUTURES:MES",
            contractSize = BigDecimal("5"),
            volumeStep = BigDecimal.ONE,
            volumeMin = BigDecimal.ONE,
            volumeMax = null,
            pointSize = BigDecimal("0.25"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private class CapturingStrategy : DslCompiledStrategy {
        var context: StrategyContext? = null
        override val declaredStreams: Map<String, HubKey> =
            mapOf("mes" to HubKey(broker = "FUTURES", symbol = "MES", timeframe = "5m"))
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks: PendingStacks = PendingStacks()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            context = ctx
        }

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

    /** A broker from an unknown connector that offers every ability. */
    private inner class AbleBroker(
        paper: PaperBroker,
    ) : Broker by paper,
        InstrumentProvider,
        ServerTimeZoneProvider,
        TicketAttributionProvider {
        override fun instrumentRegistry(): InstrumentRegistry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { it.qktSymbol == qktSymbol }
            }

        override fun serverTimeZone(): ZoneId = ZoneId.of("America/Chicago")

        override fun ticketAttributions(): Map<String, String> = mapOf("T-1" to "alpha")
    }

    @Test
    fun `a session takes instruments and recovered owners from any broker offering them`() {
        val strategy = CapturingStrategy()
        val factory: BrokerFactory = { bus: EventBus, clock, prices: MarketPriceTracker, _, _ ->
            AbleBroker(PaperBroker(bus, clock, prices))
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("FUTURES:MES"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("futures" to factory),
            )

        val handle = session.start()
        try {
            val ctx = checkNotNull(strategy.context) { "strategy was never bound" }
            assertThat(ctx.instruments.lookup("FUTURES:MES")).isEqualTo(meta)
            assertThat(session.ticketAttribution.ownerOf("T-1")).isEqualTo("alpha")
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `the broker server clock comes from the first broker that has one`() {
        val clock = FixedClock(time = 0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val prices = MarketPriceTracker()
        val plain = PaperBroker(bus, clock, prices)
        val able = AbleBroker(PaperBroker(bus, clock, prices))

        assertThat(LiveSession.serverTimeZoneOf(listOf(plain, able))).isEqualTo(ZoneId.of("America/Chicago"))
        assertThat(LiveSession.serverTimeZoneOf(listOf(plain))).isNull()
    }
}
