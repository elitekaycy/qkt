package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A roll's market legs hit whatever rests on the book. On the bot2 soak a roll's BUY filled at 240,517.50 against a
 * market near 78,000: a live roll now compares the side each leg would hit with the contract's measured roll price
 * (63000 out of September, 63800 into December here) and stops the strategy on the stream instead of sending.
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
    fun `a far ask on the old contract stops a short with no leg sent, and settlement closes its position`() {
        hold(Side.SELL)
        f.quote("63010", "63000", "150000")

        rolled("62990", "63010")

        assertThat(legs()).isEmpty()
        val saved = f.saved()
        assertThat(saved.strategies.single().stopped).contains("ask 150000").contains(f.sep)
        assertThat(saved.holdings.single().contract).isEqualTo(f.sep)
        assertThat(saved.roll).isNull()

        f.venueBus.publish(ContractSettled(f.sep, BigDecimal("63500"), timestamp = f.clock.time))
        assertThat(f.engine.filterIsInstance<BrokerEvent.OrderFilled>().map { it.side }).contains(Side.BUY)
        assertThat(f.saved().holdings).isEmpty()
    }

    @Test
    fun `an ask the market-data gate dropped from the old contract stops a short too`() {
        hold(Side.SELL)
        f.quote("63010", "63000", null)

        rolled("62990", "63010")

        assertThat(legs()).isEmpty()
        assertThat(f.saved().strategies.single().stopped).contains("no ask")
    }

    @Test
    fun `a far ask on the new contract stops a long before its close is sent`() {
        hold(Side.BUY)
        f.quote("63010", "63000", "63020")

        rolled("62990", "150000")

        assertThat(legs()).isEmpty()
        assertThat(f.saved().strategies.single().stopped).contains("ask").contains(f.dec)
        assertThat(f.saved().holdings.single().contract).isEqualTo(f.sep)
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
        assertThat(f.saved().strategies.single().stopped).isNull()
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
}
