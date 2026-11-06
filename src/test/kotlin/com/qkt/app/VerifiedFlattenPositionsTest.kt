package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.observe.insights.TicketAttribution
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerifiedFlattenPositionsTest {
    private val clock = FixedClock(1_000L)
    private val attribution = TicketAttribution().apply { record("1", "gold") }

    private class Venue(
        override val supportsPositionTickets: Boolean = true,
        val read: (Int) -> List<BrokerPositionTicket> = { emptyList() },
        val answer: (OrderRequest) -> SubmitAck = { SubmitAck(it.id, it.id, accepted = true) },
    ) : Broker {
        var reads = 0
        val submitted = mutableListOf<OrderRequest.Market>()
        override val name = "venue"
        override val capabilities = setOf(OrderTypeCapability.MARKET)

        override fun submit(request: OrderRequest): SubmitAck {
            submitted += request as OrderRequest.Market
            return answer(request)
        }

        override fun cancel(orderId: String) {}

        override fun positionTickets(): List<BrokerPositionTicket> = read(++reads)
    }

    private fun ticket(id: String) =
        BrokerPositionTicket(id, "XAUUSD", Side.BUY, BigDecimal("0.4"), BigDecimal("2000"), null, null, null, null, "")

    private fun run(
        venue: Venue,
        ids: List<String> = listOf("gold"),
        timeout: Duration = Duration.ofMinutes(1),
        engineFlatten: () -> Unit = {},
    ) = VerifiedFlatten(venue, attribution, clock, ids, engineFlatten, pollMs = 60_000L).run(timeout)

    @Test
    fun `a session with no strategy cannot flatten anything`() {
        val result = run(Venue(), ids = emptyList())

        assertThat(result).isEqualTo(FlattenResult(false, detail = "session has no strategy owner"))
    }

    @Test
    fun `a venue without position tickets falls back to the engine flatten and stays unverified`() {
        var engineFlattened = false

        val result = run(Venue(supportsPositionTickets = false), engineFlatten = { engineFlattened = true })

        assertThat(engineFlattened).isTrue()
        assertThat(result.verifiedFlat).isFalse()
        assertThat(result.detail).isEqualTo("broker venue cannot verify open position tickets")
    }

    @Test
    fun `an unreadable venue is reported before anything is closed`() {
        val venue = Venue(read = { error("gateway down") })

        assertThat(run(venue).detail).isEqualTo("broker position read failed: gateway down")
        assertThat(venue.submitted).isEmpty()
    }

    @Test
    fun `a close the venue throws on stops the flatten and names the ticket`() {
        val venue = Venue(read = { listOf(ticket("1")) }, answer = { error("socket closed") })

        val result = run(venue)

        assertThat(result.detail).isEqualTo("close submission failed for ticket 1: socket closed")
        assertThat(result.remainingTickets).containsExactly("1")
    }

    @Test
    fun `a rejected close is reported with unattributed tickets still open`() {
        val venue =
            Venue(
                read = { listOf(ticket("1"), ticket("manual")) },
                answer = { SubmitAck(it.id, null, accepted = false, rejectReason = "market closed") },
            )

        val result = run(venue)

        assertThat(result.detail).isEqualTo("close rejected for ticket 1: market closed")
        assertThat(result.remainingTickets).containsExactly("1", "manual")
        val close = venue.submitted.single()
        assertThat(close.closesTicket).isEqualTo("1")
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.id).isEqualTo("operator-kill-1")
    }

    @Test
    fun `an interrupt while waiting for the venue to confirm ends the flatten unverified`() {
        val venue =
            Venue(
                read = { attempt ->
                    // The verification read is where a stop interrupts the waiting thread.
                    if (attempt == 2) Thread.currentThread().interrupt()
                    listOf(ticket("1"))
                },
            )

        val result = run(venue)

        assertThat(result.detail).isEqualTo("broker verification interrupted")
        assertThat(result.remainingTickets).containsExactly("1")
        assertThat(Thread.interrupted()).isTrue()
    }
}
