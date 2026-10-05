package com.qkt.app

import com.qkt.app.OrderManagerOcoFixtures.limit
import com.qkt.app.OrderManagerOcoFixtures.newBus
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An OCO qkt holds together must end with exactly one leg executed, also when a leg executes only in
 * part: the leg that executes second is closed by its position ticket and the operator is alerted.
 */
class OrderManagerOcoPartialDoubleFillTest {
    private val bus = newBus()
    private val clock = FixedClock(time = 0L)
    private val delegate = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
    private val cancels = mutableListOf<String>()
    private val broker =
        object : Broker by delegate {
            override fun cancel(orderId: String) {
                cancels += orderId
            }
        }
    private val alerts = mutableListOf<String>()
    private val om =
        OrderManager(broker, bus, MarketPriceTracker(), clock, onProtectionFailure = { _, m -> alerts += m })
    private val leg1 = limit("l1", Side.BUY, "100").copy(strategyId = "alpha")
    private val leg2 = limit("l2", Side.SELL, "120").copy(strategyId = "alpha")

    init {
        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = leg1,
                leg2 = leg2,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )
    }

    private fun partial(
        leg: OrderRequest.Limit,
        ticket: String,
        qty: String,
        cumulative: String,
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            leg.id,
            ticket,
            leg.symbol,
            leg.side,
            leg.limitPrice,
            Money.of(qty),
            Money.of(cumulative),
            "alpha",
        ),
    )

    private fun fill(
        leg: OrderRequest.Limit,
        ticket: String,
        qty: String,
    ) = bus.publish(
        BrokerEvent.OrderFilled(leg.id, ticket, leg.symbol, leg.side, leg.limitPrice, Money.of(qty), "alpha"),
    )

    private fun compensations() = delegate.submits.filterIsInstance<OrderRequest.Market>()

    @Test
    fun `a leg filling after its sibling filled in part is closed by its ticket`() {
        partial(leg1, "position-101", "0.4", "0.4")
        assertThat(cancels).containsExactly(leg2.id)

        fill(leg2, "position-202", "1")

        val close = compensations().single()
        assertThat(close.closesTicket).isEqualTo("position-202")
        assertThat(close.side).isEqualTo(Side.BUY)
        assertThat(close.quantity).isEqualByComparingTo("1")
        assertThat(alerts.single()).contains("CRITICAL OCO invariant violated", "position-202")

        // The first leg's remainder still ends normally: it is the leg that keeps its position.
        fill(leg1, "position-101", "0.6")
        assertThat(compensations()).hasSize(1)
    }

    @Test
    fun `a leg that filled in part after its sibling filled is closed when it is cancelled`() {
        fill(leg1, "position-101", "1")
        partial(leg2, "position-202", "0.3", "0.3")
        bus.publish(BrokerEvent.OrderCancelled(leg2.id, "202", "cancelled at the venue", "alpha"))

        val close = compensations().single()
        assertThat(close.closesTicket).isEqualTo("position-202")
        assertThat(close.side).isEqualTo(Side.BUY)
        assertThat(close.quantity).isEqualByComparingTo("0.3")
        assertThat(alerts.single()).contains("CRITICAL OCO invariant violated", "position-202")
    }

    @Test
    fun `the leg that executed first is never closed when its remainder is cancelled`() {
        partial(leg1, "position-101", "0.4", "0.4")
        bus.publish(BrokerEvent.OrderCancelled(leg2.id, null, "cancelled at the venue", "alpha"))
        bus.publish(BrokerEvent.OrderCancelled(leg1.id, "101", "cancelled at the venue", "alpha"))

        assertThat(compensations()).isEmpty()
        assertThat(alerts).isEmpty()
    }
}
