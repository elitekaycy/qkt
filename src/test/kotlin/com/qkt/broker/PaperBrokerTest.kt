package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun tick(
        symbol: String,
        price: String,
        ts: Long = 1L,
    ) = Tick(symbol, Money.of(price), ts)

    @Test
    fun `Market fills at last tracker price`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        b.submit(req)

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `Market with no tracker price emits OrderRejected`() {
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { e -> rejects.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "UNKNOWN",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(rejects).hasSize(1)
    }

    @Test
    fun `Limit fills when a tick crosses the limit price`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()

        b.onTick(tick("EURUSD", "1.105"))
        assertThat(fills).isEmpty()

        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.099"))
    }

    @Test
    fun `Stop converts to Market on trigger`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(tick("EURUSD", "1.085"))
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `cancel removes a working Limit before fill`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.cancel("c1")

        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
    }

    @Test
    fun `IfTouched MARKET fires when tick reaches trigger`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.IfTouched(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).hasSize(1)
    }

    @Test
    fun `multiple Limits per symbol fill in submission order when a single tick crosses both`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))

        assertThat(fills.map { it.clientOrderId }).containsExactly("c1", "c2")
    }
}
