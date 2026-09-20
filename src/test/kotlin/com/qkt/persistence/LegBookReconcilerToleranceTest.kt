package com.qkt.persistence

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegBookReconcilerToleranceTest : LegBookReconcilerFixture() {
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
}
