package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `name and capabilities`() {
        val bus = newBus()
        val b = LogBroker(bus, FixedClock(0L))
        assertThat(b.name).isEqualTo("Log")
        assertThat(b.capabilities).contains(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )
    }

    @Test
    fun `submit emits OrderAccepted and returns accepted=true`() {
        val bus = newBus()
        val received = mutableListOf<BrokerEvent>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> received.add(e) }
        val b = LogBroker(bus, FixedClock(time = 100L))

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val ack = b.submit(req)

        assertThat(ack.clientOrderId).isEqualTo("c1")
        assertThat(ack.accepted).isTrue()
        assertThat(received).hasSize(1)
        assertThat(received.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit never emits a fill`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()
    }

    @Test
    fun `cancel emits OrderCancelled`() {
        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        val b = LogBroker(bus, FixedClock(time = 200L))

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )
        b.cancel("c1")

        assertThat(cancels).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit Limit accepted`() {
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        val ack =
            b.submit(
                OrderRequest.Limit(
                    id = "c2",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    limitPrice = Money.of("1.10"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(accepts).hasSize(1)
    }

    @Test
    fun `submit Stop accepted`() {
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        b.submit(
            OrderRequest.Stop(
                id = "c3",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(accepts).hasSize(1)
    }
}
