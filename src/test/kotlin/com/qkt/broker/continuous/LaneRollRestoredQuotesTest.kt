package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A restored roll has only the measured prices until it hears the stream. The market has moved past the 1% collar since
 * the roll was measured (63,000 -> 63,800 here, now 65,800 on the new contract): the roll must go on around what the
 * stream says now, not stop because every attempt was collared around a price the book left.
 */
class LaneRollRestoredQuotesTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    private fun ScriptedLaneFixture.moved() {
        clock.time += 15_000
        quote("65000", "64990", "65010") // December 65,800/65,790/65,810 in contract prices
    }

    /** A venue whose book is at [bid]/[ask]: the attempt out either fills at its side or ends unfilled. */
    private fun ScriptedLaneFixture.answer(
        kind: String,
        contract: String,
        bid: String,
        ask: String,
    ): Boolean {
        val leg = attempt(kind) as OrderRequest.Limit
        val crosses =
            if (leg.side == Side.BUY) leg.limitPrice >= BigDecimal(ask) else leg.limitPrice <= BigDecimal(bid)
        if (crosses) {
            last(leg.id, contract, leg.side, "0.010", if (leg.side == Side.BUY) ask else bid)
        } else {
            cancelled(leg.id)
        }
        return crosses
    }

    @Test
    fun `an opening leg sent after a restart is collared around the stream's latest quote`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.moved()
        g.last(close.id, g.sep, Side.BUY, "0.010", "65010")

        // SELL at 1% under 65,800, not under the measured 63,800 (63,162).
        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("65142")
    }

    @Test
    fun `a retried opening leg of a restored roll moves with the book and carries`() {
        // A long: its opening leg buys, so a collar around the old measured price lies under the book and never fills.
        f.hold(Side.BUY)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.last(close.id, g.sep, Side.SELL, "0.010", "65010") // no quote heard yet: collared around the measured 63,800

        assertThat(g.answer("open", g.dec, "65790", "65810")).isFalse()
        g.moved()

        assertThat(g.answer("open", g.dec, "65790", "65810")).isTrue()
        assertThat(g.stop()).isNull()
        assertThat(g.heldOn()).isEqualTo(g.dec)
    }

    @Test
    fun `a closing leg the venue never received goes again near the book, by the contracts' measured spread`() {
        f.hold(Side.BUY)
        f.rolled()

        val g = f.restart()
        g.venue.knows = emptySet()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.moved()

        // The stream serves December only: September is about 65,800 - 800 = 65,000, and a SELL collars 1% under it.
        val again = g.venue.sent.single() as OrderRequest.Limit
        assertThat(again.limitPrice).isEqualByComparingTo("64350")
        assertThat(g.answer("close", g.sep, "64990", "65010")).isTrue()
        g.moved()
        assertThat(g.answer("open", g.dec, "65790", "65810")).isTrue()
        assertThat(g.stop()).isNull()
    }

    @Test
    fun `a restored roll that heard nothing collars around the first quote it hears`() {
        f.hold(Side.BUY)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.last(close.id, g.sep, Side.SELL, "0.010", "63000")

        // The session's first tick, 63,010 on the stream, is December 63,810.
        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("64448.1")
    }
}
