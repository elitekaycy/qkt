package com.qkt.connector.bybit

import com.qkt.common.Side
import com.qkt.connector.bybit.spot.BybitSpotStateRecovery.ManagedOrderView
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The linear broker's orders: an owner outlives the order's end, and a cancel waits for its fills. */
class BybitOrdersTest {
    private val orders = BybitOrders(retainEnded = 2)

    private fun place(id: String) = orders.register(ManagedOrderView(id, "BYBIT_LINEAR:BTCUSDT", Side.BUY, "s1"))

    private fun cancel(id: String) = BrokerEvent.OrderCancelled(id, null, "WS-reported cancel", "s1", 0L)

    @Test
    fun `an ended order keeps its owner but is no longer open`() {
        place("c1")

        orders.end("c1")

        assertThat(orders.strategyOf("c1")).isEqualTo("s1")
        assertThat(orders.open()).isEmpty()
        assertThat(orders.symbolOf("c1")).isNull()
    }

    @Test
    fun `owners of the oldest ended orders are let go beyond the retention`() {
        listOf("c1", "c2", "c3").forEach { place(it) }
        listOf("c1", "c2", "c3").forEach { orders.end(it) }

        assertThat(listOf("c1", "c2", "c3").map(orders::strategyOf)).containsExactly(null, "s1", "s1")
    }

    @Test
    fun `an order never placed has no owner, one placed by no strategy is still this broker's`() {
        orders.register(ManagedOrderView("c0", "BYBIT_LINEAR:BTCUSDT", Side.BUY, ""))
        place("c1")
        orders.forget("c1")

        assertThat(listOf("c0", "c1", "other").map(orders::strategyOf)).containsExactly("", null, null)
    }

    @Test
    fun `a cancel reporting unbooked executions is held until they are booked`() {
        place("c1")

        assertThat(orders.hold(cancel("c1"), BigDecimal("0.5"))).isTrue
        assertThat(orders.awaitingFills()).containsExactly("c1")
        assertThat(orders.booked("c1", BigDecimal("0.2"))).isNull()
        assertThat(orders.booked("c1", BigDecimal("0.3"))?.clientOrderId).isEqualTo("c1")
        assertThat(orders.awaitingFills()).isEmpty()
    }

    @Test
    fun `a cancel with nothing more executed, or of an unknown order, is not held`() {
        place("c1")
        orders.booked("c1", BigDecimal("0.5"))

        assertThat(orders.hold(cancel("c1"), BigDecimal("0.5"))).isFalse
        assertThat(orders.hold(cancel("other"), BigDecimal("1"))).isFalse
    }

    @Test
    fun `a held cancel can be released without its fills`() {
        place("c1")
        orders.hold(cancel("c1"), BigDecimal("0.5"))

        assertThat(orders.release("c1")?.clientOrderId).isEqualTo("c1")
        assertThat(orders.release("c1")).isNull()
    }
}
