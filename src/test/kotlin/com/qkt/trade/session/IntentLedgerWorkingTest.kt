package com.qkt.trade.session

import com.qkt.common.Side
import com.qkt.strategy.OrderOutcome
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `/orders` and `finish` list the entries an intent placed that are still working. A bracket is placed as a group
 * (`dsl-bot0--1`) whose entry (`dsl-bot0--0`) fills; the group itself never gets an outcome, so it stayed listed as
 * working after a flattened session (an agent read it as an order still open, 2026-10-20).
 */
class IntentLedgerWorkingTest {
    private fun filled(
        orderId: String,
        parent: String?,
    ) = OrderOutcome.Filled(
        orderId,
        "BINANCE:BTCUSDT",
        Side.SELL,
        BigDecimal("78441.65"),
        BigDecimal("0.1"),
        partial = false,
        exitReason = null,
        timestamp = 1L,
        parentOrderId = parent,
    )

    @Test
    fun `a bracket whose entry filled is no longer a working entry`() {
        val ledger = IntentLedger()
        ledger.fate(IntentFate("int-1", "agent", "fired", emptyList(), null, 1L))
        ledger.link("int-1", "dsl-bot0--1")
        ledger.link("int-1", "dsl-bot0--0")

        ledger.outcome("agent", filled("dsl-bot0--0", parent = "dsl-bot0--1"))

        assertThat(ledger.working()).isEmpty()
    }

    @Test
    fun `a bracket whose entry has not filled is still working`() {
        val ledger = IntentLedger()
        ledger.fate(IntentFate("int-1", "agent", "fired", emptyList(), null, 1L))
        ledger.link("int-1", "dsl-bot0--1")
        ledger.link("int-1", "dsl-bot0--0")

        assertThat(ledger.working().map { it.orderId }).contains("dsl-bot0--1")
    }

    @Test
    fun `a bracket that was rejected is not working either`() {
        // Live on the Exness demo: a 1-lot bracket over the size cap was rejected on its group id, and its entry
        // id stayed listed as working in the finish report.
        val ledger = IntentLedger()
        ledger.fate(IntentFate("int-2", "agent", "fired", emptyList(), null, 1L))
        ledger.link("int-2", "dsl-bot0--3")
        ledger.link("int-2", "dsl-bot0--2")

        ledger.outcome("agent", OrderOutcome.Rejected("dsl-bot0--3", "order qty 1 exceeds per-order cap 0.05", 2L))

        assertThat(ledger.working()).isEmpty()
    }
}
