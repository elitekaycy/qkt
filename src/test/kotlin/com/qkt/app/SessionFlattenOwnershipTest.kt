package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Side
import com.qkt.observe.insights.TicketAttribution
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which venue tickets a session's flatten closes, leaves to a sibling session, or reports to the
 * operator. Rehearsal 2026-11-03: a book child and a standalone on one magic each logged the
 * other's ticket as "unattributed; operator intervention required" during a hard-limit flatten.
 */
class SessionFlattenOwnershipTest {
    private fun ticket(
        id: String,
        comment: String?,
    ) = BrokerPositionTicket(
        ticket = id,
        symbol = "EXNESS:BTCUSD",
        side = Side.BUY,
        qty = BigDecimal("0.01"),
        entryPrice = BigDecimal("77000"),
        currentPrice = null,
        profit = null,
        swap = null,
        openedAt = null,
        comment = comment,
    )

    private val attribution = TicketAttribution().apply { alias("atto_mix_a", "atto_mix:a") }

    @Test
    fun `the session's own ticket is closed`() {
        val own = ticket("3426735963", "dsl-atto_mix_a--0")

        assertThat(SessionFlatten.ownership(own, "atto_mix:a", listOf("atto_mix:a"), attribution))
            .isEqualTo(SessionFlatten.TicketOwnership.OWN)
    }

    @Test
    fun `a sibling session's qkt ticket is left to its owner, not reported`() {
        val sibling = ticket("3426736006", "dsl-atto_mix_solo--0")

        assertThat(SessionFlatten.ownership(sibling, "atto_mix:a", listOf("atto_mix:a"), attribution))
            .isEqualTo(SessionFlatten.TicketOwnership.OTHER_STRATEGY)
    }

    @Test
    fun `a ticket recorded for another strategy is left to it`() {
        attribution.record("55", "other")

        assertThat(SessionFlatten.ownership(ticket("55", null), "atto_mix:a", listOf("atto_mix:a"), attribution))
            .isEqualTo(SessionFlatten.TicketOwnership.OTHER_STRATEGY)
    }

    @Test
    fun `a ticket no qkt order placed still needs the operator`() {
        val manual = ticket("77", "")

        assertThat(SessionFlatten.ownership(manual, "atto_mix:a", listOf("atto_mix:a"), attribution))
            .isEqualTo(SessionFlatten.TicketOwnership.UNKNOWN)
    }
}
