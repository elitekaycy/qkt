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
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoEmulationTest {
    @Test
    fun `OCO without native capability is client emulated as two broker legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("l1", "l2")
        assertThat(broker.submits).noneMatch { it is OrderRequest.StandaloneOCO }
    }

    @Test
    fun `OCO with native capability is submitted as one venue atomic request`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.OCO))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val request =
            OrderRequest.StandaloneOCO(
                id = "native-oco",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("native-l1", Side.BUY, "100"),
                leg2 = limit("native-l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        om.submit(request)

        // The venue receives the atomic OCO exactly as submitted, with leg intents planned.
        assertThat(broker.submits)
            .containsExactly(LegIntentPlanner.plan(request, com.qkt.broker.PositionAccountingMode.UNKNOWN))
        assertThat(om.siblingsOf("native-l1")).isEmpty()
    }

    @Test
    fun `client emulated OCO closes the second fill by its position ticket`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val delegate =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT),
            )
        val pendingCancels = mutableListOf<String>()
        val broker =
            object : Broker by delegate {
                override fun cancel(orderId: String) {
                    pendingCancels += orderId
                }
            }
        val alerts = mutableListOf<Pair<String, String>>()
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { strategyId, message -> alerts += strategyId to message },
            )
        val leg1 = limit("l1", Side.BUY, "100").copy(strategyId = "alpha")
        val leg2 = limit("l2", Side.SELL, "120").copy(strategyId = "alpha")
        om.submit(
            OrderRequest.StandaloneOCO(
                id = "emulated-oco",
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

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = leg1.id,
                brokerOrderId = "position-101",
                symbol = leg1.symbol,
                side = leg1.side,
                price = Money.of("100"),
                quantity = leg1.quantity,
                strategyId = "alpha",
            ),
        )
        assertThat(pendingCancels).containsExactly(leg2.id)
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        assertThat(om.getOrder(leg1.id)).isNotNull()

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = leg2.id,
                brokerOrderId = "position-202",
                symbol = leg2.symbol,
                side = leg2.side,
                price = Money.of("120"),
                quantity = leg2.quantity,
                strategyId = "alpha",
            ),
        )

        val compensation = delegate.submits.filterIsInstance<OrderRequest.Market>().single()
        assertThat(compensation.closesTicket).isEqualTo("position-202")
        assertThat(compensation.side).isEqualTo(Side.BUY)
        assertThat(compensation.quantity).isEqualByComparingTo("1")
        assertThat(compensation.strategyId).isEqualTo("alpha")
        assertThat(alerts).hasSize(1)
        assertThat(alerts.single().first).isEqualTo("alpha")
        assertThat(alerts.single().second)
            .contains("CRITICAL OCO invariant violated", "position-202")
    }
}
