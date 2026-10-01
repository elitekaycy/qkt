package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live lane restarted from what it saved resumes its orders, its roll and its legs with the venue. */
class LaneRestartTest {
    private val f = ScriptedLaneFixture()

    private fun rest(): OrderRequest.Limit {
        val request =
            OrderRequest.Limit(
                "rest",
                f.front,
                Side.BUY,
                BigDecimal("0.010"),
                BigDecimal("62900"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            )
        f.broker.submit(request)
        return request
    }

    private fun held(request: OrderRequest) =
        ManagedOrder(request.id, request, OrderState.WORKING, createdAt = 0L, lastUpdatedAt = 0L)

    private fun ScriptedLaneFixture.ready() = broker.watchBookedLegs { emptyList() }

    @Test
    fun `a working order is taken back with what of it filled, and its next fill reaches the engine`() {
        val rest = rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        val g = f.restart()

        assertThat(g.broker.recoverPendingOrders(listOf(held(rest)), emptySet())).containsExactly("rest")
        val asked = g.venue.recovered.single()
        assertThat(asked.id to asked.request.symbol).isEqualTo("rest" to f.sep)
        assertThat(asked.request.quantity).isEqualByComparingTo("0.010")
        assertThat(asked.cumulativeFilledQuantity).isEqualByComparingTo("0.004")
        g.ready()
        assertThat(g.venue.ready).isTrue()
        g.last("rest", f.sep, Side.BUY, "0.006", "62895")

        val fill = g.engine.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId to fill.symbol).isEqualTo("rest" to f.front)
        assertThat(g.lanePositions.positionFor(f.sep)!!.quantity).isEqualByComparingTo("0.010")
        assertThat(
            g
                .saved()
                .strategies
                .single()
                .position,
        ).isEqualByComparingTo("0.010")
    }

    @Test
    fun `an order the venue never received is forgotten, and one the lane never saw is not accounted for`() {
        val rest = rest()
        val ghost = rest.copy(id = "ghost")
        val g = f.restart()
        g.venue.knows = emptySet()

        assertThat(g.broker.recoverPendingOrders(listOf(held(rest), held(ghost)), emptySet())).isEmpty()
        assertThat(g.saved().orders).isEmpty()
    }

    @Test
    fun `an order the engine no longer holds is cancelled at the venue once the session is ready`() {
        rest()
        val g = f.restart()
        g.ready()

        assertThat(g.venue.recovered.map { it.id }).containsExactly("rest")
        assertThat(g.venue.cancels).containsExactly("rest")
    }

    @Test
    fun `a roll waiting on its closing leg resumes on the venue's answer, and sends nothing until ready`() {
        val rest = rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        val close = f.leg(":close")
        val g = f.restart()
        g.broker.recoverPendingOrders(listOf(held(rest)), emptySet())

        assertThat(g.venue.recovered.map { it.id }).containsExactly("rest", close.id)
        g.last(close.id, f.sep, Side.SELL, "0.004", "63000")
        assertThat(g.venue.sent).isEmpty()
        g.ready()
        val open = g.leg(":open")
        assertThat(open.symbol to open.quantity).isEqualTo(f.dec to BigDecimal("0.004"))
        assertThat(g.venue.cancels).containsExactly("rest")
        g.last(open.id, f.dec, Side.BUY, "0.004", "63800")
        g.cancelled("rest")

        assertThat(
            g.ledger.entries
                .single()
                .quantity,
        ).isEqualByComparingTo("0.004")
        assertThat(
            g.venue.sent
                .single { it.id == "rest~r1" }
                .quantity,
        ).isEqualByComparingTo("0.006")
        assertThat(g.engine.filter { (it as BrokerEvent.OrderEvent).clientOrderId.startsWith("roll:") }).isEmpty()
        assertThat(g.saved().roll).isNull()
    }

    @Test
    fun `a closing leg the venue never received is sent again under its own id once ready`() {
        f.holdIntoRoll()
        val close = f.leg(":close")
        val g = f.restart()
        g.venue.knows = emptySet()
        g.ready()

        assertThat(g.venue.sent).containsExactly(close)
    }

    @Test
    fun `an opening leg part-filled before the restart carries the whole position at all its slices' weighted price`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        val open = f.leg(":open")
        f.slice(open.id, f.dec, Side.BUY, "0.004", "0.004", "63800")
        val g = f.restart()
        g.ready()

        assertThat(
            g.venue.recovered
                .single { it.id == open.id }
                .cumulativeFilledQuantity,
        ).isEqualByComparingTo("0.004")
        g.last(open.id, f.dec, Side.BUY, "0.006", "63805")
        val entry = g.ledger.entries.single()
        assertThat(entry.quantity).isEqualByComparingTo("0.010")
        assertThat(entry.toFill).isEqualByComparingTo("63803")
        assertThat(g.lanePositions.positionFor(f.dec)!!.quantity).isEqualByComparingTo("0.010")
    }

    @Test
    fun `an unwind still out at the restart is awaited again, and its fill never reaches the engine`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.slice(f.leg(":open").id, f.dec, Side.BUY, "0.004", "0.004", "63800")
        f.cancelled(f.leg(":open").id)
        val unwind = f.leg(":unwind")
        val g = f.restart()
        g.ready()
        g.last(unwind.id, f.dec, Side.SELL, "0.004", "63790")

        assertThat(g.engine).isEmpty()
        assertThat(g.lanePositions.positionFor(f.dec)?.quantity ?: BigDecimal.ZERO).isEqualByComparingTo("0")
        assertThat(g.saved().legs).isEmpty()
    }
}
