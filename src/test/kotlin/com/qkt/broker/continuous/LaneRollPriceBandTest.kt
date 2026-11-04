package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A roll's legs meet whatever rests on the book. On the bot2 soak a roll's BUY filled at 240,517.50 against a
 * market near 78,000: a live roll now compares the side each leg would hit with the contract's measured roll price
 * (63000 out of September, 63800 into December here) and waits, bounded, instead of sending into a far quote.
 */
class LaneRollPriceBandTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    private fun hold(side: Side) {
        f.broker.submit(
            OrderRequest.Market("entry", f.front, side, BigDecimal("0.010"), TimeInForce.GTC, f.clock.time, "s"),
        )
        f.last("entry", f.sep, side, "0.010", "63000")
    }

    private fun rolled(
        bid: String?,
        ask: String?,
    ) {
        f.clock.time = f.roll
        f.quote("63000", bid, ask)
    }

    private fun legs() = f.venue.sent.filter { it.id.startsWith("roll:") }

    @Test
    fun `a far ask on the old contract, its quote gone stale, sends a collared close and not a stop`() {
        hold(Side.SELL)
        f.quote("63010", "63000", "150000")

        rolled("62990", "63010")
        assertThat(legs()).isEmpty()
        assertThat(f.strategy().stopped).isNull()
        f.nextRetry()

        // No September quote stands: the BUY collars 1% over December's 63,800, above September's measured 63,000.
        assertThat((f.leg(":close") as OrderRequest.Limit).limitPrice).isEqualByComparingTo("64438")
    }

    @Test
    fun `a far ask on the new contract that clears in time sends the roll, with no stop`() {
        hold(Side.BUY)
        f.quote("63010", "63000", "63020")
        rolled("62990", "150000")
        assertThat(legs()).isEmpty()

        f.nextRetry()

        assertThat(f.leg(":close").side).isEqualTo(Side.SELL)
        assertThat(f.strategy().stopped).isNull()
    }

    @Test
    fun `a far ask on the new contract that stays stops a long before its close is sent`() {
        hold(Side.BUY)
        f.quote("63010", "63000", "63020")
        rolled("62990", "150000")

        repeat(4) {
            f.clock.time += 15_000
            f.quote("63000", "62990", "150000")
        }

        assertThat(legs()).isEmpty()
        assertThat(f.strategy().stopped).contains("ask").contains(f.dec)
        assertThat(f.heldOn()).isEqualTo(f.sep)
        assertThat(f.saved().roll).isNull()
    }

    @Test
    fun `a far ask on the side no leg hits changes nothing`() {
        hold(Side.BUY)
        f.quote("63010", "63000", "150000")

        rolled("62990", "63010")

        assertThat(f.leg(":close").side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a normal book rolls as before`() {
        hold(Side.SELL)
        f.quote("63010", "63000", "63020")

        rolled("62990", "63010")

        assertThat(f.leg(":close").side).isEqualTo(Side.BUY)
        assertThat(f.strategy().stopped).isNull()
    }

    @Test
    fun `a feed with no book sides is judged by its price`() {
        hold(Side.SELL)
        f.quote("63010", null, null)

        rolled(null, null)

        assertThat(f.leg(":close").side).isEqualTo(Side.BUY)
    }

    @Test
    fun `with no band, as in a backtest, the far quote is not judged`() {
        val g = ScriptedLaneFixture()
        g.broker.submit(
            OrderRequest.Market("entry", g.front, Side.SELL, BigDecimal("0.010"), TimeInForce.GTC, g.clock.time, "s"),
        )
        g.last("entry", g.sep, Side.SELL, "0.010", "63000")
        g.quote("63010", "63000", "150000")

        g.clock.time = g.roll
        g.quote("63000", "62990", "63010")

        assertThat(g.leg(":close").side).isEqualTo(Side.BUY)
    }

    private fun ScriptedLaneFixture.strategy() = saved().strategies.single()
}
