package com.qkt.app

import com.qkt.app.OrderManagerOcoFixtures.limit
import com.qkt.app.OrderManagerOcoFixtures.newBus
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoSilentRejectionTest {
    private val clock = FixedClock(0L)
    private val bus = newBus()
    private val fake = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))

    @Test
    fun `a first leg the venue refuses without an event abandons the oco before its second leg is sent`() {
        val sent = mutableListOf<String>()
        val refusing =
            object : Broker by fake {
                override fun submit(request: OrderRequest): SubmitAck {
                    sent += request.id
                    return SubmitAck(request.id, request.id, accepted = false, rejectReason = "market closed")
                }
            }
        val manager = OrderManager(refusing, bus, MarketPriceTracker(), clock)
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("leg-1", Side.BUY, "90"),
                leg2 = limit("leg-2", Side.SELL, "110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        val ack = manager.submit(oco)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("leg leg-1 rejected: market closed")
        assertThat(sent).containsExactly("leg-1")
        assertThat(manager.getOrder("oco")!!.state).isEqualTo(OrderState.REJECTED)
        assertThat(manager.getOrder("leg-1")!!.state).isEqualTo(OrderState.REJECTED)
        assertThat(manager.quantityFor("X", Side.SELL, null)).isEqualByComparingTo("0")
        assertThat(manager.quantityFor("X", Side.BUY, null)).isEqualByComparingTo("0")
    }
}
