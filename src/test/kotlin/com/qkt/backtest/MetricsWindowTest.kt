package com.qkt.backtest

import com.qkt.backtest.EquityCurveCollectorFixtures.newRig
import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MetricsWindowTest {
    @Test
    fun `a window folds only the samples inside its half-open range`() {
        val w = WindowSamples(MetricsWindow("oos", 2_000L, 4_000L))
        w.accept(1_000L, Money.of("100"))
        w.accept(2_000L, Money.of("101"))
        w.accept(3_999L, Money.of("99"))
        w.accept(4_000L, Money.of("50"))

        assertThat(w.metrics.count).isEqualTo(2)
        assertThat(w.metrics.startingEquity()).isEqualByComparingTo("101")
        assertThat(w.lastEquity).isEqualByComparingTo("99")
    }

    @Test
    fun `a window must be named and end after it starts`() {
        assertThatThrownBy { MetricsWindow(" ", 0L, 1L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MetricsWindow("x", 5L, 5L) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the collector routes each sample to the windows that contain it and to the daily series`() {
        val rig = newRig()
        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.TICK,
                bus = rig.bus,
                pnl = rig.pnl,
                strategyPnL = rig.strategyPnL,
                strategyIds = listOf("s1"),
                startingBalance = Money.of("1000"),
            )
        collector.declareWindow(MetricsWindow("first", 0L, 86_400_000L))
        collector.declareWindow(MetricsWindow("all", 0L, Long.MAX_VALUE))
        for (ts in listOf(1_000L, 2_000L, 86_400_000L + 1_000L)) {
            rig.clock.time = ts
            rig.bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
        }

        assertThat(collector.windows().map { it.window.name to it.metrics.count })
            .containsExactly("first" to 2, "all" to 3)
        assertThat(collector.dailyEquity()).hasSize(2)
        assertThat(collector.dailyEquity().last().close).isEqualByComparingTo("1000")
    }
}
