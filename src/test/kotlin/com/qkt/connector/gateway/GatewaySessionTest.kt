package com.qkt.connector.gateway

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.Event
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Two strategies, each in its own session with its own bus as in the daemon, sharing one gateway account. */
class GatewaySessionTest {
    private val code = "BTC_USDC-25DEC26-92000-C"
    private val symbol = "DERIBIT:BTC_USDC_25DEC26_92000_C"
    private val fake = FakeGateway(listOf(code))
    private val clock = FixedClock(5L)
    private val brokers = mutableListOf<GatewayBroker>()

    @AfterEach
    fun stop() {
        brokers.forEach { it.shutdown() }
        fake.shutdown()
    }

    private fun session() =
        GatewaySession(
            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 3),
            GatewaySymbols("DERIBIT:", listOf(code)),
            clock,
            streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
        )

    private class Strategy(
        val bus: EventBus,
        val events: MutableList<Event>,
    )

    private fun strategy(): Strategy {
        val bus = EventBus(FixedClock(5L), MonotonicSequenceGenerator())
        val events = CopyOnWriteArrayList<Event>()
        bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events += it }
        bus.subscribe<BrokerEvent.OrderRejected> { events += it }
        bus.subscribe<ContractSettled> { events += it }
        return Strategy(bus, events)
    }

    private fun broker(
        session: GatewaySession,
        strategy: Strategy,
        id: String,
        held: String = "0",
    ) = GatewayBroker(session, strategy.bus, clock, holding(held), id).also { brokers += it }

    private fun holding(quantity: String) =
        object : PositionProvider {
            override fun positionFor(symbol: String) =
                Position(symbol, BigDecimal(quantity), BigDecimal.ONE).takeIf {
                    quantity !=
                        "0"
                }

            override fun allPositions() = emptyMap<String, Position>()
        }

    private fun market(
        id: String,
        strategy: String,
        side: Side = Side.BUY,
        quantity: String = "0.1",
    ) = OrderRequest.Market(id, symbol, side, BigDecimal(quantity), TimeInForce.GTC, 5L, strategy)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `each strategy's fills reach its own session only`() {
        val shared = session()
        val a = strategy()
        val b = strategy()
        val brokerA = broker(shared, a, "a")
        broker(shared, b, "b")

        brokerA.submit(market("a-1", "a"))
        await { a.events.any { it is BrokerEvent.OrderAccepted } }
        fake.fill("a-1", "f1", "0.1", "650")
        await { a.events.any { it is BrokerEvent.OrderFilled } }

        val fill = a.events.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.strategyId to fill.price).isEqualTo("a" to BigDecimal("650"))
        assertThat(b.events).isEmpty()
    }

    @Test
    fun `a contract settlement reaches every strategy on the account`() {
        val shared = session()
        val a = strategy()
        val b = strategy()
        broker(shared, a, "a")
        broker(shared, b, "b")

        fake.settle(code, "1000")

        await { a.events.isNotEmpty() && b.events.isNotEmpty() }
        assertThat(
            listOf(a, b).map { (it.events.single() as ContractSettled).price.toPlainString() },
        ).containsOnly("1000")
    }

    @Test
    fun `under the kill switch an opening order is refused and a reducing one goes through`() {
        val shared = session()
        val a = strategy()
        val b = strategy()
        val brokerA = broker(shared, a, "a")
        val brokerB = broker(shared, b, "b", held = "0.1")
        fake.killed = true

        brokerA.submit(market("a-1", "a"))
        brokerB.submit(market("b-1", "b", Side.SELL))

        await { a.events.isNotEmpty() && b.events.isNotEmpty() }
        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason).startsWith("kill_switch:")
        assertThat(b.events.single()).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(fake.submits.single().reduceOnly).isTrue()
    }

    @Test
    fun `a submit the gateway never answered is rejected once a resync shows it was not placed`() {
        val a = strategy()
        val brokerA = broker(session(), a, "a")
        fake.unreachable = 3

        brokerA.submit(market("a-1", "a"))
        await {
            fake.reset()
            Thread.sleep(50)
            a.events.isNotEmpty()
        }

        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason).contains("not placed")
    }

    @Test
    fun `after a restart, fills made while down are booked once and earlier ones not again`() {
        val first = strategy()
        val before = broker(session(), first, "a")
        before.submit(market("a-9", "a"))
        await { first.events.any { it is BrokerEvent.OrderAccepted } }
        fake.fill("a-9", "f9", "0.05", "650")
        await { first.events.any { it is BrokerEvent.OrderPartiallyFilled } }
        before.shutdown()
        brokers -= before
        fake.fill("a-9", "f10", "0.05", "652")

        val after = strategy()
        val restored = broker(session(), after, "a")
        val known =
            restored.recoverPendingOrders(
                listOf(
                    ManagedOrder(
                        "a-9",
                        market("a-9", "a"),
                        OrderState.WORKING,
                        cumulativeFilledQuantity = BigDecimal("0.05"),
                        createdAt = 5L,
                        lastUpdatedAt = 5L,
                    ),
                ),
                emptySet(),
            )

        await { after.events.any { it is BrokerEvent.OrderFilled } }
        assertThat(known).containsExactly("a-9")
        val filled = after.events.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(filled.quantity to filled.price).isEqualTo(BigDecimal("0.05") to BigDecimal("652"))
        assertThat(after.events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>()).isEmpty()
    }
}
