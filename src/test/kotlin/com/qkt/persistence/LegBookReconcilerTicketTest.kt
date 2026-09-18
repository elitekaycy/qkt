package com.qkt.persistence

import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegBookReconcilerTicketTest : LegBookReconcilerFixture() {
    @Test
    fun `leg whose ticket the venue no longer reports is retired, the rest attach`() {
        // #1079: one leg's position closed (SL/TP) while the daemon was down. The venue says
        // so directly -- its ticket is absent -- so this is a completed lifecycle, not drift.
        val persistor = NoopStatePersistor()
        persistor.saveLegBook(
            "hedge",
            "XAUUSDm",
            book(
                ticketed("closed-while-down", ticket = "111", qty = "0.58"),
                ticketed("still-open", ticket = "222", qty = "0.51", side = Side.SELL),
            ),
        )
        val r = LegBookReconciler(persistor)

        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "-0.51")), venueTickets = setOf("222"))

        val attached = outcome as LegBookReconciler.Outcome.Attached
        assertThat(attached.retired.map { it.legId }).containsExactly("closed-while-down")
        assertThat(attached.legBook.all().map { it.legId }).containsExactly("still-open")
        // The retired leg is gone from disk too, so a second restart does not re-retire it.
        assertThat(persistor.loadLegBook("hedge", "XAUUSDm")!!.legs.map { it.legId }).containsExactly("still-open")
    }

    @Test
    fun `unmatched leg stays a mismatch when the venue exposes no tickets`() {
        // Without venue tickets the engine cannot tell "closed while down" from drift: keep failing closed.
        val persistor = NoopStatePersistor()
        persistor.saveLegBook(
            "hedge",
            "XAUUSDm",
            book(
                ticketed("a", ticket = "111", qty = "0.58"),
                ticketed("b", ticket = "222", qty = "0.51", side = Side.SELL),
            ),
        )
        val r = LegBookReconciler(persistor)

        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "-0.51")), venueTickets = null)

        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }

    @Test
    fun `unmatched ticketless leg stays a mismatch even with venue tickets`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook(
            "hedge",
            "XAUUSDm",
            book(
                primary(id = "no-ticket", qty = "0.58"),
                ticketed("b", ticket = "222", qty = "0.51", side = Side.SELL),
            ),
        )
        val r = LegBookReconciler(persistor)

        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "-0.51")), venueTickets = setOf("222"))

        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }
}
