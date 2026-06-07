package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The per-tick trigger scan is keyed by symbol, so a tick only evaluates orders for its own
 * symbol — both for correctness (it always did) and to keep per-tick cost O(this symbol's orders)
 * rather than O(all live orders) as more symbols/strategies are added.
 */
class OrderManagerSymbolIndexTest {
    private fun newBus(clock: FixedClock) = EventBus(clock, MonotonicSequenceGenerator())

    // STOP absent from caps → the Stop is engine-watched (holdPending) and fires via the tick scan.
    private fun engineStop(
        id: String,
        symbol: String,
    ) = OrderRequest.Stop(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        stopPrice = Money.of("100"),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `a tick fires only its own symbol's engine-watched orders`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(engineStop("a-stop", "A"))
        om.submit(engineStop("b-stop", "B"))

        // A tick at 101 crosses the BUY stop (>=100) for symbol A only.
        bus.publish(TickEvent(Tick("A", Money.of("101"), 1L)))

        // A's stop fired (a market under its id); B's stop was never evaluated.
        assertThat(broker.submits.any { it.id == "a-stop" }).isTrue()
        assertThat(broker.submits.none { it.id == "b-stop" }).isTrue()
    }

    @Test
    fun `a closed order on one symbol leaves the other symbol's index intact`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(engineStop("a-stop", "A"))
        om.submit(engineStop("b-stop", "B"))
        // Fire A (removes a-stop from the live index for A); B must still fire on its own tick.
        bus.publish(TickEvent(Tick("A", Money.of("101"), 1L)))
        bus.publish(TickEvent(Tick("B", Money.of("101"), 2L)))

        assertThat(broker.submits.any { it.id == "b-stop" }).isTrue()
    }
}
