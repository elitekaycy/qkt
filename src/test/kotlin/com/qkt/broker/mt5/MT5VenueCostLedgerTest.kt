package com.qkt.broker.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5VenueCostLedgerTest {
    @Test
    fun `partial then final close books every deal exactly once`() {
        val ledger = MT5VenueCostLedger(closedRetentionMs = 60_000L)
        val entry = deal(ticket = 1L, commission = "-0.70")
        val partial = deal(ticket = 2L, commission = "-0.35", swap = "-0.15")
        val final = deal(ticket = 3L, commission = "-0.35", swap = "-0.25")

        assertThat(ledger.book(42L, listOf(entry, partial), positionClosed = false, nowMs = 1L))
            .isEqualByComparingTo("1.20")
        assertThat(ledger.book(42L, listOf(entry, partial, final), positionClosed = true, nowMs = 2L))
            .isEqualByComparingTo("0.60")
        assertThat(ledger.book(42L, listOf(entry, partial), positionClosed = false, nowMs = 3L))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `final close arriving before delayed partial callback remains idempotent`() {
        val ledger = MT5VenueCostLedger(closedRetentionMs = 60_000L)
        val entry = deal(ticket = 1L, commission = "-0.70")
        val partial = deal(ticket = 2L, commission = "-0.35")
        val final = deal(ticket = 3L, commission = "-0.35")

        assertThat(ledger.book(42L, listOf(entry, partial, final), positionClosed = true, nowMs = 1L))
            .isEqualByComparingTo("1.40")
        assertThat(ledger.book(42L, listOf(entry, partial), positionClosed = false, nowMs = 2L))
            .isEqualByComparingTo("0")
    }

    private fun deal(
        ticket: Long,
        commission: String,
        swap: String = "0",
    ): MT5Deal =
        MT5Deal(
            ticket = ticket,
            orderTicket = ticket,
            positionTicket = 42L,
            symbol = "EURUSD",
            type = 0,
            entry = if (ticket == 1L) 0 else 1,
            volume = BigDecimal("0.10"),
            price = BigDecimal("1.10"),
            profit = BigDecimal.ZERO,
            commission = BigDecimal(commission),
            swap = BigDecimal(swap),
            fee = BigDecimal.ZERO,
            magic = 1,
            comment = null,
            timeMs = ticket,
        )
}
