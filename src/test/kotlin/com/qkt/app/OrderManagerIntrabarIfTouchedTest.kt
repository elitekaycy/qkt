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
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerIntrabarIfTouchedTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    @Test
    fun `a resting if-touched order makes a bar that reaches its trigger worth replaying at its extremes`() {
        val manager =
            OrderManager(FakeBroker(bus, clock, OrderTypeCapability.entries.toSet()), bus, MarketPriceTracker(), clock)
        manager.submit(
            OrderRequest.IfTouched(
                id = "touch",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("100"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(manager.intrabarFill("X", Money.of("98"), Money.of("101"))).isEqualTo(IntrabarFill.EXTREMES)
        assertThat(manager.intrabarFill("X", Money.of("102"), Money.of("105"))).isEqualTo(IntrabarFill.SYNTHETIC)
    }
}
