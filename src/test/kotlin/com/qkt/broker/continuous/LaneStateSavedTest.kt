package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.PersistedCarryStep
import com.qkt.persistence.PersistedStreamLane
import com.qkt.persistence.PersistedStreamStrategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A live lane saves its whole state before every venue action and after every venue answer. */
class LaneStateSavedTest {
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

    /** The lane as saved when the venue received the first request whose id ends with [suffix]. */
    private fun savedWhenSent(suffix: String): () -> PersistedStreamLane {
        var lane: PersistedStreamLane? = null
        f.venue.onSubmit = { if (lane == null && it.id.endsWith(suffix)) lane = f.saved() }
        return { requireNotNull(lane) { "nothing ending with $suffix was sent" } }
    }

    @Test
    fun `an engine order is saved, on the contract it works on, before the venue receives it`() {
        val atSubmit = savedWhenSent("rest")
        rest()

        assertThat(atSubmit().contract).isEqualTo(f.sep)
        assertThat(atSubmit().orders.map { Triple(it.request.id, it.venueId, it.contract) })
            .containsExactly(Triple("rest", "rest", f.sep))
    }

    @Test
    fun `a slice is saved once booked, in the stream position, the order's progress and the contract book`() {
        rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")

        val lane = f.saved()
        assertThat(lane.strategies).containsExactly(PersistedStreamStrategy("s", BigDecimal("0.004"), null))
        assertThat(lane.orders.single().filled).isEqualByComparingTo("0.004")
        val holding = lane.holdings.single()
        assertThat(holding.strategyId to holding.contract).isEqualTo("s" to f.sep)
        assertThat(holding.quantity).isEqualByComparingTo("0.004")
        assertThat(holding.avgPrice).isEqualByComparingTo("62890")
    }

    @Test
    fun `a roll is saved with its resting orders' cancels and its closing leg before that leg is sent`() {
        rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        val atClose = savedWhenSent(":close")
        f.rollAt("63000")

        val roll = requireNotNull(atClose().roll)
        assertThat(roll.from to roll.to).isEqualTo(f.sep to f.dec)
        assertThat(roll.atMs).isEqualTo(f.roll)
        assertThat(roll.fromPrice to roll.toPrice).isEqualTo(BigDecimal("63000") to BigDecimal("63800"))
        assertThat(roll.resting.map { it.venueId }).containsExactly("rest")
        assertThat(atClose().cancelling.map { it.venueId }).containsExactly("rest")
        val closing = roll.steps.single() as PersistedCarryStep.Closing
        assertThat(closing.leg.id).endsWith(":close")
        assertThat(atClose().legs.map { it.leg.id }).containsExactly(closing.leg.id)
    }

    @Test
    fun `an opening leg part-filled is saved with its slices and the contract it already holds`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.slice(f.leg(":open").id, f.dec, Side.BUY, "0.004", "0.004", "63800")

        val lane = f.saved()
        val opening = requireNotNull(lane.roll).steps.single() as PersistedCarryStep.Opening
        assertThat(opening.close.quantity).isEqualByComparingTo("0.010")
        val leg = lane.legs.single()
        assertThat(leg.leg.id).isEqualTo(opening.leg.id)
        assertThat(leg.slices.map { it.quantity to it.price }).containsExactly(
            BigDecimal("0.004") to BigDecimal("63800"),
        )
        assertThat(lane.holdings.map { it.contract to it.quantity })
            .containsExactly(f.dec to BigDecimal("0.004"))
    }

    @Test
    fun `a finished roll is saved on the new contract with nothing in flight`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.010", "63800")

        val lane = f.saved()
        assertThat(lane.contract).isEqualTo(f.dec)
        assertThat(lane.roll).isNull()
        assertThat(lane.legs).isEmpty()
        val holding = lane.holdings.single()
        assertThat(holding.strategyId to holding.contract).isEqualTo("s" to f.dec)
        assertThat(holding.quantity).isEqualByComparingTo("0.010")
        assertThat(holding.avgPrice).isEqualByComparingTo("63800")
    }

    @Test
    fun `an unwind still out after its roll ended is saved, with the strategy stopped and flat on the stream`() {
        f.holdIntoRoll()
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "63000")
        f.slice(f.leg(":open").id, f.dec, Side.BUY, "0.004", "0.004", "63800")
        f.cancelled(f.leg(":open").id)

        val lane = f.saved()
        assertThat(lane.roll).isNull()
        assertThat(lane.legs.map { it.leg.id }).containsExactly(f.leg(":unwind").id)
        val strategy = lane.strategies.single()
        assertThat(strategy.position).isEqualByComparingTo("0")
        assertThat(strategy.stopped).contains("roll")
    }

    @Test
    fun `a re-placed order is saved on the new contract with what its new venue order was placed for`() {
        rest()
        f.slice("rest", f.sep, Side.BUY, "0.004", "0.004", "62890")
        f.rollAt("63000")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.004", "63000")
        f.last(f.leg(":open").id, f.dec, Side.BUY, "0.004", "63800")

        val order = f.saved().orders.single()
        assertThat(Triple(order.venueId, order.contract, order.replacements)).isEqualTo(Triple("rest~r1", f.dec, 1))
        assertThat(order.placed).isEqualByComparingTo("0.006")
        assertThat(order.filled).isEqualByComparingTo("0.004")
    }
}
