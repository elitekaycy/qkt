package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What the venue refuses reaches the engine as the engine order's own outcome. */
class LaneVenueRejectionTest {
    private val f = ScriptedLaneFixture()

    private fun rest() =
        f.broker.submit(
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            ),
        )

    private fun rejected(id: String) =
        f.venueBus.publish(BrokerEvent.OrderRejected(id, null, "price band", "s", f.clock.time))

    @Test
    fun `a venue rejection of the original order reaches the engine under its id`() {
        rest()

        rejected("rest")

        val rejection = f.engine.filterIsInstance<BrokerEvent.OrderRejected>().single()
        assertThat(rejection.clientOrderId).isEqualTo("rest")
        assertThat(rejection.reason).isEqualTo("price band")
    }

    @Test
    fun `a rejected re-placement at the roll cancels the engine order with the reason`() {
        rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        f.cancelled("rest")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.004", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.004", "63800")
        assertThat(f.venue.sent.map { it.id }).contains("rest~r1")

        rejected("rest~r1")

        val cancel = f.engine.filterIsInstance<BrokerEvent.OrderCancelled>().last()
        assertThat(cancel.clientOrderId).isEqualTo("rest")
        assertThat(cancel.reason).isEqualTo("re-placing on ${f.dec} at the roll was rejected: price band")
        assertThat(f.engine.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
    }

    @Test
    fun `a rejection of an order the lane does not know is ignored`() {
        rejected("stranger")

        assertThat(f.engine.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
    }
}
