package com.qkt.broker.continuous

import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A roll on a venue that answers its legs later (live) ends exactly as on one that answers at once (backtest). */
class RollInFlightTest {
    private val roll = ms("2024-09-19T08:00:00Z")

    /** Enters long, then ticks at the roll at [rollPrice] (63000 is the reference level: no move). */
    private fun holdThroughRoll(
        f: ContinuousFixture,
        rollPrice: String = "63000.0",
    ) {
        f.tick("63010.0")
        f.broker.submit(f.market("entry", Side.BUY))
        f.tick(rollPrice, atMs = roll)
    }

    @Test
    fun `with the market at the reference, legs answered later book the same entry, cost and events as at once`() {
        val atOnce = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1)
        val later = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1, deferLegs = true)
        holdThroughRoll(atOnce)
        holdThroughRoll(later)

        assertThat(later.ledger.entries).isEmpty()
        assertThat(later.costs).isEmpty()
        assertThat(later.releaseLegs()).isEqualTo(2)

        assertThat(later.ledger.entries).isEqualTo(atOnce.ledger.entries)
        assertThat(later.costs).isEqualTo(atOnce.costs)
        assertThat(later.events.map { it::class to (it as BrokerEvent.OrderEvent).clientOrderId })
            .isEqualTo(atOnce.events.map { it::class to (it as BrokerEvent.OrderEvent).clientOrderId })
    }

    @Test
    fun `while a roll's legs are out the stream refuses new orders, and takes them once the roll is done`() {
        val f = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", deferLegs = true)
        holdThroughRoll(f)

        val during = f.broker.submit(f.market("during", Side.SELL))
        f.releaseLegs()
        val after = f.broker.submit(f.market("after", Side.SELL))

        assertThat(during.accepted).isFalse()
        assertThat(during.rejectReason).contains("rolling")
        assertThat(after.accepted).isTrue()
    }

    @Test
    fun `an opening leg refused later closes the position on the stream and stops the strategy, as at once`() {
        val atOnce = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", refuseOpenLegs = true)
        val later = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", refuseOpenLegs = true, deferLegs = true)
        holdThroughRoll(atOnce)
        holdThroughRoll(later)
        later.releaseLegs()

        val closes = later.only<BrokerEvent.OrderFilled>().filter { it.exitReason != null }
        assertThat(closes).isEqualTo(atOnce.only<BrokerEvent.OrderFilled>().filter { it.exitReason != null })
        assertThat(closes).hasSize(1)
        assertThat(later.broker.submit(later.market("again", Side.BUY)).accepted).isFalse()
    }

    @Test
    fun `a leg answered later fills at the venue's price when it executes, and the cost books that fill`() {
        val f = ContinuousFixture(startIso = "2024-09-19T07:45:00Z", slippageTicks = 1, deferLegs = true)
        holdThroughRoll(f, rollPrice = "63005.0")

        f.releaseLegs()

        val entry = f.ledger.entries.single()
        assertThat(entry.fromFill).isEqualByComparingTo("62999.9")
        assertThat(entry.toFill).isEqualByComparingTo("63805.1")
        assertThat(entry.toReference).isEqualByComparingTo("63800")
        assertThat(f.costs.single().amount).isEqualByComparingTo(entry.cost)
    }
}
