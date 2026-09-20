package com.qkt.app

import com.qkt.app.OrderManagerTier2FallbackFixtures.newBus
import com.qkt.app.OrderManagerTier2FallbackFixtures.tier1Only
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderModification
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTier2FallbackFireGuardTest {
    @Test
    fun `pending Stop is removed once triggered (does not double-fire)`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Stop(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.080"), 2L)))

        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `engine-held entry is rejected instead of firing after a halt`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val rejected = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { rejected += it }
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                engineHeldSubmissionBlockReason = { "halted: persistence failure" },
            )

        om.submit(
            OrderRequest.Stop(
                id = "held-entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "A",
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.11"), 1L)))

        assertThat(broker.submits).isEmpty()
        assertThat(om.getOrder("held-entry")).isNull()
        assertThat(rejected.single().reason).isEqualTo("halted: persistence failure")
    }

    @Test
    fun `trigger snapshot does not submit an order cancelled by an earlier trigger`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val submitted = mutableListOf<String>()
        lateinit var om: OrderManager
        val broker =
            object : Broker {
                override val name = "cancelling"
                override val capabilities = tier1Only

                override fun submit(request: OrderRequest): SubmitAck {
                    submitted += request.id
                    if (request.id == "first") om.cancel("second")
                    return SubmitAck(request.id, request.id, accepted = true)
                }

                override fun cancel(orderId: String) {
                    bus.publish(
                        com.qkt.events.BrokerEvent.OrderCancelled(
                            clientOrderId = orderId,
                            brokerOrderId = orderId,
                            reason = "sibling resolved",
                            timestamp = clock.now(),
                        ),
                    )
                }

                override fun modify(
                    orderId: String,
                    changes: OrderModification,
                ) = SubmitAck(orderId, orderId, accepted = false)
            }
        om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        for (id in listOf("first", "second")) {
            om.submit(
                OrderRequest.Stop(
                    id = id,
                    symbol = "EURUSD",
                    side = Side.SELL,
                    quantity = Money.of("1"),
                    stopPrice = Money.of("1.09"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )
        }

        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.08"), 1L)))

        assertThat(submitted).containsExactly("first")
    }
}
