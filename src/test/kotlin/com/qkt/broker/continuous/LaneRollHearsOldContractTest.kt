package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.derivatives.futures.RollQuoteTaps
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A daemon down across the roll instant restarts on a stream that serves only December (63,010 on the stream is
 * December 63,810 under the measured September 63,000 -> December 63,800). A short's closing leg buys September, so the
 * lane has the feed read September again ([RollQuoteTaps]) and collars that leg around September's own quote; the
 * estimate from December's quote is only for a September that says nothing.
 */
class LaneRollHearsOldContractTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    private fun sepQuote(
        g: ScriptedLaneFixture,
        price: String,
    ) = Tick(g.sep, BigDecimal(price), g.clock.time, bid = BigDecimal(price) - BigDecimal.TEN, ask = BigDecimal(price))

    @Test
    fun `a restart past the roll instant collars the closing leg around the old contract's heard quote`() {
        f.hold(Side.SELL)
        val taps = RollQuoteTaps()

        val g = f.restart(atMs = f.roll + 20_000, taps = taps)
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()

        // The roll needs September's quotes: the feed is told to read September, and no leg goes before one is heard.
        assertThat(taps.held(g.front)).isEqualTo(g.sep)
        assertThat(g.attempts("close")).isEmpty()
        taps.hear(g.front, sepQuote(g, "63500"))
        g.nextRetry()

        // BUY at 1% over September's own 63,500, not over the December estimate 63,810 (64,448.1).
        assertThat(g.limit(":close").limitPrice).isEqualByComparingTo("64135")
        g.last(g.leg(":close").id, g.sep, Side.BUY, "0.010", "63500")
        g.last(g.leg(":open").id, g.dec, Side.SELL, "0.010", "63800")
        assertThat(g.stop()).isNull()
        assertThat(g.heldOn()).isEqualTo(g.dec)
        assertThat(taps.held(g.front)).isNull()
    }

    @Test
    fun `an old contract that stays silent leaves the closing leg on the estimate from the new contract's quote`() {
        f.hold(Side.SELL)
        val taps = RollQuoteTaps()

        val g = f.restart(atMs = f.roll + 20_000, taps = taps)
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.ready()
        g.nextRetry()

        // No September quote came: BUY at 1% over the higher of December's latest 63,800 and that less the measured
        // spread (63,000).
        assertThat(g.limit(":close").limitPrice).isEqualByComparingTo("64438")
    }

    @Test
    fun `a roll that starts live holds the old contract while its legs are out and lets it go once carried`() {
        val taps = RollQuoteTaps()
        val live = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"), taps = taps)
        live.hold(Side.SELL)
        assertThat(taps.held(live.front)).isNull()

        live.rolled()
        assertThat(taps.held(live.front)).isEqualTo(live.sep)
        // The roll heard September before it: its leg goes at once, around that quote (63,010 at 07:45).
        assertThat(live.limit(":close").limitPrice).isEqualByComparingTo("63640.1")
        live.last(live.leg(":close").id, live.sep, Side.BUY, "0.010", "63010")
        live.last(live.leg(":open").id, live.dec, Side.SELL, "0.010", "63790")
        assertThat(taps.held(live.front)).isNull()
    }
}
