package com.qkt.broker.continuous

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A daemon down across the roll instant starts the roll on its first tick after the restart. The stream it hears is
 * the new contract's, in the adjusted series: 63,010 on the stream is December 63,810 under the measured
 * September 63,000 -> December 63,800. Here the measured spread is wrong and September trades with December, about
 * 63,800. A short's closing leg buys September, so it must be collared around a September price the book can meet,
 * however the restart fell against the roll.
 */
class LaneRollRestartPastRollTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    @Test
    fun `a roll started after a restart past its instant collars the closing leg around a price the book can meet`() {
        f.hold(Side.SELL)

        // The session's first tick (63,010 on the stream) is stamped after the roll: no September quote is heard.
        val g = f.restart(atMs = f.roll + 20_000)
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()

        // BUY at 1% over December 63,810, not over 63,810 - 800 = 63,010 (63,640.1), which a September ask of
        // 63,810 never meets.
        assertThat(g.limit(":close").limitPrice).isEqualByComparingTo("64448.1")
        g.last(g.leg(":close").id, g.sep, Side.BUY, "0.010", "63810")
        g.last(g.leg(":open").id, g.dec, Side.SELL, "0.010", "63800")
        assertThat(g.stop()).isNull()
        assertThat(g.heldOn()).isEqualTo(g.dec)
    }

    @Test
    fun `a roll that heard the old contract before the restart's first roll tick collars around that quote`() {
        f.hold(Side.SELL)

        // The session first hears September stamped before the roll, then December after it.
        val g = f.restart(atMs = f.roll - 3_000)
        g.quote("63810", "63800", "63810")
        g.clock.time = f.roll + 20_000
        g.tick("63010")
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()

        // BUY at 1% over September's own 63,810.
        assertThat(g.limit(":close").limitPrice).isEqualByComparingTo("64448.1")
    }
}
