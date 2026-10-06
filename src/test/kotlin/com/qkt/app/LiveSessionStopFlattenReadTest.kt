package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerFactory
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.positions.LegRole
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1357: `qkt stop --flatten` queues the flatten, then stops the session. The flatten reads the
 * venue's positions first; when that read is slow (a busy gateway) or fails, the flatten must still
 * close what the strategy holds rather than fault with nothing sent.
 */
class LiveSessionStopFlattenReadTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val tickets = CopyOnWriteArrayList<BrokerPositionTicket>()
    private val ticketCloses = CopyOnWriteArrayList<String>()

    /** Set once the stop flatten is requested: the venue reads below misbehave only from then on. */
    private val flattening = AtomicBoolean(false)

    private val source = // one tick, then the feed blocks until the session interrupts it
        object : MarketSource {
            override val name: String = "blocking"
            override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String): Boolean = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private var emitted = false

                    override fun next(): Tick? {
                        if (!emitted) {
                            emitted = true
                            return Tick("EXNESS:X", Money.of("100"), now.toEpochMilli())
                        }
                        return runCatching { Thread.sleep(Long.MAX_VALUE) }.let { null }
                    }

                    override fun close() = Unit
                }
        }

    private val strategy =
        object : DslCompiledStrategy {
            private var fired = false
            override val declaredStreams: Map<String, HubKey> = mapOf("x" to HubKey("EXNESS", "X", "1m"))
            override val retentionByKey: Map<HubKey, Int> = emptyMap()
            override val pendingStacks: PendingStacks = PendingStacks()

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (fired) return
                fired = true
                emit(
                    Signal.Submit(
                        OrderRequest.Market(
                            id = "ORD-test-0",
                            symbol = tick.symbol,
                            side = Side.BUY,
                            quantity = Money.of("1"),
                            timeInForce = TimeInForce.GTC,
                            timestamp = tick.timestamp,
                            legIntent = LegIntent.Open("ORD-test-0", LegRole.INDEPENDENT),
                        ),
                    ),
                )
            }
        }

    /** A hedging venue whose position list is read through [read]. */
    private fun venue(read: () -> List<BrokerPositionTicket>): BrokerFactory =
        { bus, clock, prices, _, _ ->
            val delegate = PaperBroker(bus, clock, prices)
            bus.subscribe<BrokerEvent.OrderFilled> { fill ->
                if (fill.side != Side.BUY) return@subscribe
                tickets.add(
                    BrokerPositionTicket(
                        ticket = requireNotNull(fill.brokerOrderId),
                        symbol = fill.symbol,
                        side = fill.side,
                        qty = fill.quantity,
                        entryPrice = fill.price,
                        currentPrice = fill.price,
                        profit = BigDecimal.ZERO,
                        swap = BigDecimal.ZERO,
                        openedAt = fill.timestamp,
                        comment = fill.clientOrderId,
                    ),
                )
            }
            object : Broker by delegate {
                override val supportsPositionTickets: Boolean = true

                override fun positionAccountingMode(symbol: String) = PositionAccountingMode.HEDGING

                override fun positionTickets(): List<BrokerPositionTicket> = read()

                override fun submit(request: OrderRequest): SubmitAck {
                    val closeTicket = (request as? OrderRequest.Market)?.closesTicket
                    if (closeTicket != null) {
                        ticketCloses.add(closeTicket)
                        tickets.removeIf { it.ticket == closeTicket }
                        return SubmitAck(request.id, closeTicket, accepted = true)
                    }
                    return delegate.submit(request)
                }
            }
        }

    private fun openThenStopWithFlatten(factory: BrokerFactory) {
        val handle =
            LiveSession(
                strategies = listOf("test" to strategy),
                source = source,
                symbols = listOf("EXNESS:X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("exness" to factory),
            ).start()
        val openDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (tickets.isEmpty() && System.nanoTime() < openDeadline) Thread.sleep(10)
        assertThat(tickets).hasSize(1)

        // What the daemon's stop route does for `qkt stop --flatten`.
        flattening.set(true)
        handle.flattenForStop()
        handle.stop()
        handle.awaitTermination(Duration.ofSeconds(10))
    }

    @Test
    fun `a stop flatten whose venue read outlasts the drain grace still closes the position`() {
        val slowed = AtomicBoolean(false)
        openThenStopWithFlatten(
            venue {
                if (flattening.get() && slowed.compareAndSet(false, true)) {
                    try {
                        // A gateway answering slowly under load: longer than the stop's drain grace.
                        Thread.sleep(3_500)
                    } catch (e: InterruptedException) {
                        throw IllegalStateException("positionTickets: leaf exness failed: ${e.message}", e)
                    }
                }
                tickets.toList()
            },
        )

        assertThat(ticketCloses).containsExactly("ORD-test-0")
    }

    @Test
    fun `a stop flatten whose venue read was interrupted reads again and closes`() {
        val interruptedOnce = AtomicBoolean(false)
        openThenStopWithFlatten(
            venue {
                if (flattening.get() && interruptedOnce.compareAndSet(false, true)) {
                    // As live: the MT5 read's retry sleep threw, and the composite wrapped it.
                    throw IllegalStateException(
                        "CompositeBroker.positionTickets: leaf exness failed: sleep interrupted",
                        InterruptedException("sleep interrupted"),
                    )
                }
                tickets.toList()
            },
        )

        assertThat(ticketCloses).containsExactly("ORD-test-0")
    }

    @Test
    fun `a stop flatten that cannot read the venue closes the ledger's tickets`() {
        openThenStopWithFlatten(
            venue {
                check(!flattening.get()) { "MT5Broker exness positionTickets: gateway read failed" }
                tickets.toList()
            },
        )

        assertThat(ticketCloses).containsExactly("ORD-test-0")
    }
}
