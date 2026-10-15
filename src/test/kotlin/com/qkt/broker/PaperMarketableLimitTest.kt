package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A limit already marketable when it arrives fills at the current price, never at a later print. */
class PaperMarketableLimitTest {
    private val tracker = MarketPriceTracker()
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val fills =
        mutableListOf<BrokerEvent.OrderFilled>().also { f ->
            bus.subscribe<BrokerEvent.OrderFilled> {
                f +=
                    it
            }
        }
    private val broker = PaperBroker(bus, FixedClock(0L), tracker, fillAtTriggerPrice = { true })

    private fun limit(
        side: Side,
        price: String,
    ) = OrderRequest.Limit("o1", "X", side, Money.of("1"), Money.of(price), TimeInForce.GTC, timestamp = 0L)

    @Test
    fun `a buy limit above the open fills at the open, not at the bar's later low`() {
        tracker.update("X", Money.of("101"))

        broker.submit(limit(Side.BUY, "103"))
        broker.onTick(Tick("X", Money.of("100"), 1L))

        assertThat(fills.map { it.price }).containsExactly(Money.of("101"))
    }

    @Test
    fun `a sell limit below the open fills at the open, not at the bar's later high`() {
        tracker.update("X", Money.of("105"))

        broker.submit(limit(Side.SELL, "103"))
        broker.onTick(Tick("X", Money.of("106"), 1L))

        assertThat(fills.map { it.price }).containsExactly(Money.of("105"))
    }

    @Test
    fun `a limit not yet marketable still rests until price reaches it`() {
        tracker.update("X", Money.of("104"))

        broker.submit(limit(Side.BUY, "103"))
        assertThat(fills).isEmpty()
        broker.onTick(Tick("X", Money.of("102.5"), 1L))

        assertThat(fills.map { it.price }).containsExactly(Money.of("102.5"))
    }
}
