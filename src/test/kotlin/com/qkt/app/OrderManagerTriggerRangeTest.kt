package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `canTriggerInBar` is the read-only predicate behind tick-resolved fills: given a bar's price
 * range, could a live order on that symbol fill within it? MARKET-only broker caps keep the Stop
 * engine-watched (it rests PENDING in the OrderManager rather than at the broker).
 */
class OrderManagerTriggerRangeTest {
    private fun newOm(): OrderManager {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        return OrderManager(broker, bus, MarketPriceTracker(), clock)
    }

    private fun buyStop(
        symbol: String,
        price: String,
    ) = OrderRequest.Stop(
        id = "s",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        stopPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `buy stop is reachable when the bar high crosses it`() {
        val om = newOm()
        om.submit(buyStop("X", "100"))
        assertThat(om.canTriggerInBar("X", Money.of("98"), Money.of("101"))).isTrue()
        assertThat(om.canTriggerInBar("X", Money.of("96"), Money.of("99"))).isFalse()
    }

    @Test
    fun `buy stop is reachable on a gap-open above it`() {
        val om = newOm()
        om.submit(buyStop("X", "100"))
        // bar gaps to [102,103]; level 100 is below low, but a buy stop still fires (high >= 100)
        assertThat(om.canTriggerInBar("X", Money.of("102"), Money.of("103"))).isTrue()
    }

    @Test
    fun `no live order means not reachable`() {
        assertThat(newOm().canTriggerInBar("X", Money.of("0"), Money.of("9999"))).isFalse()
    }

    @Test
    fun `another symbol's order is not reachable`() {
        val om = newOm()
        om.submit(buyStop("A", "100"))
        assertThat(om.canTriggerInBar("B", Money.of("98"), Money.of("101"))).isFalse()
    }
}
