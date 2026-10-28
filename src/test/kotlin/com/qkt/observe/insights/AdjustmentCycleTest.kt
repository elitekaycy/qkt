package com.qkt.observe.insights

import com.qkt.broker.AdjustmentKind
import com.qkt.broker.AdjustmentSource
import com.qkt.broker.BrokerAdjustment
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A position's dividend reaches Insights as its strategy's, once; a deposit is the account's and is not sent. */
class AdjustmentCycleTest {
    private fun adj(
        ticket: String,
        kind: AdjustmentKind,
        amount: String,
        ts: Long,
        symbol: String? = "EXNESS:AAPL",
        position: String? = "900",
    ) = BrokerAdjustment("EXNESS", ticket, kind, BigDecimal(amount), symbol, position, null, "c", ts)

    private class Venue(
        var booked: List<BrokerAdjustment>,
    ) : AdjustmentSource {
        var reads = 0
        var fail = false

        override fun adjustments(
            from: Long,
            to: Long,
        ): List<BrokerAdjustment> {
            reads++
            if (fail) error("gateway down")
            return booked.filter { it.ts in from..to }
        }
    }

    @Test
    fun `a dividend is offered once as an ADJUSTMENT deal of its position's strategy, a deposit never`() {
        val offered = mutableListOf<InsightsEnvelope>()
        val cycle = AdjustmentCycle(offered::add, backfillMs = 86_400_000L, overlapMs = 60_000L, intervalMs = 600_000L)
        val venue =
            Venue(
                listOf(
                    adj("D1", AdjustmentKind.DIVIDEND, "0.27", 1_000_000L),
                    adj("B1", AdjustmentKind.BALANCE, "1000", 1_000_000L, symbol = null, position = null),
                ),
            )
        cycle.poll(venue, "acct", 2_000_000L, "USD") { "stock_cfd" to "position" }
        cycle.poll(venue, "acct", 2_100_000L, "USD") { "stock_cfd" to "position" } // not due yet
        cycle.poll(venue, "acct", 2_700_000L, "USD") { "stock_cfd" to "position" } // due: nothing new to send

        assertThat(venue.reads).isEqualTo(2)
        assertThat(offered).hasSize(1)
        val e = offered.single()
        assertThat(e.type).isEqualTo("broker.deal")
        assertThat(e.strategyId).isEqualTo("stock_cfd")
        assertThat(e.payload["entry"]).isEqualTo("ADJUSTMENT")
        assertThat(e.payload["adjustment"]).isEqualTo("DIVIDEND")
        assertThat(e.payload["profit"] as BigDecimal).isEqualByComparingTo("0.27")
        assertThat(e.payload["qty"]).isEqualTo(0)
        assertThat(e.payload).doesNotContainKey("side")
    }

    @Test
    fun `a failed read is retried next cycle and loses nothing`() {
        val offered = mutableListOf<InsightsEnvelope>()
        val cycle = AdjustmentCycle(offered::add, backfillMs = 86_400_000L, overlapMs = 60_000L, intervalMs = 600_000L)
        val venue = Venue(listOf(adj("C1", AdjustmentKind.CHARGE, "-1.5", 1_000_000L))).apply { fail = true }
        cycle.poll(venue, "acct", 2_000_000L, "USD") { null to "none" }
        venue.fail = false
        cycle.poll(venue, "acct", 2_000_001L, "USD") { null to "none" }
        assertThat(offered.map { it.payload["dealTicket"] }).containsExactly("C1")
    }
}
