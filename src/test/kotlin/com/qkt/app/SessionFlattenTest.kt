package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionFlattenTest {
    private val clock = FixedClock(1_000L)
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private class Venue(
        override val supportsPositionTickets: Boolean,
        val read: () -> List<BrokerPositionTicket> = { emptyList() },
        val modes: Map<String, PositionAccountingMode> = emptyMap(),
    ) : Broker {
        override val name = "venue"
        override val capabilities = setOf(OrderTypeCapability.MARKET)

        override fun submit(request: OrderRequest) = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) {}

        override fun positionTickets(): List<BrokerPositionTicket> = read()

        override fun positionAccountingMode(symbol: String) = modes[symbol] ?: PositionAccountingMode.UNKNOWN
    }

    private class Flatten(
        clock: FixedClock,
        venue: Venue,
        strategies: List<Pair<String, Strategy>>,
        val attribution: TicketAttribution = TicketAttribution(),
    ) {
        val pipeline = PipelineFixtures.pipeline(clock, { venue })
        val closes = mutableListOf<OrderRequest>()
        val subject =
            SessionFlatten(
                strategies,
                clock,
                venue,
                attribution,
                pipeline,
                pipeline.strategyPositions,
                SequentialIdGenerator(),
                pipeline.bus,
            )

        init {
            pipeline.bus.subscribe<OrderEvent> { closes += it.request }
        }

        fun leg(
            id: String,
            symbol: String,
            ticket: String?,
        ) {
            val leg = PositionLeg(id, symbol, Side.BUY, BigDecimal.ONE, BigDecimal("2000"), 0L, LegRole.INDEPENDENT)
            pipeline.strategyPositions.addIndependentLeg("gold", leg.copy(brokerTicket = ticket))
        }
    }

    private fun ticket(
        id: String,
        comment: String? = null,
    ) = BrokerPositionTicket(
        id,
        "XAUUSD",
        Side.SELL,
        BigDecimal("0.5"),
        BigDecimal("2000"),
        null,
        null,
        null,
        null,
        comment,
    )

    @Test
    fun `on a ticketed venue owned tickets close through their leg or by ticket and the rest are left`() {
        val venue = Venue(true, { listOf(ticket("1"), ticket("2"), ticket("3"), ticket("4")) })
        val f = Flatten(clock, venue, listOf("gold" to idle))
        f.attribution.record("1", "gold")
        f.attribution.record("2", "gold")
        f.attribution.record("3", "silver")
        f.leg("leg-1", "XAUUSD", "1")

        assertThat(f.subject.flattenAndSweep()).isNull()

        val markets = f.closes.map { it as OrderRequest.Market }
        assertThat(markets.map { it.closesTicket }).containsExactly("1", "2")
        assertThat(markets[0].closesLegId).isEqualTo("leg-1")
        assertThat(markets[0].side).isEqualTo(Side.SELL)
        assertThat(markets[1].closesLegId).isNull()
        assertThat(markets[1].side).isEqualTo(Side.BUY)
        assertThat(markets[1].quantity).isEqualByComparingTo("0.5")
    }

    @Test
    fun `without tickets only netting legs are closed by an opposite order`() {
        val venue = Venue(false, modes = mapOf("EURUSD" to PositionAccountingMode.NETTING))
        val f = Flatten(clock, venue, listOf("gold" to idle))
        f.leg("net", "EURUSD", null)
        f.leg("hedged", "XAUUSD", null)

        assertThat(f.subject.flattenAndSweep()).isNull()

        assertThat(f.closes.map { (it as OrderRequest.Market).closesLegId }).containsExactly("net")
    }

    @Test
    fun `a session with no strategy has nothing to flatten`() {
        val f = Flatten(clock, Venue(true, { listOf(ticket("1")) }), emptyList())

        assertThat(f.subject.flattenAndSweep()).isNull()
        assertThat(f.closes).isEmpty()
    }

    @Test
    fun `an unreadable venue closes pinned ledger legs and reports the rest`() {
        val venue = Venue(true, { throw IllegalStateException("gateway busy", InterruptedException("stop")) })
        val f = Flatten(clock, venue, listOf("gold" to idle))
        f.leg("pinned", "XAUUSD", "77")
        f.leg("loose", "EURUSD", null)

        val gap = interruptingSleeps { f.subject.flattenAndSweep() }

        assertThat(gap)
            .contains("venue positions unreadable after 3 reads on venue")
            .contains("closed ledger tickets [77]")
            .contains("could not close legs without a ticket [loose (EURUSD)]")
        assertThat(f.closes.map { (it as OrderRequest.Market).closesTicket }).containsExactly("77")
        // The interrupts the reads swallowed are handed back to the caller's thread.
        assertThat(Thread.interrupted()).isTrue()
    }

    /** Runs [block] while a watcher cuts short every sleep it enters, so retry backoff costs no wall time. */
    private fun <T> interruptingSleeps(block: () -> T): T {
        val target = Thread.currentThread()
        val done = AtomicBoolean(false)
        val watcher =
            Thread {
                while (!done.get()) {
                    if (target.state == Thread.State.TIMED_WAITING) {
                        target.interrupt()
                        while (!done.get() && target.state == Thread.State.TIMED_WAITING) Thread.onSpinWait()
                    }
                    Thread.onSpinWait()
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            return block()
        } finally {
            done.set(true)
            watcher.join()
        }
    }
}
