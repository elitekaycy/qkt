package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** A bus over a fixed clock that captures every closed candle, fresh for each candle aggregator test. */
abstract class CandleAggregatorFixture {
    protected val clock = FixedClock(0L)
    protected val sequencer = MonotonicSequenceGenerator()
    protected val bus = EventBus(clock, sequencer)
    protected val captured = mutableListOf<CandleEvent>()

    init {
        bus.subscribe<CandleEvent> { captured.add(it) }
    }

    protected fun aggregator() = CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    protected fun publishTick(
        symbol: String,
        price: BigDecimal,
        ts: Long,
        volume: BigDecimal? = null,
    ) {
        bus.publish(TickEvent(Tick(symbol, price, ts, volume)))
    }
}
