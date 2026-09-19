package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/** A bus over a fixed clock and a fresh sequence generator per test, plus a tick builder. */
abstract class EventBusFixture {
    protected val clock = FixedClock(time = 1000L)
    protected val sequencer = MonotonicSequenceGenerator()

    protected fun newBus() = EventBus(clock, sequencer)

    protected fun tick(
        symbol: String = "XAUUSD",
        price: BigDecimal = Money.of("2400.0"),
    ) = Tick(symbol, price, 999L)
}
