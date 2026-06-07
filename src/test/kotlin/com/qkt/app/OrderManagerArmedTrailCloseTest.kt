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

class OrderManagerArmedTrailCloseTest {
    private fun armedSl(id: String) =
        OrderRequest.ArmedTrailingStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            trailDistance = Money.of("5"),
            mfeThreshold = Money.of("10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun fireOf(
        om: OrderManager,
        broker: FakeBroker,
        bus: EventBus,
        sl: OrderRequest.ArmedTrailingStop,
    ): OrderRequest.Market {
        om.submit(sl)
        // Price rises to arm (MFE 10 ≥ threshold → hwm 110, trail at 110−5=105), then drops
        // through the trail (104 ≤ 105) so the stop fires.
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))
        return broker.submits.first { it.id == sl.id } as OrderRequest.Market
    }

    @Test
    fun `fired armed trailing stop closes its leg by ticket via the resolver`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closeTicketFor = { _, exitId -> if (exitId == "b1-sl") "tkt-99" else null },
            )

        val fired = fireOf(om, broker, bus, armedSl("b1-sl"))

        // The fired market closes the exact venue position by ticket — not an opposite order
        // that would open a counter on a hedging account.
        assertThat(fired.closesTicket).isEqualTo("tkt-99")
        assertThat(fired.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `armed trailing stop with no resolver match fires a plain market`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, closeTicketFor = { _, _ -> null })

        val fired = fireOf(om, broker, bus, armedSl("lone-sl"))

        // A plain single-position (PRIMARY) trailing stop keeps the netting close — no ticket.
        assertThat(fired.closesTicket).isNull()
    }
}
