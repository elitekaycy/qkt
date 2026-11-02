package com.qkt.broker.continuous

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** MT5 drops a limit's time in force, so a collared leg can rest there: the lane cancels it, and the roll ends. */
class RollLegDeadlineTest {
    private val f = ScriptedLaneFixture(rollPriceBand = BigDecimal("0.25"))

    @Test
    fun `a venue that lets a collared leg rest has it cancelled at the deadline, so the roll goes on`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id

        f.clock.time = f.roll + RollLegDeadline.DEADLINE_MS - 1
        f.tick("63000")
        assertThat(f.venue.cancels).isEmpty()
        f.clock.time = f.roll + RollLegDeadline.DEADLINE_MS
        f.tick("63000")
        assertThat(f.venue.cancels).containsExactly(close)

        f.clock.time += 1_000
        f.tick("63000")
        assertThat(f.venue.cancels).containsExactly(close)
        f.exhaust("close")

        assertThat(f.saved().roll).isNull()
        assertThat(f.stop()).contains("cancelled by the venue")
    }

    @Test
    fun `a cancel the venue did not answer is asked again one deadline later`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id

        f.clock.time = f.roll + RollLegDeadline.DEADLINE_MS
        f.tick("63000")
        f.clock.time += RollLegDeadline.DEADLINE_MS
        f.tick("63000")

        assertThat(f.venue.cancels).containsExactly(close, close)
    }

    @Test
    fun `a restored leg is not cancelled before the session is ready, and a market leg never is`() {
        f.hold(Side.SELL)
        f.rolled()
        val close = f.leg(":close").id
        val later = f.roll + 2 * RollLegDeadline.DEADLINE_MS

        val g = f.restart(later)
        g.broker.recoverPendingOrders(emptyList(), emptySet())
        g.tick("63000")
        assertThat(g.venue.cancels).isEmpty()
        g.ready()
        g.tick("63000")
        assertThat(g.venue.cancels).containsExactly(close)

        val backtest = ScriptedLaneFixture()
        backtest.hold(Side.SELL)
        backtest.rolled()
        backtest.clock.time += 2 * RollLegDeadline.DEADLINE_MS
        backtest.tick("63000")
        assertThat(backtest.venue.cancels).isEmpty()
    }
}
