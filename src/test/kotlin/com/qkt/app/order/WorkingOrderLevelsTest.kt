package com.qkt.app.order

import com.qkt.broker.OrderModification
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** qkt#1754: a reported modification moves the engine's order to the reported levels, and only its levels. */
class WorkingOrderLevelsTest {
    private val clock = FixedClock(time = 5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val book = OrderBook({ false }, {})

    init {
        WorkingOrderLevels(BookOrderOps(book), clock).subscribe(bus)
    }

    private fun hold(request: OrderRequest) =
        book.put(ManagedOrder(request.id, request, OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L))

    private fun modified(
        id: String,
        changes: OrderModification,
    ) = bus.publish(BrokerEvent.OrderModified(id, "v-$id", changes, "s"))

    private val qty = BigDecimal("0.010")

    @Test
    fun `a stop-limit takes both reported levels and keeps its quantity`() {
        hold(
            OrderRequest.StopLimit(
                "sll",
                "X",
                Side.SELL,
                qty,
                BigDecimal("62800"),
                BigDecimal("62790"),
                TimeInForce.GTC,
                0,
                "s",
            ),
        )

        modified("sll", OrderModification(BigDecimal("1"), BigDecimal("62793"), BigDecimal("62803")))

        val moved = book["sll"]!!.request as OrderRequest.StopLimit
        assertThat(moved.stopPrice).isEqualByComparingTo("62803")
        assertThat(moved.limitPrice).isEqualByComparingTo("62793")
        assertThat(moved.quantity).isEqualByComparingTo("0.010")
        assertThat(book["sll"]!!.lastUpdatedAt).isEqualTo(5L)
    }

    @Test
    fun `an order without levels is left as it is, and an unknown order is ignored`() {
        val market = OrderRequest.Market("mkt", "X", Side.SELL, qty, TimeInForce.GTC, 0, "s")
        hold(market)

        modified("mkt", OrderModification(newLimitPrice = BigDecimal("1")))
        modified("gone", OrderModification(newStopPrice = BigDecimal("1")))

        assertThat(book["mkt"]!!.request).isEqualTo(market)
        assertThat(book["gone"]).isNull()
    }
}
