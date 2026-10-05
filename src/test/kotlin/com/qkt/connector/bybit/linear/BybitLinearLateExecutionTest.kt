package com.qkt.connector.bybit.linear

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
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** #1330: an execution heard after its order ended is booked once, to the order's strategy. */
class BybitLinearLateExecutionTest {
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
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
        loggers.forEach { it.addAppender(logs) }
    }

    @AfterEach
    fun detach() = loggers.forEach { it.detachAppender(logs) }

    private fun broker() = BybitLinearBroker(client, bus, clock, StrategyPositionTracker().account)

    private fun BybitLinearBroker.place(
        id: String,
        symbol: String = "BYBIT_LINEAR:BTCUSDT",
        side: Side = Side.SELL,
    ) {
        client.responses["/v5/order/create"] = """{"retCode":0,"retMsg":"OK","result":{"orderId":"o-$id"}}"""
        submit(
            OrderRequest.Limit(id, symbol, side, Money.of("0.5"), Money.of("94942.5"), TimeInForce.GTC, 0L, "s1"),
        )
    }

    private fun order(
        status: String,
        cumExecQty: String,
        id: String = "c1",
    ) = client.emitWsFrame("order", frame("order", wsOrder(id, status, cumExecQty)))

    private fun execution(vararg entries: String) = client.emitWsFrame("execution", frame("execution", *entries))

    private fun fills() = events.filterIsInstance<BrokerEvent.OrderFilled>()

    private fun firstSlice() = wsExecution("c1", "e-a", execQty = "0.2", leavesQty = "0.3", execFee = "10.549011")

    @Test
    fun `an execution after its order's Filled update is booked to its strategy with its price and fee`() {
        broker().place("c1")

        execution(firstSlice())
        order("Filled", "0.5")
        execution(wsExecution("c1", "e-b", execQty = "0.3", execFee = "15.8235165"))

        assertThat(fills().map { listOf(it.strategyId, it.quantity, it.price, it.venueCosts, it.side) })
            .containsExactly(
                listOf("s1", Money.of("0.2"), Money.of("95900.1"), Money.of("10.549011"), Side.SELL),
                listOf("s1", Money.of("0.3"), Money.of("95900.1"), Money.of("15.8235165"), Side.SELL),
            )
    }

    @Test
    fun `an execution after its order's Cancelled update is booked, and the cancel follows it`() {
        broker().place("c1")

        order("Cancelled", "0.2")
        assertThat(events).isEmpty()
        execution(firstSlice())

        assertThat(events.map { it::class.simpleName to (it as BrokerEvent.OrderEvent).strategyId }).containsExactly(
            "OrderFilled" to "s1",
            "OrderCancelled" to "s1",
        )
        assertThat(fills().single().quantity).isEqualByComparingTo("0.2")
        assertThat(fills().single().venueCosts).isEqualByComparingTo("10.549011")
    }

    @Test
    fun `a cancel that reports nothing executed beyond what was booked is published at once`() {
        broker().place("c1")

        execution(firstSlice())
        order("Cancelled", "0.2")

        assertThat(events.map { it::class.simpleName }).containsExactly("OrderFilled", "OrderCancelled")
    }

    @Test
    fun `an execution only the replay finds after the cancel is booked with its fee, then the cancel`() {
        val broker = broker()
        broker.place("c2", symbol = "BYBIT_LINEAR:ETHPERP", side = Side.BUY)
        client.responsesByPredicate +=
            { path: String, q: String -> path == "/v5/execution/list" && "orderLinkId=c2" in q } to
            page(restExecution("c2"))

        order("Cancelled", "0.1", id = "c2")
        client.fireOnReconnect()

        val fill = fills().single()
        assertThat(
            listOf(fill.strategyId, fill.symbol, fill.side),
        ).containsExactly("s1", "BYBIT_LINEAR:ETHPERP", Side.BUY)
        assertThat(fill.quantity).isEqualByComparingTo("0.1")
        assertThat(fill.price).isEqualByComparingTo("1190.15")
        assertThat(fill.venueCosts).isEqualByComparingTo("0.071409")
        assertThat(events.last()).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }

    @Test
    fun `a held cancel whose executions never appear is released at the next reconcile`() {
        broker().place("c1")

        order("Cancelled", "0.2")
        client.fireOnReconnect()
        assertThat(events).isEmpty()
        client.fireOnReconnect()

        assertThat(events.map { it::class.simpleName }).containsExactly("OrderCancelled")
    }

    @Test
    fun `an execution booked from the stream is not booked again when the replay sees it`() {
        broker().place("c2", symbol = "BYBIT_LINEAR:ETHPERP", side = Side.BUY)
        val rest = restExecution("c2")
        val asStream = rest.replace("{\"symbol\"", "{\"category\":\"linear\",\"symbol\"")

        execution(asStream)
        order("Filled", "0.1", id = "c2")
        client.responses["/v5/execution/list"] = page(rest)
        client.fireOnReconnect()
        client.fireOnReconnect()

        assertThat(fills()).hasSize(1)
        assertThat(fills().single().strategyId).isEqualTo("s1")
    }

    @Test
    fun `a restarted broker's startup replay does not book a fill of the previous session again`() {
        val first = broker().apply { place("c1") }
        execution(firstSlice())
        first.shutdown()
        val previous = wsExecution("c1", "e-a", execQty = "0.2", leavesQty = "0.3", execFee = "10.549011")
        client.responses["/v5/execution/list"] = page(previous)

        broker().shutdown()

        assertThat(fills()).hasSize(1)
    }

    @Test
    fun `an execution of an order qkt did not place is logged and never booked`() {
        val other = wsExecution("someone-else", "e-x")
        client.responses["/v5/execution/list"] = page(restExecution("someone-else"))
        broker()

        execution(other)
        execution(wsExecution("", "e-y"))

        assertThat(fills()).isEmpty()
        val warnings = logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertThat(warnings).hasSize(3).allMatch { "order qkt did not place; not booked" in it }
        assertThat(warnings).anyMatch { "someone-else" in it && "e0cbe81d" in it }.anyMatch { "e-x" in it }
    }
}
