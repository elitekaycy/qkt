package com.qkt.marketdata

import com.qkt.common.Clock
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The outlier band is sampled over a fixed span of TIME, so raising a feed's tick rate does
 * not narrow it. A count-bounded window shrank from about a minute to about twelve seconds
 * when the MT5 source moved from one quote per poll to every tick in the interval, tightening
 * the band precisely during the fast moves the engine most needs to trade.
 */
class MarketDataGateOutlierWindowTest {
    private class MutableClock(
        var nowMs: Long,
    ) : Clock {
        override fun now(): Long = nowMs
    }

    private fun tick(
        price: String,
        ms: Long,
    ) = Tick(
        symbol = "EXNESS:XAUUSD",
        price = BigDecimal(price),
        timestamp = ms,
        bid = BigDecimal(price),
        ask = BigDecimal(price),
        volume = null,
    )

    /** Walks a drifting price for [seconds] at [perSecond] ticks a second, then judges [probe]. */
    private fun verdictAfterWalk(
        perSecond: Int,
        seconds: Int,
        probe: String,
    ): MarketDataGate.Verdict {
        val clock = MutableClock(1_778_662_794_000L)
        val gate = MarketDataGate(clock)
        val stepMs = 1_000L / perSecond
        var n = 0
        repeat(seconds * perSecond) {
            // A slow drift: the same price PATH either way, sampled at different densities.
            val price = 4700.0 + (n % 40) * 0.05
            gate.observe(tick(String.format("%.2f", price), clock.nowMs))
            clock.nowMs += stepMs
            n++
        }
        return gate.observe(tick(probe, clock.nowMs))
    }

    @Test
    fun `a dense feed judges the same move as a sparse feed`() {
        // Same 64 seconds of price action, same probe: only the sampling density differs.
        val sparse = verdictAfterWalk(perSecond = 1, seconds = 64, probe = "4701.00")
        val dense = verdictAfterWalk(perSecond = 6, seconds = 64, probe = "4701.00")
        assertThat(dense).isEqualTo(sparse)
    }

    @Test
    fun `prices older than the window are dropped from the band`() {
        val clock = MutableClock(1_778_662_794_000L)
        val gate = MarketDataGate(clock, outlierWindowMs = 10_000L)
        // A tight cluster far in the past must not widen the band that judges the present.
        repeat(20) {
            gate.observe(tick("4700.00", clock.nowMs))
            clock.nowMs += 100L
        }
        clock.nowMs += 60_000L
        repeat(20) {
            gate.observe(tick("4800.00", clock.nowMs))
            clock.nowMs += 100L
        }
        // Judged against the recent 4800 cluster alone, 4700 is a gross outlier.
        assertThat(gate.observe(tick("4700.00", clock.nowMs))).isEqualTo(MarketDataGate.Verdict.OUTLIER)
    }

    @Test
    fun `a genuine level shift still re-baselines rather than freezing the feed`() {
        val clock = MutableClock(1_778_662_794_000L)
        val gate = MarketDataGate(clock)
        repeat(20) {
            gate.observe(tick("4700.00", clock.nowMs))
            clock.nowMs += 200L
        }
        // Three coherent ticks at a new level re-baseline; the feed must not stay stuck.
        repeat(3) {
            gate.observe(tick("4750.00", clock.nowMs))
            clock.nowMs += 200L
        }
        assertThat(gate.observe(tick("4750.00", clock.nowMs))).isEqualTo(MarketDataGate.Verdict.OK)
    }
}
