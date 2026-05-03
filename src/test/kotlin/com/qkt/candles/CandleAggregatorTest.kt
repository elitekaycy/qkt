package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleAggregatorTest {
    private val clock = FixedClock(0L)
    private val sequencer = MonotonicSequenceGenerator()
    private val bus = EventBus(clock, sequencer)
    private val captured = mutableListOf<CandleEvent>()

    init {
        bus.subscribe<CandleEvent> { captured.add(it) }
    }

    private fun aggregator() = CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    private fun publishTick(
        symbol: String,
        price: Double,
        ts: Long,
        volume: Double? = null,
    ) {
        bus.publish(TickEvent(Tick(symbol, price, ts, volume)))
    }

    @Test
    fun `first tick for a symbol does not emit a CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick within current window updates OHLC in place without emitting`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.5, 30_000L)
        publishTick("XAUUSD", 2399.5, 45_000L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick past current endTime emits CandleEvent for the closed window`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 30_000L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `closed candle has correct OHLC computed from all ticks in the window`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.5, 15_000L)
        publishTick("XAUUSD", 2399.5, 30_000L)
        publishTick("XAUUSD", 2400.8, 45_000L)
        publishTick("XAUUSD", 2402.0, 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.open).isEqualTo(2400.0)
        assertThat(c.high).isEqualTo(2401.5)
        assertThat(c.low).isEqualTo(2399.5)
        assertThat(c.close).isEqualTo(2400.8)
    }

    @Test
    fun `closed candle's startTime is window-aligned not first-tick timestamp`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 23_456L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
    }

    @Test
    fun `closed candle's endTime is startTime plus durationMs`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.endTime).isEqualTo(c.startTime + 60_000L)
    }

    @Test
    fun `volume sums only non-null tick volumes`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L, volume = 1.5)
        publishTick("XAUUSD", 2400.5, 30_000L, volume = null)
        publishTick("XAUUSD", 2400.2, 45_000L, volume = 2.5)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.volume).isEqualTo(4.0)
    }

    @Test
    fun `windows for different symbols are tracked independently`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("EURUSD", 1.0921, 30_000L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        publishTick("EURUSD", 1.0930, 75_000L)
        assertThat(captured).hasSize(2)
        assertThat(captured.map { it.candle.symbol }).containsExactly("XAUUSD", "EURUSD")
    }

    @Test
    fun `boundary timestamp triggers window roll`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 30_000L)
        publishTick("XAUUSD", 2401.0, 60_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
        assertThat(captured[0].candle.endTime).isEqualTo(60_000L)
    }

    @Test
    fun `multiple consecutive rolls each emit one CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.0, 60_000L)
        publishTick("XAUUSD", 2402.0, 120_000L)
        publishTick("XAUUSD", 2403.0, 180_000L)
        assertThat(captured).hasSize(3)
        assertThat(captured.map { it.candle.startTime }).containsExactly(0L, 60_000L, 120_000L)
    }
}
