package com.qkt.persistence

import com.qkt.positions.LegBook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegBookReconcilerTest : LegBookReconcilerFixture() {
    @Test
    fun `no broker, no persisted -- NothingPersisted`() {
        val persistor = NoopStatePersistor()
        val r = LegBookReconciler(persistor)
        assertThat(r.reconcile("hedge", "XAUUSDm", emptyList())).isEqualTo(LegBookReconciler.Outcome.NothingPersisted)
    }

    @Test
    fun `empty persisted book and no broker positions -- NothingPersisted without a wipe (#1103)`() {
        val inner = NoopStatePersistor()
        val persistor =
            object : StatePersistor by inner {
                var saves = 0

                override fun saveLegBook(
                    strategyId: String,
                    symbol: String,
                    legBook: LegBook,
                ) {
                    saves++
                    inner.saveLegBook(strategyId, symbol, legBook)
                }
            }
        persistor.saveLegBook("hedge", "XAUUSDm", LegBook("XAUUSDm"))
        val r = LegBookReconciler(persistor)
        assertThat(r.reconcile("hedge", "XAUUSDm", emptyList())).isEqualTo(LegBookReconciler.Outcome.NothingPersisted)
        // the seed save only: an already-empty book is not rewritten on every restart
        assertThat(persistor.saves).isEqualTo(1)
    }

    @Test
    fun `persisted but no broker -- wipe and retire every leg`() {
        val persistor = NoopStatePersistor()
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary()))
        val r = LegBookReconciler(persistor)
        val outcome = r.reconcile("hedge", "XAUUSDm", emptyList())
        // The legs closed while the daemon was down: they come back as retired so the caller
        // books their venue-realized result and resets rule edges, exactly like a partial
        // downtime close does.
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Attached::class.java)
        val attached = outcome as LegBookReconciler.Outcome.Attached
        assertThat(attached.legBook.legs).isEmpty()
        assertThat(attached.retired.map { it.legId }).containsExactly(primary().legId)
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
    fun `unmatched persisted leg triggers Mismatch`() {
        val persistor = NoopStatePersistor()
        // persisted has primary + stack but broker only reports primary
        persistor.saveLegBook("hedge", "XAUUSDm", book(primary(), stack()))
        val r = LegBookReconciler(persistor)
        val outcome = r.reconcile("hedge", "XAUUSDm", listOf(pos(signedQty = "0.20", entry = "4700")))
        assertThat(outcome).isInstanceOf(LegBookReconciler.Outcome.Mismatch::class.java)
    }
}
