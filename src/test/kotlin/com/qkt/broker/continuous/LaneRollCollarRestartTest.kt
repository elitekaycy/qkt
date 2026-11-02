package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A restart mid-roll takes its legs back as they were saved, market legs of an older file included. */
class LaneRollCollarRestartTest {
    private val band = BigDecimal("0.25")
    private val f = ScriptedLaneFixture(rollPriceBand = band)

    @Test
    fun `a restart mid-roll restores a collared closing leg, and the roll goes on collared`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        val restored = g.recovered(close.id)
        assertThat(restored).isEqualTo(close)
        g.ready()
        assertThat(g.venue.sent).isEmpty()
        g.last(close.id, g.sep, Side.BUY, "0.010", "63010")

        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("63162")
    }

    @Test
    fun `a collared leg the venue never received goes again under a new id, carrying the position`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.venue.knows = emptySet()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.nextRetry()

        val again = g.venue.sent.single() as OrderRequest.Limit
        assertThat(again.id).isEqualTo("${close.id}-2")
        assertThat(again.limitPrice).isEqualByComparingTo(close.limitPrice)
        g.last(again.id, g.sep, Side.BUY, "0.010", "63010")
        g.last(g.limit(":open").id, g.dec, Side.SELL, "0.010", "63790")
        assertThat(g.stop()).isNull()
        assertThat(g.heldOn()).isEqualTo(g.dec)
    }

    @Test
    fun `a market leg of an older lane the venue never received goes again collared, under a new id`() {
        val before = ScriptedLaneFixture()
        before.hold(Side.SELL)
        before.rolled()
        val close = before.leg(":close")

        val g = ScriptedLaneFixture(before.persistor, before.clock.time, band)
        g.venue.knows = emptySet()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.nextRetry()

        val again = g.venue.sent.single() as OrderRequest.Limit
        assertThat(again.id).isEqualTo("${close.id}-2")
        assertThat(again.limitPrice).isEqualByComparingTo("63630")
    }

    @Test
    fun `a restart while a retry waits asks the venue again and goes on from the next attempt`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id
        f.slice(close, f.sep, Side.BUY, "0.004", "0.004", "63010")
        f.cancelled(close)
        val out = f.saved().legs.single()
        assertThat(out.leg.id).isEqualTo(close)

        val g = f.restart()
        g.venue.onRecover = { g.cancelled(close) }
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        val asked = g.venue.recovered.single { it.id == close }
        assertThat(asked.cumulativeFilledQuantity).isEqualByComparingTo("0.004")
        g.ready()
        g.nextRetry()

        val again = g.venue.sent.single()
        assertThat(again.id).isEqualTo("$close-2")
        assertThat(again.quantity).isEqualByComparingTo("0.006")
        g.last(again.id, g.sep, Side.BUY, "0.006", "63020")
        assertThat(g.leg(":open").quantity).isEqualByComparingTo("0.010")
    }

    @Test
    fun `a restart restores a retry with what earlier attempts filled, and counts it in the carry`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id
        f.slice(close, f.sep, Side.BUY, "0.004", "0.004", "63010")
        f.cancelled(close)
        f.nextRetry()
        val second = f.attempt("close")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        val asked = g.venue.recovered.single()
        assertThat(asked.id to asked.cumulativeFilledQuantity.signum()).isEqualTo(second.id to 0)
        g.ready()
        g.last(second.id, g.sep, Side.BUY, "0.006", "63020")
        g.last(g.leg(":open").id, g.dec, Side.SELL, "0.010", "63790")

        assertThat(g.stop()).isNull()
        val entry = g.ledger.entries.single()
        assertThat(entry.fromFill).isEqualByComparingTo("63016")
    }

    @Test
    fun `a restart restores a collared unwind, whose end still reaches the lane`() {
        f.partOpened()
        val unwind = f.limit(":unwind")

        val g = f.restart()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        assertThat(g.recovered(unwind.id)).isEqualTo(unwind)
        g.last(unwind.id, g.dec, Side.BUY, "0.004", "63800")

        assertThat(g.saved().holdings).isEmpty()
        assertThat(g.saved().legs).isEmpty()
    }

    @Test
    fun `a market leg saved by the previous version is awaited as saved, and the roll's next leg is collared`() {
        val before = ScriptedLaneFixture()
        before.hold(Side.SELL)
        before.rolled()
        val close = before.leg(":close")
        assertThat(close).isInstanceOf(OrderRequest.Market::class.java)

        val g = ScriptedLaneFixture(before.persistor, before.clock.time, band)
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        assertThat(g.recovered(close.id)).isEqualTo(close)
        g.last(close.id, g.sep, Side.BUY, "0.010", "63010")

        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("63162")
    }
}
