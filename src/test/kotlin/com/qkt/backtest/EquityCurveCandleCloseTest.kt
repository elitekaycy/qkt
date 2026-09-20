package com.qkt.backtest

import com.qkt.backtest.EquityCurveCollectorFixtures.candle
import com.qkt.backtest.EquityCurveCollectorFixtures.newRig
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquityCurveCandleCloseTest {
    @Test
    fun `CANDLE_CLOSE samples global and per-strategy equity at candle endTime`() {
        val rig = newRig()
        val bus = rig.bus
        val pnl = rig.pnl
        val strategyPnL = rig.strategyPnL
        val clock = rig.clock

        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.CANDLE_CLOSE,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = listOf("s1"),
            )

        clock.time = 60_000L
        bus.publish(CandleEvent(candle("100", 60_000L)))
        clock.time = 120_000L
        bus.publish(CandleEvent(candle("100", 120_000L)))

        assertThat(collector.global()).hasSize(2)
        assertThat(collector.global()[0].timestamp).isEqualTo(60_000L)
        assertThat(collector.global()[1].timestamp).isEqualTo(120_000L)
        assertThat(collector.global()[0].equity).isEqualByComparingTo(Money.ZERO)

        assertThat(collector.forStrategy("s1")).hasSize(2)
        assertThat(collector.forStrategy("s1")[0].equity).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `CANDLE_CLOSE samples once after all symbols close the same boundary`() {
        val rig = newRig()
        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.CANDLE_CLOSE,
                bus = rig.bus,
                pnl = rig.pnl,
                strategyPnL = rig.strategyPnL,
                strategyIds = listOf("s1"),
                candleSymbols = setOf("X", "Y"),
            )

        rig.bus.publish(CandleEvent(candle("100", 60_000L, symbol = "X")))
        assertThat(collector.global()).isEmpty()
        rig.bus.publish(CandleEvent(candle("200", 60_000L, symbol = "Y")))

        assertThat(collector.global()).hasSize(1)
        assertThat(collector.globalMetrics().count).isEqualTo(1)
        assertThat(collector.global().single().timestamp).isEqualTo(60_000L)
    }

    @Test
    fun `a sample stamped before the replay window is warmup and never reaches the curve`() {
        // Warmup replays pre-window ticks and seeded bars through the same bus. Without the floor
        // the curve started with flat starting-balance points before the run began, which changed
        // the sample count every return-based statistic divides by.
        val rig = newRig()
        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.CANDLE_CLOSE,
                bus = rig.bus,
                pnl = rig.pnl,
                strategyPnL = rig.strategyPnL,
                strategyIds = listOf("s1"),
                windowStartMs = 120_000L,
            )

        rig.bus.publish(CandleEvent(candle("100", 60_000L)))
        assertThat(collector.global()).isEmpty()
        assertThat(collector.globalMetrics().count).isEqualTo(0)

        rig.bus.publish(CandleEvent(candle("100", 120_000L)))
        rig.bus.publish(CandleEvent(candle("100", 180_000L)))

        assertThat(collector.global().map { it.timestamp }).containsExactly(120_000L, 180_000L)
        assertThat(collector.globalMetrics().count).isEqualTo(2)
        assertThat(collector.forStrategy("s1")).hasSize(2)
    }

    @Test
    fun `no window floor keeps every sample for a live session`() {
        val rig = newRig()
        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.CANDLE_CLOSE,
                bus = rig.bus,
                pnl = rig.pnl,
                strategyPnL = rig.strategyPnL,
                strategyIds = listOf("s1"),
            )
        rig.bus.publish(CandleEvent(candle("100", 60_000L)))
        assertThat(collector.global()).hasSize(1)
    }

    @Test
    fun `unknown strategyId returns empty list`() {
        val rig = newRig()
        val bus = rig.bus
        val pnl = rig.pnl
        val strategyPnL = rig.strategyPnL
        val clock = rig.clock

        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.CANDLE_CLOSE,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = listOf("s1"),
            )

        assertThat(collector.forStrategy("nonexistent")).isEmpty()
    }
}
