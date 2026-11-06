package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Deals an older gateway reports without a ticket are told apart by what they are. */
class MT5VenueCostLedgerTicketlessTest {
    private val ledger = MT5VenueCostLedger(closedRetentionMs = 60_000L)

    private fun ticketless(
        order: Long,
        timeMs: Long,
        commission: String,
    ) = MT5Deal(
        ticket = 0L,
        orderTicket = order,
        positionTicket = 500L,
        symbol = "XAUUSDm",
        type = 0,
        entry = 0,
        volume = BigDecimal("0.10"),
        price = BigDecimal("2400"),
        profit = BigDecimal.ZERO,
        commission = BigDecimal(commission),
        swap = BigDecimal.ZERO,
        fee = BigDecimal.ZERO,
        magic = 10001,
        comment = null,
        timeMs = timeMs,
    )

    @Test
    fun `two ticketless deals of different orders are both charged`() {
        val costs =
            ledger.book(
                500L,
                listOf(ticketless(900L, 1_000L, "-0.35"), ticketless(901L, 1_000L, "-0.40")),
                positionClosed = false,
                nowMs = 2_000L,
            )

        assertThat(costs).isEqualByComparingTo("0.75")
    }

    @Test
    fun `the same ticketless deal read twice is charged once`() {
        val deal = ticketless(900L, 1_000L, "-0.35")

        val first = ledger.book(500L, listOf(deal), positionClosed = false, nowMs = 2_000L)
        val again = ledger.book(500L, listOf(deal.copy()), positionClosed = true, nowMs = 3_000L)

        assertThat(first).isEqualByComparingTo("0.35")
        assertThat(again).isEqualByComparingTo("0")
    }
}
