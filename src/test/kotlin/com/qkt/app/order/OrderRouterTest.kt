package com.qkt.app.order

import com.qkt.app.OrderManager
import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRouterTest {
    private val bus = newBus()
    private val clock = FixedClock(0L)

    private fun manager(vararg caps: OrderTypeCapability): Pair<OrderManager, FakeBroker> {
        val broker = FakeBroker(bus, clock, caps.toSet())
        return OrderManager(broker, bus, MarketPriceTracker(), clock) to broker
    }

    private val price = BigDecimal("1990")
    private val gtc = TimeInForce.GTC

    private fun touch(id: String) =
        OrderRequest.IfTouched(id, "XAUUSD", Side.BUY, BigDecimal.ONE, price, TriggerType.MARKET, null, gtc, 0L)

    @Test
    fun `re-submitting a live order id is acknowledged without a second venue submit`() {
        val (om, broker) = manager(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)
        val limit = OrderRequest.Limit("l-1", "XAUUSD", Side.BUY, BigDecimal.ONE, price, gtc, 0L)

        om.submit(limit)
        val again = om.submit(limit)

        assertThat(again.accepted).isTrue()
        assertThat(again.clientOrderId).isEqualTo("l-1")
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `a venue that holds if touched orders gets them natively`() {
        val (om, broker) = manager(OrderTypeCapability.MARKET, OrderTypeCapability.IF_TOUCHED)

        om.submit(touch("t-1"))

        assertThat(broker.submits.single().id).isEqualTo("t-1")
        assertThat(om.getOrder("t-1")!!.state).isNotEqualTo(OrderState.PENDING)
    }

    @Test
    fun `a venue without if touched support leaves the order resting engine side`() {
        val (om, broker) = manager(OrderTypeCapability.MARKET)

        om.submit(touch("t-2"))

        assertThat(broker.submits).isEmpty()
        assertThat(om.getOrder("t-2")!!.state).isEqualTo(OrderState.PENDING)
    }
}
