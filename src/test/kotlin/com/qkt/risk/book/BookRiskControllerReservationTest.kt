package com.qkt.risk.book

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reservations close the gap between an approved order and the book sample that will eventually
 * carry it.
 *
 * Without them, every order checked inside one sample window saw the same book exposure, so two
 * children entering at once both passed a cap that admits only one -- measured live on 2026-09-10
 * (three 0.01-lot entries, ~3,239 notional, against a 1,998.53 gross cap, none refused). A
 * reservation must count an approved order exactly once: from approval until a sample that already
 * contains its position, and never twice, and never forever.
 *
 * Every case uses a gross cap of 0.3 x 1,000 = 300, so one 200 entry fits and two do not.
 */
class BookRiskControllerReservationTest {
    private fun controller(limits: BookLimits? = BookLimits(maxGrossExposure = BigDecimal("0.3"))) =
        BookRiskController(config = BookRiskConfig(limits = limits), capital = BigDecimal("1000"))

    private fun sample(
        controller: BookRiskController,
        gross: String,
        startedAt: Long? = null,
    ) {
        val snapshot =
            BookSnapshot(
                timestampMs = 1L,
                bookEquity = BigDecimal("1000"),
                exposure = Exposure(BigDecimal(gross), BigDecimal(gross), mapOf("A" to BigDecimal(gross))),
                perStrategyPnl = emptyMap(),
            )
        if (startedAt == null) controller.onSample(snapshot) else controller.onSample(snapshot, startedAt)
    }

    private fun key(
        strategy: String,
        order: String,
    ) = bookReservationKey(strategy, order)

    @Test
    fun `a second entry inside one sample sees the first entry's reservation`() {
        val c = controller()
        sample(c, "0")
        assertThat(c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))).isNull()
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("200")))
            .contains("book gross exposure 400")
    }

    @Test
    fun `the same order id in two children is two reservations`() {
        // Every child session numbers its own orders from ORD-0, so the key must carry the child.
        val c = controller()
        sample(c, "0")
        assertThat(c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("100"))).isNull()
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("100"))).isNull()
        assertThat(c.pendingReservations()).isEqualTo(2)
    }

    @Test
    fun `a rejected or cancelled order frees its headroom immediately`() {
        val c = controller()
        sample(c, "0")
        c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("200"))).isNotNull()
        c.release(key("book:a", "ORD-0"))
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("200"))).isNull()
    }

    @Test
    fun `a filled reservation is dropped by the first sample that began after the fill`() {
        val c = controller()
        sample(c, "0")
        c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))
        c.markFilled(key("book:a", "ORD-0"))
        sample(c, "200") // this sample began after the fill, so it carries the position
        assertThat(c.pendingReservations()).isZero()
        // Exactly once: 200 in the sample, nothing double-counted, so a 100 entry still fits.
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("100"))).isNull()
    }

    @Test
    fun `a sample that began before the fill keeps the reservation`() {
        // The live supervisor gathers legs, then publishes the sample. A fill landing in between
        // is not in those legs, so dropping its reservation there would leave it counted nowhere.
        val c = controller()
        sample(c, "0")
        c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))
        val startedAt = c.beginSample()
        c.markFilled(key("book:a", "ORD-0"))
        sample(c, "0", startedAt)
        assertThat(c.pendingReservations()).isEqualTo(1)
        assertThat(c.checkAndReserve(key("book:b", "ORD-0"), "B", BigDecimal("200"))).isNotNull()
    }

    @Test
    fun `an order that never resolves stops counting after two samples`() {
        // No reservation may outlive its usefulness: a resting order is not counted today either,
        // and a leaked reservation would slowly block the whole book from trading.
        val c = controller()
        sample(c, "0")
        c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))
        sample(c, "0")
        assertThat(c.pendingReservations()).isEqualTo(1)
        sample(c, "0")
        assertThat(c.pendingReservations()).isZero()
    }

    @Test
    fun `a refused order reserves nothing`() {
        val c = controller()
        sample(c, "250")
        assertThat(c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))).isNotNull()
        assertThat(c.pendingReservations()).isZero()
    }

    @Test
    fun `a book with no limits reserves nothing`() {
        val c = controller(limits = null)
        sample(c, "0")
        assertThat(c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))).isNull()
        assertThat(c.pendingReservations()).isZero()
    }

    @Test
    fun `the published state stays the raw sample`() {
        // Sizing and dashboards read state(); only the pre-trade check adds reservations.
        val c = controller()
        sample(c, "0")
        c.checkAndReserve(key("book:a", "ORD-0"), "A", BigDecimal("200"))
        assertThat(c.state().grossExposure).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
