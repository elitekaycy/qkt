package com.qkt.connector.bybit.spot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.bybit.BybitDocPayloads.frame
import com.qkt.connector.bybit.BybitDocPayloads.page
import com.qkt.connector.bybit.BybitDocPayloads.restExecution
import com.qkt.connector.bybit.BybitDocPayloads.wsExecution
import com.qkt.connector.bybit.BybitDocPayloads.wsOrder
import com.qkt.connector.bybit.BybitExecutionReplay
import com.qkt.connector.bybit.BybitExecutionStream
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** #1333: a spot execution heard after its order ended is booked once, to the order's strategy. */
class BybitSpotLateExecutionTest {
    private val clock = FixedClock(1_746_270_400_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val client = FakeBybitClient()
    private val events = mutableListOf<BrokerEvent>()
    private val loggers =
        listOf(BybitExecutionStream::class.java, BybitExecutionReplay::class.java)
            .map { LoggerFactory.getLogger(it) as Logger }
    private val logs = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
        // Booked here either way; partial versus completing fills are pinned by BybitLinearPartialFillTest.
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { p ->
            events +=
                BrokerEvent.OrderFilled(
                    p.clientOrderId,
                    p.brokerOrderId,
                    p.symbol,
                    p.side,
                    p.price,
                    p.quantity,
                    p.strategyId,
                    p.timestamp,
                    venueCosts = p.venueCosts,
                )
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
        loggers.forEach { it.addAppender(logs) }
    }

    @AfterEach
    fun detach() = loggers.forEach { it.detachAppender(logs) }

    private fun broker() = BybitSpotBroker(client, bus, clock)

    private fun BybitSpotBroker.place(id: String) {
        client.responses["/v5/order/create"] = """{"retCode":0,"retMsg":"OK","result":{"orderId":"o-$id"}}"""
        submit(
            OrderRequest.Limit(
                id,
                "BYBIT_SPOT:BTCUSDT",
                Side.SELL,
                Money.of("0.5"),
                Money.of("94942.5"),
                TimeInForce.GTC,
                0L,
                "s1",
            ),
        )
    }

    private fun order(
        status: String,
        cumExecQty: String,
    ) = client.emitWsFrame("order", frame("order", wsOrder("c1", status, cumExecQty, category = "spot")))

    private fun execution(vararg entries: String) = client.emitWsFrame("execution", frame("execution", *entries))

    private fun slice(
        execId: String,
        qty: String,
        leaves: String,
        fee: String,
        orderLinkId: String = "c1",
    ) = wsExecution(orderLinkId, execId, qty, leaves, fee, category = "spot")

    private fun spotRest(orderLinkId: String) = restExecution(orderLinkId).replace("ETHPERP", "BTCUSDT")

    private fun fills() = events.filterIsInstance<BrokerEvent.OrderFilled>()

    @Test
    fun `an execution after its order's Filled update is booked to its strategy with its price and fee`() {
        broker().place("c1")

        execution(slice("e-a", "0.2", "0.3", "10.549011"))
        order("Filled", "0.5")
        execution(slice("e-b", "0.3", "0", "15.8235165"))

        assertThat(fills().map { listOf(it.strategyId, it.symbol, it.quantity, it.price, it.venueCosts) })
            .containsExactly(
                listOf("s1", "BYBIT_SPOT:BTCUSDT", Money.of("0.2"), Money.of("95900.1"), Money.of("10.549011")),
                listOf("s1", "BYBIT_SPOT:BTCUSDT", Money.of("0.3"), Money.of("95900.1"), Money.of("15.8235165")),
            )
    }

    @Test
    fun `an execution after its order's PartiallyFilledCanceled update is booked, and the cancel follows it`() {
        broker().place("c1")

        order("PartiallyFilledCanceled", "0.2")
        assertThat(events).isEmpty()
        execution(slice("e-a", "0.2", "0.3", "10.549011"))

        assertThat(events.map { it::class.simpleName to (it as BrokerEvent.OrderEvent).strategyId }).containsExactly(
            "OrderFilled" to "s1",
            "OrderCancelled" to "s1",
        )
    }

    @Test
    fun `an execution with quantity left is a partial fill with the venue's cumulative, the last completes it`() {
        val partials = mutableListOf<BrokerEvent.OrderPartiallyFilled>()
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials += it }
        broker().place("c1")

        execution(slice("e-a", "0.2", "0.3", "10.549011"))
        execution(slice("e-b", "0.3", "0", "15.8235165"))

        assertThat(partials.single().cumulativeFilled).isEqualByComparingTo("0.2")
        assertThat(events.filterIsInstance<BrokerEvent.OrderFilled>().last().quantity).isEqualByComparingTo("0.3")
    }

    @Test
    fun `a cancel with nothing executed is published at once`() {
        broker().place("c1")

        order("Cancelled", "0")

        assertThat(events.map { it::class.simpleName }).containsExactly("OrderCancelled")
    }

    @Test
    fun `an execution only the replay finds after the cancel is booked with its fee, then the cancel`() {
        broker().place("c1")
        client.responsesByPredicate +=
            { path: String, q: String -> path == "/v5/execution/list" && "orderLinkId=c1" in q } to page(spotRest("c1"))

        order("PartiallyFilledCanceled", "0.1")
        client.fireOnReconnect()

        val fill = fills().single()
        assertThat(listOf(fill.strategyId, fill.symbol)).containsExactly("s1", "BYBIT_SPOT:BTCUSDT")
        assertThat(fill.quantity).isEqualByComparingTo("0.1")
        assertThat(fill.venueCosts).isEqualByComparingTo("0.071409")
        assertThat(events.last()).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }

    @Test
    fun `an execution booked from the stream is not booked again when the replay sees it`() {
        broker().place("c1")

        execution(slice("e-a", "0.5", "0", "26.3725275"))
        client.responses["/v5/execution/list"] = page(slice("e-a", "0.5", "0", "26.3725275"))
        client.fireOnReconnect()

        assertThat(fills()).hasSize(1)
    }

    @Test
    fun `an execution of an order qkt did not place is logged once and never booked`() {
        client.responses["/v5/execution/list"] = page(spotRest("someone-else"))
        val broker = broker()

        execution(slice("e-x", "0.5", "0", "26.3725275", orderLinkId = "someone-else"))
        client.fireOnReconnect()
        broker.shutdown()

        assertThat(fills()).isEmpty()
        val warnings = logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertThat(warnings).hasSize(2).allMatch { "order qkt did not place; not booked" in it }
    }
}
