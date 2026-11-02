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

        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("47850")
    }

    @Test
    fun `a collared leg the venue never received is sent again as saved`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.limit(":close")

        val g = f.restart()
        g.venue.knows = emptySet()
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()

        assertThat(g.venue.sent.single()).isEqualTo(close)
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

        assertThat(g.limit(":open").limitPrice).isEqualByComparingTo("47850")
    }
}
