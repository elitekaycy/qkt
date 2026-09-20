package com.qkt.backtest

import com.qkt.backtest.EquityCurveCollectorFixtures.newRig
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquityCurveCadenceTest {
    @Test
    fun `TICK cadence samples on every TickEvent`() {
        val rig = newRig()
        val bus = rig.bus
        val pnl = rig.pnl
        val strategyPnL = rig.strategyPnL
        val clock = rig.clock

        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.TICK,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = listOf("s1"),
            )

        clock.time = 1_000L
        bus.publish(TickEvent(Tick("X", Money.of("100"), 1_000L)))
        clock.time = 2_000L
        bus.publish(TickEvent(Tick("X", Money.of("100"), 2_000L)))

        assertThat(collector.global()).hasSize(2)
        assertThat(collector.global()[0].timestamp).isEqualTo(1_000L)
        assertThat(collector.global()[1].timestamp).isEqualTo(2_000L)
    }

    @Test
    fun `FILL cadence samples on every OrderFilled event`() {
        val rig = newRig()
        val bus = rig.bus
        val pnl = rig.pnl
        val strategyPnL = rig.strategyPnL
        val clock = rig.clock

        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.FILL,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = listOf("s1"),
            )

        clock.time = 5_000L
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("1"),
                strategyId = "s1",
            ),
        )

        assertThat(collector.global()).hasSize(1)
        assertThat(collector.global()[0].timestamp).isEqualTo(5_000L)
    }

    @Test
    fun `chart curve stays bounded under a tick flood while metrics see every sample`() {
        val rig = newRig()
        val bus = rig.bus
        val clock = rig.clock

        val cap = 100
        val collector =
            EquityCurveCollector(
                cadence = SampleCadence.TICK,
                bus = bus,
                pnl = rig.pnl,
                strategyPnL = rig.strategyPnL,
                strategyIds = listOf("s1"),
                curveCap = cap,
            )

        val ticks = 50_000
        for (i in 1..ticks) {
            clock.time = i.toLong()
            bus.publish(TickEvent(Tick("X", Money.of("100"), i.toLong())))
        }

        // Stored chart curve is thinned to the cap (+1 trailing point), not one-per-tick.
        assertThat(collector.global().size).isLessThanOrEqualTo(cap + 1)
        // Metrics still reflect the full-resolution stream.
        assertThat(collector.globalMetrics().count).isEqualTo(ticks)
        assertThat(collector.global().last().timestamp).isEqualTo(ticks.toLong())
    }
}
