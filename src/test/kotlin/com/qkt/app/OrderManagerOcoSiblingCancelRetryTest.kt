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
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The venue refuses the cancel of an OCO's other leg after one leg filled (an MT5 `OrderCancelFailed`:
 * the order is still live and may still fill). qkt sends the cancel again until the venue confirms
 * it, and alerts the operator when it stays unconfirmed, instead of leaving the other leg live.
 */
class OrderManagerOcoSiblingCancelRetryTest {
    private val bus = newBus()
    private val clock = FixedClock(time = 0L)
    private val delegate = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
    private val cancels = mutableListOf<String>()
    private var venueRefusesCancel = true
    private val broker =
        object : Broker by delegate {
            override fun cancel(orderId: String) {
                cancels += orderId
                if (venueRefusesCancel) {
                    bus.publish(BrokerEvent.OrderCancelFailed(orderId, orderId, "requote", "alpha"))
                } else {
                    delegate.cancel(orderId)
                }
            }
        }
    private val alerts = mutableListOf<String>()
    private val om =
        OrderManager(broker, bus, MarketPriceTracker(), clock, onProtectionFailure = { _, m -> alerts += m })
    private val leg1 = limit("l1", Side.BUY, "100").copy(strategyId = "alpha")
    private val leg2 = limit("l2", Side.SELL, "120").copy(strategyId = "alpha")

    private fun submitAndFillLeg1() {
        om.submit(
            OrderRequest.StandaloneOCO(
                "oco",
                "X",
                Side.BUY,
                Money.of("1"),
                leg1,
                leg2,
                TimeInForce.GTC,
                0L,
                strategyId = "alpha",
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(leg1.id, "position-101", "X", Side.BUY, Money.of("100"), Money.of("1"), "alpha"),
        )
    }

    @Test
    fun `a refused cancel of the other leg is sent again until the venue confirms it`() {
        submitAndFillLeg1()
        assertThat(cancels).containsExactly(leg2.id)

        venueRefusesCancel = false
        om.retryHaltCancellations(1_000L)

        assertThat(cancels).containsExactly(leg2.id, leg2.id)
        assertThat(om.getOrder(leg2.id)?.state).isEqualTo(OrderState.CANCELLED)
        om.retryHaltCancellations(60_000L)
        assertThat(cancels).hasSize(2)
    }

    @Test
    fun `a cancel of the other leg that stays unconfirmed alerts the operator`() {
        submitAndFillLeg1()

        om.retryHaltCancellations(1_000L)
        om.retryHaltCancellations(3_000L)

        assertThat(cancels).containsOnly(leg2.id).hasSize(3)
        assertThat(alerts.single()).contains("CRITICAL", "remains unconfirmed", leg2.id)
    }
}
