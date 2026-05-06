package com.qkt.events

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerEventTest {
    @Test
    fun `Accepted carries client and broker order ids`() {
        val e =
            BrokerEvent.OrderAccepted(
                clientOrderId = "c1",
                brokerOrderId = "b1",
            )
        assertThat(e.clientOrderId).isEqualTo("c1")
        assertThat(e.brokerOrderId).isEqualTo("b1")
    }

    @Test
    fun `Rejected carries reason`() {
        val e =
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = null,
                reason = "no price",
            )
        assertThat(e.reason).isEqualTo("no price")
    }

    @Test
    fun `Filled carries price quantity and side`() {
        val e =
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            )
        assertThat(e.price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(e.quantity).isEqualByComparingTo(Money.of("1"))
        assertThat(e.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `PartiallyFilled carries cumulative quantity`() {
        val e =
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("0.3"),
                cumulativeFilled = Money.of("0.3"),
            )
        assertThat(e.cumulativeFilled).isEqualByComparingTo(Money.of("0.3"))
    }

    @Test
    fun `Cancelled carries reason`() {
        val e =
            BrokerEvent.OrderCancelled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                reason = "user cancel",
            )
        assertThat(e.reason).isEqualTo("user cancel")
    }

    @Test
    fun `BalancesUpdated round-trips through the EventBus`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val received = mutableListOf<BrokerEvent.BalancesUpdated>()
        bus.subscribe<BrokerEvent.BalancesUpdated> { received.add(it) }

        bus.publish(
            BrokerEvent.BalancesUpdated(
                balances = mapOf("BTC" to BigDecimal("0.5"), "USDT" to BigDecimal("30000")),
                source = "BYBIT_SPOT",
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received.single().source).isEqualTo("BYBIT_SPOT")
        assertThat(received.single().balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `PositionReconciled round-trips through the EventBus`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val received = mutableListOf<BrokerEvent.PositionReconciled>()
        bus.subscribe<BrokerEvent.PositionReconciled> { received.add(it) }

        bus.publish(
            BrokerEvent.PositionReconciled(
                symbol = "BYBIT_LINEAR:BTCUSDT",
                oldQty = BigDecimal("0.4"),
                newQty = BigDecimal("0.5"),
                oldAvgPx = BigDecimal("79000"),
                newAvgPx = BigDecimal("80000"),
                source = "BYBIT_LINEAR",
                reason = "periodic reconcile",
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received.single().symbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
        assertThat(received.single().oldQty).isEqualByComparingTo(BigDecimal("0.4"))
    }

    @Test
    fun `PositionReconciled allows null old fields for new positions`() {
        val event =
            BrokerEvent.PositionReconciled(
                symbol = "BYBIT_LINEAR:ETHUSDT",
                oldQty = null,
                newQty = BigDecimal("1.0"),
                oldAvgPx = null,
                newAvgPx = BigDecimal("3000"),
                source = "BYBIT_LINEAR",
                reason = "broker reports new position",
            )

        assertThat(event.oldQty).isNull()
        assertThat(event.oldAvgPx).isNull()
    }

    @Test
    fun `OrderEvent marker is reachable from order variants`() {
        val accepted: BrokerEvent.OrderEvent =
            BrokerEvent.OrderAccepted(clientOrderId = "c1", brokerOrderId = "b1")
        assertThat(accepted.clientOrderId).isEqualTo("c1")
    }
}
