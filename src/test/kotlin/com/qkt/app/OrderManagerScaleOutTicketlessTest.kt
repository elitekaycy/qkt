package com.qkt.app

import com.qkt.app.OrderManagerScaleOutFixtures.marketBasis
import com.qkt.app.OrderManagerScaleOutFixtures.newBus
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A basis fill that reports no ticket arms exits that close the leg the basis opened, not a ticket. */
class OrderManagerScaleOutTicketlessTest {
    private val clock = FixedClock(0L)
    private val bus = newBus()
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))

    private fun exitsAfterTicketlessFill(basis: OrderRequest.Market): List<OrderRequest.IfTouched> {
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)
        manager.submit(scaleOut(basis, "alpha"))
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "e1",
                brokerOrderId = null,
                symbol = "X",
                side = Side.BUY,
                price = BigDecimal("100"),
                quantity = BigDecimal("1"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )
        return listOf("s1-leg-0", "s1-leg-1").map { manager.getOrder(it)!!.request as OrderRequest.IfTouched }
    }

    @Test
    fun `a basis that opened its own leg arms exits that each close part of that leg`() {
        val exits =
            exitsAfterTicketlessFill(marketBasis("1").copy(legIntent = LegIntent.Open("e1", LegRole.INDEPENDENT)))

        exits.forEach { exit ->
            assertThat(exit.closesTicket).isNull()
            assertThat(exit.side).isEqualTo(Side.SELL)
            val intent = exit.legIntent as LegIntent.Close
            assertThat(intent.legId).isEqualTo("e1")
            assertThat(intent.ticket).isNull()
            assertThat(intent.partial).isTrue()
        }
    }

    @Test
    fun `a basis booked the way the venue nets it arms exits booked the same way`() {
        val exits = exitsAfterTicketlessFill(marketBasis("1"))

        assertThat(exits.map { it.legIntent }).containsOnly(LegIntent.Unplanned)
        assertThat(exits.map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo("0.5") }
    }
}
