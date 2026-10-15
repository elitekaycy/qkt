package com.qkt.backtest

import com.qkt.backtest.EquityCurveCollectorFixtures.candle
import com.qkt.backtest.EquityCurveCollectorFixtures.newRig
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A candle-close sample is equity at that candle's close, captured before the next bar's first tick moves
 * prices, not equity when the closing event fires (already marked at the next open).
 */
class EquityCurveBoundaryEquityTest {
    private val rig = newRig()

    private fun collector(window: Long?) =
        EquityCurveCollector(
            cadence = SampleCadence.CANDLE_CLOSE,
            bus = rig.bus,
            pnl = rig.pnl,
            strategyPnL = rig.strategyPnL,
            strategyIds = listOf("s1"),
            startingBalance = Money.of("10000"),
            candleWindowMs = window,
        )

    @Test
    fun `the sample keeps equity as of the close when the next bar opens elsewhere`() {
        val c = collector(60_000L)
        c.beforeTick(30_000L)
        rig.pnl.recordRealized(Money.of("-20"))
        c.beforeTick(60_000L)
        rig.pnl.recordRealized(Money.of("19"))
        rig.bus.publish(CandleEvent(candle("80", 60_000L)))

        assertThat(c.global().single().equity).isEqualByComparingTo(Money.of("9980"))
    }

    @Test
    fun `without a window the sample is equity when the close fires`() {
        val c = collector(null)
        rig.pnl.recordRealized(Money.of("-20"))
        c.beforeTick(60_000L)
        rig.pnl.recordRealized(Money.of("19"))
        rig.bus.publish(CandleEvent(candle("80", 60_000L)))

        assertThat(c.global().single().equity).isEqualByComparingTo(Money.of("9999"))
    }

    @Test
    fun `a gap of several windows keys the capture to the last closed window's end`() {
        val c = collector(60_000L)
        c.beforeTick(10_000L)
        rig.pnl.recordRealized(Money.of("-5"))
        c.beforeTick(300_000L)
        rig.pnl.recordRealized(Money.of("-100"))
        rig.bus.publish(CandleEvent(candle("80", 60_000L)))

        assertThat(c.global().single().equity).isEqualByComparingTo(Money.of("9995"))
    }
}
