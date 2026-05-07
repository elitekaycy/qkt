package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquityCurveCollectorTest {
    private fun candle(
        close: String,
        endMs: Long,
    ): Candle =
        Candle(
            symbol = "X",
            open = Money.of(close),
            high = Money.of(close),
            low = Money.of(close),
            close = Money.of(close),
            volume = Money.of("1"),
            startTime = endMs - 60_000L,
            endTime = endMs,
        )

    private data class Rig(
        val bus: EventBus,
        val pnl: PnLCalculator,
        val strategyPnL: StrategyPnL,
        val clock: FixedClock,
    )

    private fun newRig(): Rig {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        return Rig(bus, pnl, strategyPnL, clock)
    }

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
}
