package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A live probe saw far touches 0-4.2% of the time on the bid and 1.4-9.3% on the ask, per contract: a collared leg an
 * IOC cancel ended is sent again inside the collar, five attempts about 15 s apart, before the strategy stops.
 */
class LaneRollRetryTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    @Test
    fun `a leg a far touch cancelled goes again under a new id, in the collar, and the roll carries`() {
        f.hold(Side.SELL)
        f.rolled()
        val first = f.leg(":close") as OrderRequest.Limit

        f.cancelled(first.id)
        f.clock.time += 14_999
        f.quote("63000", "62990", "63010")
        assertThat(f.attempts("close")).hasSize(1)
        assertThat(f.stop()).isNull()
        f.clock.time += 1
        f.quote("63000", "62990", "63010")

        val second = f.attempt("close") as OrderRequest.Limit
        assertThat(second.id).isEqualTo("${first.id}-2")
        assertThat(second.limitPrice).isEqualByComparingTo(first.limitPrice)
        assertThat(second.quantity).isEqualByComparingTo("0.010")
        f.last(second.id, f.sep, Side.BUY, "0.010", "63010")
        f.last(f.leg(":open").id, f.dec, Side.SELL, "0.010", "63790")

        assertThat(f.stop()).isNull()
        assertThat(f.heldOn()).isEqualTo(f.dec)
        assertThat(f.engine.filterIsInstance<BrokerEvent.OrderFilled>().filter { it.clientOrderId.startsWith("roll:") })
            .isEmpty()
    }

    @Test
    fun `after a part fill only the remainder goes again, and the fills count as one at their weighted price`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id
        f.slice(close, f.sep, Side.BUY, "0.004", "0.004", "63010")
        f.cancelled(close)
        f.nextRetry()

        val second = f.attempt("close")
        assertThat(second.quantity).isEqualByComparingTo("0.006")
        f.last(second.id, f.sep, Side.BUY, "0.006", "63020")
        assertThat(f.leg(":open").quantity).isEqualByComparingTo("0.010")
        f.last(f.leg(":open").id, f.dec, Side.SELL, "0.010", "63790")

        assertThat(f.stop()).isNull()
        val entry = f.ledger.entries.single()
        assertThat(entry.fromFill).isEqualByComparingTo("63016")
        assertThat(f.heldQuantity()).isEqualByComparingTo("-0.010")
    }

    @Test
    fun `an opening leg part filled over attempts completes the carry`() {
        f.hold(Side.SELL)
        f.rolled()
        f.last(f.leg(":close").id, f.sep, Side.BUY, "0.010", "63010")
        val open = f.leg(":open").id
        f.slice(open, f.dec, Side.SELL, "0.004", "0.004", "63790")
        f.cancelled(open)
        f.nextRetry()

        f.last(f.attempt("open").id, f.dec, Side.SELL, "0.006", "63780")

        assertThat(f.stop()).isNull()
        val entry = f.ledger.entries.single()
        assertThat(entry.quantity).isEqualByComparingTo("-0.010")
    }

    @Test
    fun `attempts are bounded at five, about a minute, then the strategy stops`() {
        f.hold(Side.SELL)
        f.rolled()

        f.exhaust("close")

        assertThat(f.attempts("close").map { it.id.substringAfterLast(':') })
            .containsExactly("close", "close-2", "close-3", "close-4", "close-5")
        assertThat(f.clock.time - f.roll).isEqualTo(60_000)
        assertThat(f.stop()).contains("cancelled by the venue")
        f.nextRetry()
        assertThat(f.attempts("close")).hasSize(5)
    }

    @Test
    fun `a refusal, kill switch or any other, stops at once with no retry`() {
        f.hold(Side.SELL)
        f.rolled()

        f.venueBus.publish(
            BrokerEvent.OrderRejected(f.leg(":close").id, null, "kill_switch: killed", "s", f.clock.time),
        )
        f.nextRetry()

        assertThat(f.attempts("close")).hasSize(1)
        assertThat(f.stop()).contains("kill_switch")
    }

    @Test
    fun `a retry waiting keeps new orders refused, and saves its leg for a restart`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id
        f.cancelled(close)

        assertThat(f.saved().roll).isNotNull()
        assertThat(f.saved().legs.map { it.leg.id }).containsExactly(close)
    }

    @Test
    fun `a venue close to the old contract's expiry gets no retry that would outlive it`() {
        val g = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))
        g.hold(Side.SELL)
        g.clock.time = g.expirySep - 5 * 60_000
        g.quote("63000", "62990", "63010")

        g.cancelled(g.leg(":close").id)
        g.nextRetry()

        assertThat(g.attempts("close")).hasSize(1)
        assertThat(g.stop()).contains("cancelled by the venue")
    }

    @Test
    fun `a closing leg a venue's price limit refused goes again instead of stopping the strategy`() {
        f.holdIntoRoll()
        val first = f.leg(":close")
        val reason = "venue_rejected: price_too_high 78180"
        f.venueBus.publish(BrokerEvent.OrderRejected(first.id, null, reason, "s", f.clock.time))
        f.clock.time += RollLegIds.RETRY_SPACING_MS
        f.tick("63000")

        val sent = f.venue.sent
        assertThat(sent.count { it.id.startsWith("roll:") }).isEqualTo(2)
        val lane = f.saved()
        assertThat(lane.strategies.single { it.strategyId == "s" }.stopped).isNull()
    }
}
