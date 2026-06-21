package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class BookReturnCollectorTest {
    private data class Rig(
        val bus: EventBus,
        val pnl: PnLCalculator,
        val strategyPnL: StrategyPnL,
        val clock: FixedClock,
    )

    private fun newRig(): Rig {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val priceTracker = MarketPriceTracker()
        val pnl = PnLCalculator(PositionTracker(), priceTracker)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), priceTracker)
        return Rig(bus, pnl, strategyPnL, clock)
    }

    private val capital = BigDecimal("10000")

    private fun Rig.tick(t: Long) {
        clock.time = t
        bus.publish(TickEvent(Tick("X", Money.of("100"), t)))
    }

    private fun collector(
        rig: Rig,
        ids: List<String>,
    ) = BookReturnCollector(SampleCadence.TICK, rig.bus, rig.pnl, rig.strategyPnL, ids, capital)

    @Test
    fun `single strategy yields null analytics`() {
        val rig = newRig()
        val c = collector(rig, listOf("only"))
        rig.tick(1L)
        rig.tick(2L)
        assertThat(c.result()).isNull()
    }

    @Test
    fun `two perfectly correlated strategies report correlation ~1 and equal risk contribution`() {
        val rig = newRig()
        val c = collector(rig, listOf("a", "b"))
        // Identical, varying per-sample returns for a and b => correlation ~1, equal risk shares.
        for ((t, delta) in listOf(1L to "100", 2L to "200", 3L to "50", 4L to "200")) {
            rig.strategyPnL.recordRealized("a", BigDecimal(delta))
            rig.strategyPnL.recordRealized("b", BigDecimal(delta))
            rig.pnl.recordRealized(BigDecimal(delta).multiply(BigDecimal("2")))
            rig.tick(t)
        }
        val ba = c.result()!!
        assertThat(ba.returnCorrelation).hasSize(1)
        assertThat(ba.returnCorrelation[0].correlation).isGreaterThan(BigDecimal("0.99"))
        assertThat(ba.riskContribution.getValue("a")).isCloseTo(BigDecimal("0.5"), within(BigDecimal("0.02")))
        assertThat(ba.riskContribution.getValue("b")).isCloseTo(BigDecimal("0.5"), within(BigDecimal("0.02")))
    }

    @Test
    fun `contribution to return matches pnl shares`() {
        val rig = newRig()
        val c = collector(rig, listOf("a", "b"))
        // a -> +70 total, b -> +30 total, book +100.
        val steps = listOf(Triple(1L, "10", "10"), Triple(2L, "30", "10"), Triple(3L, "30", "10"))
        for ((t, da, db) in steps) {
            rig.strategyPnL.recordRealized("a", BigDecimal(da))
            rig.strategyPnL.recordRealized("b", BigDecimal(db))
            rig.pnl.recordRealized(BigDecimal(da).add(BigDecimal(db)))
            rig.tick(t)
        }
        val ba = c.result()!!
        assertThat(ba.contributionToReturn.getValue("a")).isEqualByComparingTo("0.7")
        assertThat(ba.contributionToReturn.getValue("b")).isEqualByComparingTo("0.3")
    }
}
