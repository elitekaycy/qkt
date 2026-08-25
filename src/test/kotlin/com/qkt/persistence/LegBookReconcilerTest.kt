package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegBookReconcilerTest {
    private fun book(vararg legs: PositionLeg): LegBook =
        LegBook(legs.first().symbol).apply { legs.forEach { add(it) } }

    private fun primary(
        id: String = "leg-1",
        symbol: String = "XAUUSDm",
        side: Side = Side.BUY,
        qty: String = "0.20",
        entry: String = "4700",
    ) = PositionLeg(
        legId = id,
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.PRIMARY,
    )

    private fun stack(
        id: String = "leg-2",
        parent: String = "leg-1",
        symbol: String = "XAUUSDm",
        side: Side = Side.BUY,
        qty: String = "0.06",
        entry: String = "4710",
    ) = PositionLeg(
        legId = id,
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.STACK,
        parentLegId = parent,
    )

    private fun pos(
        symbol: String = "XAUUSDm",
        signedQty: String = "0.20",
        entry: String = "4700",
    ) = Position(symbol = symbol, quantity = BigDecimal(signedQty), avgEntryPrice = BigDecimal(entry))

    private fun ticketed(
        id: String,
        ticket: String,
        qty: String = "0.20",
        entry: String = "4700",
        side: Side = Side.BUY,
    ) = PositionLeg(
        legId = id,
        symbol = "XAUUSDm",
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.INDEPENDENT,
        brokerTicket = ticket,
    )

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

    @Test
    fun `no broker, no persisted -- NothingPersisted`() {
        val persistor = NoopStatePersistor()
        val r = LegBookReconciler(persistor)
        assertThat(r.reconcile("hedge", "XAUUSDm", emptyList())).isEqualTo(LegBookReconciler.Outcome.NothingPersisted)
    }

    @Test
    fun `persisted but no broker -- wipe and NothingPersisted`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary()))
        val r = LegBookReconciler(persistor)
        val outcome = r.reconcile("hedge", "XAUUSDm", emptyList())
        assertThat(outcome).isEqualTo(LegBookReconciler.Outcome.NothingPersisted)
        // persisted state should be empty after the wipe
        val after = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(after?.legs).isEmpty()
    }

    @Test
    fun `broker but no persisted -- Mismatch`() {
        val persistor = NoopStatePersistor()
        val r = LegBookReconciler(persistor)
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos()))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }

    @Test
    fun `broker matches persisted -- Attached with leg metadata`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(), stack()))
        val r = LegBookReconciler(persistor)
        val outcome =
            r.reconcile(
                "hedge",
                "XAUUSDm",
                listOf(pos(signedQty = "0.20", entry = "4700"), pos(signedQty = "0.06", entry = "4710")),
            )
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Attached::class.java)
        val attached = (outcome as LegBookReconciler.Outcome.Attached).legBook
        assertThat(attached.all()).hasSize(2)
        assertThat(attached.primary()?.legId).isEqualTo("leg-1")
        assertThat(attached.stacks().single().parentLegId).isEqualTo("leg-1")
    }

    @Test
    fun `quantity within tolerance still matches`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(qty = "0.20")))
        val r = LegBookReconciler(persistor, quantityTolerance = BigDecimal("0.005"))
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "0.199", entry = "4700")))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Attached::class.java)
    }

    @Test
    fun `quantity beyond tolerance triggers Mismatch`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(qty = "0.20")))
        val r = LegBookReconciler(persistor, quantityTolerance = BigDecimal("0.001"))
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "0.15", entry = "4700")))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }

    @Test
    fun `entry price within bps tolerance still matches`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(entry = "4700.0")))
        val r = LegBookReconciler(persistor, priceToleranceFraction = BigDecimal("0.001")) // 10 bps
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "0.20", entry = "4701.0")))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Attached::class.java)
    }

    @Test
    fun `unmatched persisted leg triggers Mismatch`() {
        val persistor = NoopStatePersistor()
        // persisted has primary + stack but broker only reports primary
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(), stack()))
        val r = LegBookReconciler(persistor)
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "0.20", entry = "4700")))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }
}
