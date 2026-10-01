package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.broker.continuous.ContinuousContractBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Live broker routing for a strategy trading a continuous futures stream beside a listed contract. */
class SessionBrokersContinuousRoutesTest {
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

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val clock = FixedClock(ms("2024-09-20T00:00:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val engine =
        object : PositionProvider {
            override fun positionFor(symbol: String) = null

            override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
        }
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                            ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                        ),
                    ),
            ),
            mapOf(
                root.root to
                    RollHistory(
                        root.root,
                        "8d@08:00",
                        listOf(
                            RollRecord(
                                ms("2024-09-19T08:00:00Z"),
                                "BTCUSDT_240927",
                                "BTCUSDT_241227",
                                "63000",
                                "63800",
                            ),
                        ),
                    ),
            ),
        )

    private fun routing(bound: MutableList<EventBus>) =
        object : ContinuousRouting {
            override val chains = ContinuousChains(registry.futures())

            override fun bindLane(bus: EventBus) {
                bound += bus
            }
        }

    private class Attached(
        val bus: EventBus,
        val positions: PositionProvider,
        val broker: Broker,
    )

    @Test
    fun `a continuous stream trades through lanes on the account, each on a bound bus with the lane's positions`() {
        val attached = mutableListOf<Attached>()
        val account: BrokerFactory = { b, c, t, positions, _ ->
            PaperBroker(b, c, t).also {
                attached +=
                    Attached(b, positions, it)
            }
        }
        val bound = mutableListOf<EventBus>()
        val streams =
            mapOf(
                "perp" to HubKey("BINANCE_UM", "BTCUSDT@front", "1m"),
                "dec" to HubKey("BINANCE_UM", "BTCUSDT_241227", "1m"),
            )
        val sessionBrokers =
            SessionBrokers(listOf("s" to Streams(streams)), emptyList(), mapOf("binance_um" to account), registry)

        val broker =
            sessionBrokers.buildBroker(
                PaperBroker(bus, clock, tracker),
                bus,
                clock,
                tracker,
                engine,
                routing(bound),
            )

        assertThat(attached).hasSize(2)
        val (main, lane) = attached
        assertThat(main.bus).isSameAs(bus)
        assertThat(main.positions).isSameAs(engine)
        assertThat(bound).containsExactly(lane.bus)
        assertThat(lane.positions).isNotSameAs(engine)
        assertThat(broker.supports("BINANCE_UM:BTCUSDT@front")).isTrue()
        assertThat(broker.supports("BINANCE_UM:BTCUSDT_241227")).isTrue()
        assertThat(sessionBrokers.built.filterIsInstance<ContinuousContractBroker>()).hasSize(1)
    }

    @Test
    fun `a session with no continuous streams builds exactly what it built before`() {
        val made = mutableListOf<EventBus>()
        val account: BrokerFactory = { b, c, t, _, _ -> PaperBroker(b, c, t).also { made += b } }
        val streams = mapOf("dec" to HubKey("BINANCE_UM", "BTCUSDT_241227", "1m"))
        val bound = mutableListOf<EventBus>()

        SessionBrokers(listOf("s" to Streams(streams)), emptyList(), mapOf("binance_um" to account), registry)
            .buildBroker(
                PaperBroker(bus, clock, tracker),
                bus,
                clock,
                tracker,
                engine,
                routing(bound),
            )

        assertThat(made).containsExactly(bus)
        assertThat(bound).isEmpty()
    }
}
