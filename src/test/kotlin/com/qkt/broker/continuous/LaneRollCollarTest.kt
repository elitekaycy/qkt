package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * On the soak a roll's legs reached the venue 11 and 14 s after the roll's check and filled at 233,605 and 38,940 with
 * the market near 78,000. A live roll's legs are now limits at the band from the contract's measured roll price (63000
 * out of September, 63800 into December here), immediate-or-cancel, so the venue itself cannot fill beyond it.
 */
class LaneRollCollarTest {
    private val band = BigDecimal("0.25")
    private val f = ScriptedLaneFixture(rollPriceBand = band)

    private fun ScriptedLaneFixture.closes() =
        engine.filterIsInstance<BrokerEvent.OrderFilled>().filter { it.exitReason == ExitReason.ROLL_FAILED }

    @Test
    fun `a short's legs are collared limits, immediate-or-cancel, and a normal book carries it`() {
        f.hold(Side.SELL)
        f.rolled()

        val close = f.limit(":close")
        assertThat(close.side).isEqualTo(Side.BUY)
        assertThat(close.limitPrice).isEqualByComparingTo("78750")
        assertThat(close.timeInForce).isEqualTo(TimeInForce.IOC)
        f.last(close.id, f.sep, Side.BUY, "0.010", "63010")

        val open = f.limit(":open")
        assertThat(open.side).isEqualTo(Side.SELL)
        assertThat(open.limitPrice).isEqualByComparingTo("47850")
        assertThat(open.timeInForce).isEqualTo(TimeInForce.IOC)
        f.last(open.id, f.dec, Side.SELL, "0.010", "63790")

        assertThat(f.stop()).isNull()
        assertThat(f.saved().roll).isNull()
        assertThat(f.heldOn()).isEqualTo(f.dec)
        val entry = f.ledger.entries.single()
        assertThat(entry.toFill).isEqualByComparingTo("63790")
    }

    @Test
    fun `a long's legs collar the other way`() {
        f.hold(Side.BUY)
        f.rolled()

        assertThat(f.limit(":close").limitPrice).isEqualByComparingTo("47250")
        f.last(f.leg(":close").id, f.sep, Side.SELL, "0.010", "62990")
        assertThat(f.limit(":open").limitPrice).isEqualByComparingTo("79750")
    }

    @Test
    fun `a far book between check and send, the closing leg ends unfilled and the strategy stops on its contract`() {
        f.hold(Side.SELL)
        f.rolled()

        f.exhaust("close")

        assertThat(f.venue.sent.filter { it.id.endsWith(":open") }).isEmpty()
        assertThat(f.stop()).contains("cancelled by the venue")
        assertThat(f.saved().roll).isNull()
        assertThat(f.heldOn()).isEqualTo(f.sep)
        assertThat(f.closes()).isEmpty()
    }

    @Test
    fun `a far book at the opening leg ends it unfilled and the position closes on the stream at the old leg's fill`() {
        f.hold(Side.SELL)
        f.rolled()
        f.last(f.leg(":close").id, f.sep, Side.BUY, "0.010", "63010")

        f.exhaust("open")

        assertThat(f.stop()).contains("cancelled by the venue")
        assertThat(f.saved().holdings).isEmpty()
        assertThat(f.closes().single().quantity).isEqualByComparingTo("0.010")
    }

    @Test
    fun `a closing leg part filled closes that part and stops, the rest staying on its contract`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id

        f.slice(close, f.sep, Side.BUY, "0.004", "0.004", "63010")
        f.exhaust("close")

        assertThat(f.stop()).isNotNull()
        assertThat(f.closes().single().quantity).isEqualByComparingTo("0.004")
        assertThat(f.heldOn()).isEqualTo(f.sep)
        assertThat(f.heldQuantity()).isEqualByComparingTo("-0.006")
    }

    @Test
    fun `an opening leg part filled is unwound by a limit collared around the new contract's price`() {
        f.hold(Side.SELL)
        f.rolled()
        f.last(f.leg(":close").id, f.sep, Side.BUY, "0.010", "63010")
        val open = f.leg(":open").id

        f.slice(open, f.dec, Side.SELL, "0.004", "0.004", "63790")
        f.exhaust("open")

        val unwind = f.limit(":unwind")
        assertThat(unwind.side).isEqualTo(Side.BUY)
        assertThat(unwind.quantity).isEqualByComparingTo("0.004")
        assertThat(unwind.limitPrice).isEqualByComparingTo("79750")
        assertThat(unwind.timeInForce).isEqualTo(TimeInForce.IOC)
        f.last(unwind.id, f.dec, Side.BUY, "0.004", "63800")

        assertThat(f.saved().holdings).isEmpty()
        assertThat(f.saved().legs).isEmpty()
    }

    @Test
    fun `a collared unwind that does not fill leaves what it held booked, and the stop cannot be released`() {
        f.partOpened()

        f.exhaust("unwind")

        assertThat(f.saved().legs).isEmpty()
        assertThat(f.heldOn()).isEqualTo(f.dec)
        assertThat(f.heldQuantity()).isEqualByComparingTo("-0.004")
        assertThat(f.stop()).contains("0.004 opened on ${f.dec}")
        val release = f.broker.releaseStreamStops("s").single()
        assertThat(release.cleared).isFalse()
        assertThat(release.stands).contains("the venue holds -0.004 of ${f.dec}")
    }

    @Test
    fun `with no band, as in a backtest, every leg stays a market order, the unwind too`() {
        val g = ScriptedLaneFixture()
        g.hold(Side.SELL)
        g.rolled()
        val close = g.leg(":close")
        assertThat(close).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(close.timeInForce).isEqualTo(TimeInForce.GTC)
        g.last(close.id, g.sep, Side.BUY, "0.010", "63010")
        val open = g.leg(":open")
        assertThat(open).isInstanceOf(OrderRequest.Market::class.java)
        g.slice(open.id, g.dec, Side.SELL, "0.004", "0.004", "63790")
        g.cancelled(open.id)

        assertThat(g.leg(":unwind")).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(g.leg(":unwind").timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `a tick with both sides dropped passes on its price, and only ever sends a collared leg`() {
        f.hold(Side.SELL)
        f.quote("63010", null, null)
        f.clock.time = f.roll

        f.quote("63000", null, null)

        assertThat(f.limit(":close").limitPrice).isEqualByComparingTo("78750")
        f.exhaust("close")
        assertThat(f.stop()).isNotNull()
        assertThat(f.heldOn()).isEqualTo(f.sep)
    }

    @Test
    fun `a collar stays inside the band when snapped to the contract's tick`() {
        val tick = BigDecimal("0.1")

        assertThat(collarPrice(Side.BUY, BigDecimal("63000.03"), band, tick)).isEqualByComparingTo("78750.0")
        assertThat(collarPrice(Side.SELL, BigDecimal("63000.03"), band, tick)).isEqualByComparingTo("47250.1")
    }
}
