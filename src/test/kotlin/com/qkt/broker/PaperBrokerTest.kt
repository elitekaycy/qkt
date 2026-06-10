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
    fun `stop triggers are side-aware on the quote, not the mid`() {
        // MT5 fires BUY_STOP on the ASK and SELL_STOP on the BID. With bid 100.4 /
        // ask 100.6 (mid 100.5): a buy stop at 100.55 must trigger (ask crossed it)
        // even though the mid never did, and a sell stop at 100.55 must NOT trigger
        // (the bid never reached it) even though the ask did.
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("100.5"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "buy-stop",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100.55"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.submit(
            OrderRequest.Stop(
                id = "sell-stop",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("100.45"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(
            Tick(
                symbol = "XAUUSD",
                price = Money.of("100.5"),
                timestamp = 1L,
                bid = Money.of("100.48"),
                ask = Money.of("100.56"),
            ),
        )

        // Ask 100.56 crossed the 100.55 buy stop; bid 100.48 never reached the
        // 100.45 sell stop — even though the mid (100.5) would have triggered neither
        // or, with a wider spread, both.
        assertThat(fills.map { it.clientOrderId }).containsExactly("buy-stop")
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

    @Test
    fun `declares MULTI_POSITION_PER_SYMBOL capability`() {
        val bus = newBus()
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        assertThat(b.capabilities).contains(OrderTypeCapability.MULTI_POSITION_PER_SYMBOL)
    }

    @Test
    fun `does not advertise POSITION_MODIFY so bracket exits use the resting-order fallback`() {
        val bus = newBus()
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        assertThat(b.capabilities).doesNotContain(OrderTypeCapability.POSITION_MODIFY)
    }

    @Test
    fun `multiple market fills on the same symbol each emit a distinct OrderFilled`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        for (idx in 0..2) {
            b.submit(
                OrderRequest.Market(
                    id = "c$idx",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                    strategyId = "alpha",
                ),
            )
        }

        // PaperBroker does not net — each submit produces its own fill with its own id.
        assertThat(fills.map { it.clientOrderId }).containsExactly("c0", "c1", "c2")
        assertThat(fills.map { it.quantity }).allMatch { it.compareTo(Money.of("0.1")) == 0 }
    }

    @Test
    fun `concurrent limit + stop on the same symbol fire independently as price prints`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        // BUY-limit at 1.09 (fires when price ≤ 1.09)
        b.submit(
            OrderRequest.Limit(
                id = "limit-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.1"),
                limitPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        // BUY-stop at 1.11 (fires when price ≥ 1.11)
        b.submit(
            OrderRequest.Stop(
                id = "stop-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.1"),
                stopPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(tick("EURUSD", "1.12")) // crosses the stop only
        assertThat(fills.map { it.clientOrderId }).containsExactly("stop-1")
        b.onTick(tick("EURUSD", "1.08")) // crosses the limit
        assertThat(fills.map { it.clientOrderId }).containsExactly("stop-1", "limit-1")
    }

    @Test
    fun `stack-shape multi-fill on same symbol does not commingle quantities`() {
        // Mirrors the STACK_AT stack-entry market shape: each tier's entry comes through
        // with its own client id and its own qty. PaperBroker treats them as independent.
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        // Primary entry
        b.submit(
            OrderRequest.Market(
                id = "primary-1-entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        // Stack tier-0 entry at a later (favorable) price
        tracker.update("EURUSD", Money.of("1.106"))
        b.submit(
            OrderRequest.Market(
                id = "primary-1-stack-tier0-entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.06"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(fills).hasSize(2)
        assertThat(fills[0].clientOrderId).isEqualTo("primary-1-entry")
        assertThat(fills[0].quantity).isEqualByComparingTo("0.20")
        assertThat(fills[0].price).isEqualByComparingTo("1.10")
        assertThat(fills[1].clientOrderId).isEqualTo("primary-1-stack-tier0-entry")
        assertThat(fills[1].quantity).isEqualByComparingTo("0.06")
        assertThat(fills[1].price).isEqualByComparingTo("1.106")
    }
}
