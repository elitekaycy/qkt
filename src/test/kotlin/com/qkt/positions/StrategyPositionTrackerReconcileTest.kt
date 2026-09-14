package com.qkt.positions

import com.qkt.common.Side
import com.qkt.persistence.NoopStatePersistor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Venue corrections at startup must confirm or add the venue's positions, never clobber a ticketed book (#1103). */
class StrategyPositionTrackerReconcileTest {
    private val symbol = "EXNESS:XAGUSD"

    private fun leg(
        id: String,
        side: Side,
        qty: String,
        ticket: String?,
        role: LegRole = LegRole.PRIMARY,
    ) = PositionLeg(
        legId = id,
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal("62.8"),
        openedAt = 1L,
        role = role,
        brokerTicket = ticket,
    )

    private fun trackerWith(vararg legs: PositionLeg): StrategyPositionTracker {
        val persistor = NoopStatePersistor()
        if (legs.isNotEmpty()) persistor.saveLegBook("s", symbol, LegBook(symbol).apply { legs.forEach { add(it) } })
        return StrategyPositionTracker(persistor).apply { preloadFromPersistor("s", symbol) }
    }

    @Test
    fun `a position already booked under its ticket is left alone`() {
        val t = trackerWith(leg("dsl-s--1", Side.SELL, "0.01", ticket = "42"))
        val changed =
            t.reconcileNet(
                symbol,
                BigDecimal("-0.01"),
                BigDecimal("62.8"),
                5L,
                "mt5:exness",
                ticket = "42",
                strategyId = "s",
            )
        assertThat(changed).isFalse()
        val legs = t.allLegsFor("s")
        assertThat(legs).hasSize(1)
        assertThat(legs.single().legId).isEqualTo("dsl-s--1")
        assertThat(legs.single().brokerTicket).isEqualTo("42")
    }

    @Test
    fun `a venue position the book does not know is added as a leg carrying its ticket`() {
        val t = trackerWith()
        val changed =
            t.reconcileNet(
                symbol,
                BigDecimal("-0.01"),
                BigDecimal("62.8"),
                5L,
                "mt5:exness",
                ticket = "43",
                strategyId = "s",
            )
        assertThat(changed).isTrue()
        val single = t.allLegsFor("s").single()
        assertThat(single.brokerTicket).isEqualTo("43")
        assertThat(single.side).isEqualTo(Side.SELL)
        assertThat(single.role).isEqualTo(LegRole.PRIMARY)
    }

    @Test
    fun `a second venue position on a hedging account becomes an independent leg beside the primary`() {
        val t = trackerWith(leg("dsl-s--1", Side.BUY, "0.01", ticket = "42"))
        val changed =
            t.reconcileNet(
                symbol,
                BigDecimal("-0.01"),
                BigDecimal("62.9"),
                5L,
                "mt5:exness",
                ticket = "44",
                strategyId = "s",
            )
        assertThat(changed).isTrue()
        val legs = t.allLegsFor("s")
        assertThat(legs).hasSize(2)
        assertThat(legs.map { it.brokerTicket }).containsExactlyInAnyOrder("42", "44")
        assertThat(legs.single { it.brokerTicket == "44" }.role).isEqualTo(LegRole.INDEPENDENT)
    }

    @Test
    fun `an unticketed book that already nets to the venue position is not double-booked`() {
        val t = trackerWith(leg("dsl-s--1", Side.SELL, "0.01", ticket = null))
        val changed =
            t.reconcileNet(
                symbol,
                BigDecimal("-0.01"),
                BigDecimal("62.8"),
                5L,
                "mt5:exness",
                ticket = "42",
                strategyId = "s",
            )
        assertThat(changed).isFalse()
        assertThat(t.allLegsFor("s")).hasSize(1)
    }

    @Test
    fun `without a ticket the net figure still replaces the book`() {
        val t = trackerWith(leg("dsl-s--1", Side.SELL, "0.01", ticket = "42"))
        val changed = t.reconcileNet(symbol, BigDecimal("0.03"), BigDecimal("63.0"), 5L, "BYBIT_LINEAR")
        assertThat(changed).isTrue()
        val single = t.allLegsFor("s").single()
        assertThat(single.side).isEqualTo(Side.BUY)
        assertThat(single.quantity).isEqualByComparingTo("0.03")
        assertThat(single.brokerTicket).isNull()
    }

    @Test
    fun `without an owner the correction is not applied`() {
        val t = trackerWith()
        assertThat(
            t.reconcileNet(symbol, BigDecimal("0.01"), BigDecimal("63.0"), 5L, "mt5:exness", ticket = "45"),
        ).isFalse()
        assertThat(t.allLegsFor("s")).isEmpty()
    }
}
