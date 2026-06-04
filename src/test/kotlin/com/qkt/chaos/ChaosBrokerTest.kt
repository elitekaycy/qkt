package com.qkt.chaos

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChaosBrokerTest {
    private fun bus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun order(id: String) =
        OrderRequest.Market(id, "BYBIT_SPOT:BTCUSDT", Side.BUY, BigDecimal("1"), TimeInForce.GTC, 1_000L)

    private fun fake() = FakeBroker(bus(), FixedClock(0L), setOf(OrderTypeCapability.MARKET))

    @Test
    fun `passes submit through to the delegate when no fault`() {
        val delegate = fake()
        val chaos = ChaosBroker(delegate, ChaosFaultModel())
        val ack = chaos.submit(order("ORD-1"))
        assertThat(ack.accepted).isTrue()
        assertThat(delegate.submits).hasSize(1)
    }

    @Test
    fun `REJECT fault returns an un-accepted ack without touching the delegate`() {
        val delegate = fake()
        val chaos = ChaosBroker(delegate, ChaosFaultModel(submitFault = SubmitFault.REJECT))
        val ack = chaos.submit(order("ORD-1"))
        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("chaos")
        assertThat(delegate.submits).isEmpty()
    }

    @Test
    fun `THROW fault propagates an exception`() {
        val chaos = ChaosBroker(fake(), ChaosFaultModel(submitFault = SubmitFault.THROW))
        assertThatThrownBy { chaos.submit(order("ORD-1")) }.hasMessageContaining("chaos")
    }

    @Test
    fun `stalePositions hides the delegate's open positions`() {
        val chaos = ChaosBroker(fake(), ChaosFaultModel(stalePositions = true))
        assertThat(chaos.getOpenPositions()).isEmpty()
    }
}
