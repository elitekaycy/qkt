package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileTicketScopeMarkerTest {
    private val symbol = "EXNESS:XAUUSD"
    private val scope = ReconcileTicketScope(TicketAttribution())

    private fun ticket(
        id: String,
        price: String,
        comment: String? = null,
        clientOrderId: String? = null,
    ) = BrokerPositionTicket(
        ticket = id,
        symbol = symbol,
        side = Side.BUY,
        qty = BigDecimal("0.10"),
        entryPrice = BigDecimal(price),
        currentPrice = null,
        profit = null,
        swap = null,
        openedAt = null,
        comment = comment,
        clientOrderId = clientOrderId,
    )

    @Test
    fun `a position no ticket matches on price breaks the match`() {
        val positions = mapOf(symbol to listOf(Position(symbol, BigDecimal("0.10"), BigDecimal("4000"))))

        assertThat(scope.ticketSnapshotMatches(positions, listOf(ticket("1", "4001")))).isFalse()
    }

    @Test
    fun `an oco comment carries the order marker after its group`() {
        assertThat(
            ReconcileTicketScope.qktOrderMarker(ticket("1", "4000", "oco:g7/dsl-gold-3")),
        ).isEqualTo("dsl-gold-3")
        assertThat(scope.isPotentiallyOwnedBy(ticket("1", "4000", "oco:g7/dsl-gold-3"), "gold")).isTrue()
        assertThat(scope.isPotentiallyOwnedBy(ticket("2", "4000", "oco:g7/dsl-silver-3"), "gold")).isFalse()
    }

    @Test
    fun `an oco comment with no order after its group names no strategy`() {
        assertThat(ReconcileTicketScope.qktOrderMarker(ticket("1", "4000", "oco:g7"))).isNull()
        assertThat(scope.isPotentiallyOwnedBy(ticket("1", "4000", "oco:g7"), "gold")).isTrue()
    }

    @Test
    fun `the client order id is read before the comment`() {
        val both = ticket("1", "4000", comment = "dsl-silver-1", clientOrderId = "dsl-gold-1")

        assertThat(ReconcileTicketScope.qktOrderMarker(both)).isEqualTo("dsl-gold-1")
        assertThat(
            ReconcileTicketScope.qktOrderMarker(ticket("2", "4000", comment = " ", clientOrderId = "manual")),
        ).isNull()
    }
}
