package com.qkt.connector.bybit.linear

import com.qkt.app.OrderManager
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.bybit.BybitDocPayloads.frame
import com.qkt.connector.bybit.BybitDocPayloads.page
import com.qkt.connector.bybit.BybitDocPayloads.wsExecution
import com.qkt.connector.bybit.BybitDocPayloads.wsOrder
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bracket whose entry Bybit fills in two executions (Bybit's documented market Sell of 0.5, as 0.2 then
 * 0.3): each execution with `leavesQty` left is a partial fill, the last one completes the order, and the
 * exits protect the whole 0.5 once.
 */
class BybitLinearPartialFillTest {
    private val clock = FixedClock(1_746_270_400_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val client = FakeBybitClient()
    private val broker = BybitLinearBroker(client, bus, clock, StrategyPositionTracker().account)
    private val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)
    private val partials = mutableListOf<BrokerEvent.OrderPartiallyFilled>()
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

    init {
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials += it }
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
        client.responses["/v5/order/create"] = """{"retCode":0,"retMsg":"OK","result":{"orderId":"o-1"}}"""
    }

    private fun submitBracket() {
        val entry = OrderRequest.Market("e1", SYMBOL, Side.SELL, Money.of("0.5"), TimeInForce.GTC, 0L, "s1")
        manager.submit(
            OrderRequest.Bracket(
                "b1",
                SYMBOL,
                Side.SELL,
                Money.of("0.5"),
                entry,
                Money.of("94000"),
                StopLossSpec.Fixed(Money.of("97000")),
                TimeInForce.GTC,
                0L,
                "s1",
            ),
        )
    }

    private fun execution(vararg entries: String) = client.emitWsFrame("execution", frame("execution", *entries))

    private fun first(id: String = "e1") = wsExecution(id, "e-a", "0.2", "0.3", "10.549011")

    private fun last(id: String = "e1") = wsExecution(id, "e-b", "0.3", "0", "15.8235165")

    private fun exits() =
        client.posts
            .filter { it.path == "/v5/order/create" && "\"orderLinkId\":\"e1\"" !in it.body }
            .map {
                Regex("\"qty\":\"([0-9.]+)\"")
                    .find(it.body)!!
                    .groupValues[1]
                    .toBigDecimal()
                    .stripTrailingZeros()
            }

    @Test
    fun `an execution with quantity left is a partial fill carrying the venue's cumulative`() {
        submitBracket()

        execution(first())

        assertThat(fills).isEmpty()
        val partial = partials.single()
        assertThat(listOf(partial.strategyId, partial.symbol, partial.side)).containsExactly("s1", SYMBOL, Side.SELL)
        assertThat(partial.quantity).isEqualByComparingTo("0.2")
        assertThat(partial.cumulativeFilled).isEqualByComparingTo("0.2")
        assertThat(partial.venueCosts).isEqualByComparingTo("10.549011")
        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(exits()).isEmpty()
    }

    @Test
    fun `the execution that leaves nothing completes the order and arms exits sized to the whole fill, once`() {
        submitBracket()

        execution(first())
        execution(last())

        assertThat(fills.single().quantity).isEqualByComparingTo("0.3")
        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(manager.getOrder("e1")?.cumulativeFilledQuantity).isEqualByComparingTo("0.5")
        assertThat(exits()).hasSize(2).allMatch { it.compareTo(Money.of("0.5")) == 0 }
    }

    @Test
    fun `executions the replay returns newest first are booked oldest first`() {
        submitBracket()
        client.responses["/v5/execution/list"] = page(last(), first())

        client.fireOnReconnect()

        assertThat(partials.map { it.quantity.toPlainString() to it.cumulativeFilled.toPlainString() })
            .containsExactly(Money.of("0.2").toPlainString() to Money.of("0.2").toPlainString())
        assertThat(fills.single().quantity).isEqualByComparingTo("0.3")
        assertThat(exits()).hasSize(2).allMatch { it.compareTo(Money.of("0.5")) == 0 }
    }

    @Test
    fun `a final execution heard before an earlier one waits for it, so the order completes in order`() {
        submitBracket()
        client.responsesByPredicate +=
            { path: String, q: String -> path == "/v5/execution/list" && "orderLinkId=e1" in q } to page(first())

        execution(last())
        assertThat(fills).isEmpty()
        client.fireOnReconnect()

        assertThat(partials.single().cumulativeFilled).isEqualByComparingTo("0.2")
        assertThat(fills.single().quantity).isEqualByComparingTo("0.3")
        assertThat(exits()).hasSize(2).allMatch { it.compareTo(Money.of("0.5")) == 0 }
    }

    @Test
    fun `a part-filled entry the venue cancels is protected for the part filled`() {
        submitBracket()

        execution(first())
        client.emitWsFrame("order", frame("order", wsOrder("e1", "Cancelled", "0.2")))

        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(exits()).hasSize(2).allMatch { it.compareTo(Money.of("0.2")) == 0 }
    }

    @Test
    fun `a scale-out arms its legs only when the basis's last execution completes it`() {
        val basis = OrderRequest.Market("e1", SYMBOL, Side.SELL, Money.of("0.5"), TimeInForce.GTC, 0L, "s1")
        val leg = ScaleOutLeg(Money.of("94000"), Money.of("1"))
        manager.submit(
            OrderRequest.ScaleOut(
                "so",
                SYMBOL,
                Side.SELL,
                Money.of("0.5"),
                basis,
                listOf(leg),
                TimeInForce.GTC,
                0L,
                "s1",
            ),
        )

        execution(first())
        assertThat(manager.getOrder("so-leg-0")).isNull()
        execution(last())

        assertThat(manager.getOrder("so-leg-0")?.request?.quantity).isEqualByComparingTo("0.5")
    }

    private companion object {
        const val SYMBOL = "BYBIT_LINEAR:BTCUSDT"
    }
}
