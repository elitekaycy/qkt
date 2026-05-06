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
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTier2FallbackTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val tier1Only = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)

    @Test
    fun `Stop without broker capability is held pending`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
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

        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.PENDING)
        assertThat(broker.submits).isEmpty()
    }

    @Test
    fun `Stop SELL fires Market when tick crosses stopPrice`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
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
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(broker.submits.single().symbol).isEqualTo("EURUSD")
        assertThat(broker.submits.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `StopLimit fires Limit at limitPrice when tick crosses stopPrice`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StopLimit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                limitPrice = Money.of("1.085"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))

        assertThat(broker.submits).hasSize(1)
        val submitted = broker.submits.single()
        assertThat(submitted).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((submitted as OrderRequest.Limit).limitPrice).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `IfTouched MARKET fires Market when tick reaches trigger`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
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
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.099"), 1L)))

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `pending Stop is removed once triggered (does not double-fire)`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
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
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.080"), 2L)))

        assertThat(broker.submits).hasSize(1)
    }
}
