package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * #139 PR B — deploy-time validation that every broker prefix declared by a DSL strategy
 * has a configured factory. Without this check, the old code path silently fell through
 * to `paperBroker` for unmapped prefixes, so the strategy filled on paper instead of the
 * intended venue and operators only noticed when real fills didn't appear.
 */
class LiveSessionBrokerCoverageTest {
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

    /** Broker factory that should not be called — the validation must fire before factory invocation. */
    private val unusedFactory: BrokerFactory = {
        _: EventBus,
        _: Clock,
        _: MarketPriceTracker,
        _: PositionProvider,
        _: String?,
        ->
        object : Broker {
            override val name: String = "unused"
            override val capabilities: Set<OrderTypeCapability> = emptySet()

            override fun supports(symbol: String): Boolean = false

            override fun submit(request: OrderRequest) = error("factory was invoked despite missing prefix validation")

            override fun cancel(orderId: String) = error("not used")

            override fun modify(
                orderId: String,
                changes: OrderModification,
            ) = error("not used")
        }
    }

    @Test
    fun `start fails fast when a declared broker prefix has no configured factory`() {
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS_LIVE", symbol = "XAUUSD", timeframe = "5m")),
            )
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS_LIVE:XAUUSD"),
                clock = FixedClock(time = 0L),
                // Only `exness_demo` configured — the strategy's `EXNESS_LIVE` prefix has no factory.
                brokerFactories = mapOf("exness_demo" to unusedFactory),
            )

        val ex = catchThrowable { session.start() }
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex.message).contains("exness_live")
        assertThat(ex.message).contains("exness_demo")
    }

    @Test
    fun `start refuses when broker positions cannot be read at reconcile`() {
        // A transient venue error must not read as "flat" — the session retries and,
        // without one clean read, refuses to start instead of trading on assumed state.
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")),
            )
        var reads = 0
        val failingFactory: BrokerFactory = { bus, clock, priceTracker, _, _ ->
            object : Broker by PaperBroker(bus, clock, priceTracker) {
                override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
                    reads++
                    error("gateway read failed")
                }
            }
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("exness" to failingFactory),
                reconcileReadBackoffMs = 1L,
            )

        val ex = catchThrowable { session.start() }
        assertThat(ex).isInstanceOf(ReconcileException::class.java)
        assertThat(ex.message).contains("refusing to start")
        assertThat(reads).isEqualTo(5)
    }

    @Test
    fun `reconcile read succeeds after transient failures and the session starts`() {
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")),
            )
        var reads = 0
        val flakyFactory: BrokerFactory = { bus, clock, priceTracker, _, _ ->
            object : Broker by PaperBroker(bus, clock, priceTracker) {
                override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
                    reads++
                    if (reads < 3) error("gateway read failed")
                    return emptyMap()
                }
            }
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("exness" to flakyFactory),
                reconcileReadBackoffMs = 1L,
            )

        val handle = session.start()
        assertThat(reads).isEqualTo(3)
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }

    @Test
    fun `start does not throw when every declared prefix has a configured factory`() {
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")),
            )
        val paperFactory: BrokerFactory = { bus, clock, priceTracker, _, _ -> PaperBroker(bus, clock, priceTracker) }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("exness" to paperFactory),
            )

        // Validation should not throw — happy path.
        val handle = session.start()
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }

    @Test
    fun `factory receives the session PositionProvider (Bybit linear wiring, G1)`() {
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("btc" to HubKey(broker = "BYBIT_LINEAR", symbol = "BTCUSDT", timeframe = "5m")),
            )
        var captured: PositionProvider? = null
        val factory: BrokerFactory = { bus, clock, priceTracker, positions, _ ->
            captured = positions
            PaperBroker(bus, clock, priceTracker)
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("BYBIT_LINEAR:BTCUSDT"),
                clock = FixedClock(time = 0L),
                brokerFactories = mapOf("bybit_linear" to factory),
            )

        val handle = session.start()
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
        // A BYBIT_LINEAR: prefix resolves to the bybit_linear factory, which is handed the
        // engine's PositionProvider — what BybitLinearBroker needs for position reconcile.
        assertThat(captured).isNotNull()
    }

    @Test
    fun `start does not throw when no brokers are configured at all (paper-only)`() {
        // Empty brokerFactories means "paper for everything" — pre-existing behavior must
        // stay intact and the validation must NOT fire on prefixes that have no factory map.
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("btc" to HubKey(broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
            )
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("BACKTEST:BTCUSDT"),
                clock = FixedClock(time = 0L),
                brokerFactories = emptyMap(),
            )

        val handle = session.start()
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))
    }

    @Test
    fun `restart re-adopts a persisted straddle of INDEPENDENT legs into the tracker (#432)`() {
        // A hedge_straddle holds two INDEPENDENT legs (a filled long and a filled short), no
        // PRIMARY. On restart the reconciler matches both to the broker's open positions and
        // returns Attached, but the old code only re-loaded a leg when its role was PRIMARY —
        // so an all-INDEPENDENT book was dropped, POSITION.<stream> read 0, and the dsl bracket
        // plus winner-timeout were dead. The fix re-loads the whole book regardless of role.
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")),
            )
        // Seed the on-disk book the prior session would have persisted: two INDEPENDENT legs of
        // unequal size, so the net view is non-flat (long 0.01) and adoption is observable.
        val persistor = NoopStatePersistor()
        val book = LegBook("EXNESS:XAUUSD")
        book.add(
            PositionLeg(
                legId = "alpha-EXNESS:XAUUSD-long",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.25"),
                entryPrice = BigDecimal("4136"),
                openedAt = 1_700_000_000_000L,
                role = LegRole.INDEPENDENT,
                brokerTicket = "111",
            ),
        )
        book.add(
            PositionLeg(
                legId = "alpha-EXNESS:XAUUSD-short",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.24"),
                entryPrice = BigDecimal("4165"),
                openedAt = 1_700_000_000_000L,
                role = LegRole.INDEPENDENT,
                brokerTicket = "222",
            ),
        )
        persistor.saveLegBook("alpha", "EXNESS:XAUUSD", book)
        // The venue reports both legs as separate positions (a hedging account is not netted),
        // each matching a persisted leg by side, quantity, and price → reconcile returns Attached.
        val factory: BrokerFactory = { bus, clock, priceTracker, _, _ ->
            object : Broker by PaperBroker(bus, clock, priceTracker) {
                override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> =
                    mapOf(
                        "EXNESS:XAUUSD" to
                            listOf(
                                com.qkt.positions.Position("EXNESS:XAUUSD", BigDecimal("0.25"), BigDecimal("4136")),
                                com.qkt.positions.Position("EXNESS:XAUUSD", BigDecimal("-0.24"), BigDecimal("4165")),
                            ),
                    )
            }
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(time = 1_700_000_100_000L),
                brokerFactories = mapOf("exness" to factory),
                persistor = persistor,
            )

        val handle = session.start()
        val summary = handle.dailySummaryRows().single().positionsSummary
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))

        // Adopted: the strategy tracker holds the legs, so the net view is long 0.01 — not flat.
        assertThat(summary).isNotEqualTo("flat")
        assertThat(summary).contains("long 0.01").contains("EXNESS:XAUUSD")
    }

    @Test
    fun `ignore-mismatches adopts unmatched broker positions as INDEPENDENT legs with venue tickets (#437)`() {
        // The persistor holds no legs for this strategy/symbol, but the venue reports two open
        // gold positions → reconcile returns Mismatch. With ignore-mismatches the session adopts
        // them rather than refusing to start. The adopted legs must be INDEPENDENT and carry their
        // venue tickets, so a later CLOSE flattens them per-leg by ticket instead of via a net
        // opposite order — which on a hedging account would open a counter position (#437).
        val strategy =
            StubDslStrategy(
                declaredStreams =
                    mapOf("gold" to HubKey(broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")),
            )
        val persistor = NoopStatePersistor()
        val factory: BrokerFactory = { bus, clock, priceTracker, _, _ ->
            object : Broker by PaperBroker(bus, clock, priceTracker) {
                // getOpenPositions() is ticketless and drives the mismatch detection.
                override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> =
                    mapOf(
                        "EXNESS:XAUUSD" to
                            listOf(
                                com.qkt.positions.Position("EXNESS:XAUUSD", BigDecimal("0.13"), BigDecimal("4140")),
                                com.qkt.positions.Position("EXNESS:XAUUSD", BigDecimal("-0.13"), BigDecimal("4150")),
                            ),
                    )

                // positionTickets() carries the venue tickets used for the attach.
                override fun positionTickets(): List<com.qkt.broker.BrokerPositionTicket> =
                    listOf(
                        com.qkt.broker.BrokerPositionTicket(
                            ticket = "9001",
                            symbol = "EXNESS:XAUUSD",
                            side = Side.BUY,
                            qty = BigDecimal("0.13"),
                            entryPrice = BigDecimal("4140"),
                            currentPrice = null,
                            profit = null,
                            swap = null,
                            openedAt = 1_700_000_000_000L,
                            comment = "",
                        ),
                        com.qkt.broker.BrokerPositionTicket(
                            ticket = "9002",
                            symbol = "EXNESS:XAUUSD",
                            side = Side.SELL,
                            qty = BigDecimal("0.13"),
                            entryPrice = BigDecimal("4150"),
                            currentPrice = null,
                            profit = null,
                            swap = null,
                            openedAt = 1_700_000_000_000L,
                            comment = "",
                        ),
                    )
            }
        }
        val session =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = EmptySource,
                symbols = listOf("EXNESS:XAUUSD"),
                clock = FixedClock(time = 1_700_000_100_000L),
                brokerFactories = mapOf("exness" to factory),
                persistor = persistor,
                ignoreMismatches = true,
            )

        val handle = session.start()
        handle.stop()
        handle.awaitTermination(java.time.Duration.ofSeconds(2))

        val persisted = persistor.loadLegBook("alpha", "EXNESS:XAUUSD")
        assertThat(persisted).isNotNull
        assertThat(persisted!!.legs).hasSize(2)
        // No STACK legs, no synthetic parent — every adopted leg is INDEPENDENT.
        assertThat(persisted.legs).allMatch { it.role == LegRole.INDEPENDENT }
        assertThat(persisted.legs.none { it.role == LegRole.STACK }).isTrue
        // Each adopted leg carries its real venue ticket, so CLOSE can target it by ticket.
        assertThat(persisted.legs.map { it.brokerTicket }).containsExactlyInAnyOrder("9001", "9002")
    }
}
