package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EquityCurveCollectorEdgesTest {
    private val rig = EquityCurveCollectorFixtures.newRig()

    private fun collector(cadence: SampleCadence = SampleCadence.TICK) =
        EquityCurveCollector(
            cadence = cadence,
            bus = rig.bus,
            pnl = rig.pnl,
            strategyPnL = rig.strategyPnL,
            strategyIds = listOf("s"),
            startingBalance = BigDecimal("100"),
            candleWindowMs = 60_000L,
        )

    private fun tick(t: Long) {
        rig.clock.time = t
        rig.bus.publish(TickEvent(Tick("X", Money.of("10"), t)))
    }

    @Test
    fun `an unknown strategy has no curve and no metrics`() {
        val c = collector()

        assertThat(c.forStrategy("other")).isEmpty()
        assertThat(c.metricsFor("other")).isNull()
        assertThat(c.metricsFor("s")).isNotNull()
    }

    @Test
    fun `windows must be declared before the first sample`() {
        val c = collector()
        c.declareWindow(MetricsWindow("early", 0L, 60_000L))
        tick(1L)

        assertThatThrownBy { c.declareWindow(MetricsWindow("late", 0L, 60_000L)) }
            .hasMessage("metrics windows must be declared before the first equity sample")
        assertThat(c.windows().map { it.window.name }).containsExactly("early")
    }

    @Test
    fun `closing an unsampled or unchanged curve adds no sample`() {
        val c = collector()
        c.closeAt(5L)
        assertThat(c.global()).isEmpty()

        tick(1L)
        c.closeAt(5L)

        assertThat(c.global().map { it.timestamp }).containsExactly(1L)
    }

    @Test
    fun `boundary snapshots are taken only under candle-close sampling`() {
        val c = collector(SampleCadence.TICK)
        c.beforeTick(0L)
        c.beforeTick(120_000L)
        tick(120_000L)

        assertThat(c.global().single().equity).isEqualByComparingTo("100")
    }
}
