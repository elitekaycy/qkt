package com.qkt.app.order

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.persistence.PersistedTimeExit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimedLegCloserTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val ops = BookOrderOps(book)

    private fun exit(
        legId: String? = null,
        ticket: String? = null,
        side: Side = Side.BUY,
        protective: List<String> = emptyList(),
    ) = PersistedTimeExit(
        id = "x1",
        strategyId = "alpha",
        symbol = "XAUUSD",
        side = side,
        quantity = BigDecimal("2"),
        legId = legId,
        ticket = ticket,
        deadlineMs = 0L,
        protectiveIds = protective,
    )

    private fun closer(
        tickets: ((String, String) -> String?)? = null,
        legs: ((String, String) -> BigDecimal?)? = null,
        net: ((String, String) -> BigDecimal)? = null,
    ) = TimedLegCloser(book, FixedClock(5L), ops, tickets, legs, net)

    @Test
    fun `a leg close goes out under its default id with the ledger's ticket, after its protection is cancelled`() {
        val sent =
            closer(tickets = {
                _,
                _,
                ->
                "T7"
            }, legs = { _, _ -> BigDecimal("0.5") }).close(exit("L1", protective = listOf("p-sl")))

        assertThat(sent).isTrue()
        assertThat(ops.cancelled).containsExactly("p-sl")
        val close = ops.dispatched.single() as OrderRequest.Market
        assertThat(close.id).isEqualTo("x1-close")
        assertThat(close.quantity).isEqualByComparingTo("0.5")
        assertThat(close.closesTicket).isEqualTo("T7")
        assertThat(close.legIntent).isEqualTo(LegIntent.Close(legId = "L1", ticket = "T7"))
        assertThat(close.timestamp).isEqualTo(5L)
    }

    @Test
    fun `a leg the ledger no longer holds is not closed`() {
        assertThat(closer(legs = { _, _ -> null }).close(exit("L1"))).isFalse()
        assertThat(closer(legs = { _, _ -> BigDecimal.ZERO }).close(exit("L1"))).isFalse()
        assertThat(ops.dispatched).isEmpty()
    }

    @Test
    fun `with no readers the exit's own size and ticket are used`() {
        closer().close(exit("L1", ticket = "T1"))

        val close = ops.dispatched.single() as OrderRequest.Market
        assertThat(close.quantity).isEqualByComparingTo("2")
        assertThat(close.closesTicket).isEqualTo("T1")
        assertThat(closer().openQuantity(exit())).isEqualByComparingTo("2")
    }

    @Test
    fun `a netting close takes no more than the strategy holds on the exit's side`() {
        val shortThree = closer(net = { _, _ -> BigDecimal("-3") })

        assertThat(shortThree.openQuantity(exit(side = Side.SELL))).isEqualByComparingTo("2")
        assertThat(shortThree.openQuantity(exit(side = Side.BUY))).isNull()
        assertThat(closer(net = { _, _ -> BigDecimal("1") }).openQuantity(exit())).isEqualByComparingTo("1")
    }

    @Test
    fun `a ticket is filled in only for a leg exit that lacks one and whose leg the ledger can name`() {
        val withLedger = closer(tickets = { _, legId -> "T-$legId" })

        assertThat(withLedger.withTicket(exit("L1"))!!.ticket).isEqualTo("T-L1")
        assertThat(withLedger.withTicket(exit("L1", ticket = "T0"))).isNull()
        assertThat(withLedger.withTicket(exit())).isNull()
        assertThat(closer().withTicket(exit("L1"))).isNull()
    }

    @Test
    fun `without a close guard no other close is known to cover the leg`() {
        assertThat(closer().covered(exit("L1"))).isFalse()
    }
}
