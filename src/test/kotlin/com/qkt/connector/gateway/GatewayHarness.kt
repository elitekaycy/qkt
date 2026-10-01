package com.qkt.connector.gateway

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.Event
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach

/** A [FakeGateway] account and helpers to run strategies on it, each in its own session with its own bus. */
internal abstract class GatewayHarness {
    protected val code = "BTC_USDC-25DEC26-92000-C"
    protected val symbol = "DERIBIT:BTC_USDC_25DEC26_92000_C"
    protected val fake = FakeGateway(listOf(code))
    private val clock = SystemClock()
    private val sessions = mutableListOf<GatewaySession>()

    @AfterEach
    fun stopGateway() {
        sessions.forEach { it.close() }
        fake.shutdown()
    }

    /** A gateway session for an account expected to serve [expected]. */
    protected fun session(expected: Set<String> = setOf("a")) =
        GatewaySession(
            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 2),
            GatewaySymbols("DERIBIT:"),
            clock,
            GatewayIdentity("fake", "7", "demo"),
            expected,
            streamFactory = { e, r, c -> GatewayStream(fake.url, "k", e, r, c, initialBackoffMs = 20) },
            submitDeadlineMs = 300,
            retryMs = 50,
            resyncRetryMs = 50,
        ).also { sessions += it }

    /** One strategy's session: its bus, what reached it, and what it holds. */
    protected class Strategy {
        val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
        val events = CopyOnWriteArrayList<Event>()
        val held = ConcurrentHashMap<String, BigDecimal>()
        val positions =
            object : PositionProvider {
                override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

                override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal.ONE) }
            }

        init {
            bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
            bus.subscribe<BrokerEvent.OrderFilled> { events += it }
            bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events += it }
            bus.subscribe<BrokerEvent.OrderRejected> { events += it }
            bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
            bus.subscribe<BrokerEvent.GatewayUnreachable> { events += it }
            bus.subscribe<ContractSettled> { events += it }
        }

        inline fun <reified T : Event> of(): List<T> = events.filterIsInstance<T>()
    }

    /** A broker for strategy [id] of [strategy] on [session]. */
    protected fun broker(
        session: GatewaySession,
        strategy: Strategy,
        id: String,
        shared: Boolean = false,
    ) = GatewayBroker(session, strategy.bus, SystemClock(), strategy.positions, id, shared)

    protected fun market(
        id: String,
        strategy: String,
        side: Side = Side.BUY,
        quantity: String = "0.1",
    ) = OrderRequest.Market(id, symbol, side, BigDecimal(quantity), TimeInForce.GTC, 5L, strategy)

    /** Waits up to 5 s for [condition]. */
    protected fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
