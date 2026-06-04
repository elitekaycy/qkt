package com.qkt.chaos

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmitFaultChaosTest {
    @Test
    fun `a venue-rejecting broker yields an un-accepted ack and no fill`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val chaos =
            ChaosBroker(
                FakeBroker(bus, FixedClock(0L), setOf(OrderTypeCapability.MARKET)),
                ChaosFaultModel(submitFault = SubmitFault.REJECT),
            )

        val ack =
            chaos.submit(
                OrderRequest.Market("ORD-1", "BYBIT_SPOT:BTCUSDT", Side.BUY, BigDecimal("1"), TimeInForce.GTC, 1_000L),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(fills).isEmpty()
    }
}
