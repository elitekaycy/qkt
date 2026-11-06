package com.qkt.app

import com.qkt.app.OrderManagerTier2FallbackFixtures.newBus
import com.qkt.app.OrderManagerTier2FallbackFixtures.tier1Only
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTier2FallbackTouchLimitTest {
    @Test
    fun `an engine held limit if touched becomes a limit at its own price once the trigger is reached`() {
        val bus = newBus()
        val clock = FixedClock(time = 5L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.IfTouched(
                id = "lit-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("2"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.LIMIT,
                limitPrice = Money.of("1.1005"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )
        assertThat(broker.submits).isEmpty()

        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.099"), 1L)))

        val limit = broker.submits.single() as OrderRequest.Limit
        assertThat(limit.id).isEqualTo("lit-1")
        assertThat(limit.limitPrice).isEqualByComparingTo("1.1005")
        assertThat(limit.quantity).isEqualByComparingTo("2")
        assertThat(limit.strategyId).isEqualTo("alpha")
        assertThat(limit.timestamp).isEqualTo(5L)
    }
}
