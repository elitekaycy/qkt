package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileTicketScopeTest {
    private val symbol = "EXNESS:XAUUSD"

    private fun ticket(
        id: String,
        side: Side,
        qty: String,
        price: String,
        comment: String? = null,
    ) = BrokerPositionTicket(
        ticket = id,
        symbol = symbol,
        side = side,
        qty = BigDecimal(qty),
        entryPrice = BigDecimal(price),
        currentPrice = null,
        profit = null,
        swap = null,
        openedAt = null,
        comment = comment,
    )

    @Test
    fun `hedged tickets match the position read regardless of order and scale`() {
        val scope = ReconcileTicketScope(TicketAttribution())
        val positions =
            mapOf(
                symbol to
                    listOf(
                        Position(symbol, BigDecimal("-0.10"), BigDecimal("4000.0")),
                        Position(symbol, BigDecimal("0.2"), BigDecimal("3990")),
                    ),
            )
        val tickets = listOf(ticket("1", Side.BUY, "0.20", "3990.00"), ticket("2", Side.SELL, "0.10", "4000"))

        assertThat(scope.ticketSnapshotMatches(positions, tickets)).isTrue()
    }

    @Test
    fun `a ticket the position read does not show breaks the match`() {
        val scope = ReconcileTicketScope(TicketAttribution())
        val positions = mapOf(symbol to listOf(Position(symbol, BigDecimal("0.10"), BigDecimal("4000"))))
        val tickets = listOf(ticket("1", Side.BUY, "0.10", "4000"), ticket("2", Side.BUY, "0.10", "4000"))

        assertThat(scope.ticketSnapshotMatches(positions, tickets)).isFalse()
    }

    @Test
    fun `a recorded owner wins over the comment and an unmarked ticket stays in scope`() {
        val attribution = TicketAttribution().also { it.record("1", "other") }
        val scope = ReconcileTicketScope(attribution)

        assertThat(scope.isPotentiallyOwnedBy(ticket("1", Side.BUY, "0.10", "4000", "dsl-gold"), "gold")).isFalse()
        assertThat(scope.isPotentiallyOwnedBy(ticket("2", Side.BUY, "0.10", "4000", "dsl-gold"), "gold")).isTrue()
        assertThat(scope.isPotentiallyOwnedBy(ticket("3", Side.BUY, "0.10", "4000", "manual"), "gold")).isTrue()
        assertThat(scope.isPotentiallyOwnedBy(ticket("4", Side.BUY, "0.10", "4000", "dsl-silver"), "gold")).isFalse()
    }
}
