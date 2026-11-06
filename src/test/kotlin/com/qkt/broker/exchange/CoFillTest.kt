package com.qkt.broker.exchange

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoFillTest {
    private fun stop(
        id: String,
        side: Side,
        qty: String,
        strategy: String = "s",
    ) = OrderRequest.Stop(id, "C", side, BigDecimal(qty), BigDecimal("100"), TimeInForce.GTC, 0L, strategy)

    @Test
    fun `buy stops of one strategy on one contract co-fill and add up`() {
        val working = listOf(stop("a", Side.BUY, "2"), stop("b", Side.BUY, "3"), stop("c", Side.BUY, "7", strategy = "other"))

        assertThat(CoFill.pending(working, stop("new", Side.BUY, "1"))).isEqualByComparingTo("5")
    }

    @Test
    fun `sell stops co-fill as a negative quantity and never count a buy stop`() {
        val working = listOf(stop("a", Side.SELL, "2"), stop("b", Side.BUY, "3"))

        assertThat(CoFill.pending(working, stop("new", Side.SELL, "1"))).isEqualByComparingTo("-2")
    }

    @Test
    fun `an order shape without a resting trigger fills at once like a market order`() {
        val touch =
            OrderRequest.IfTouched(
                id = "t",
                symbol = "C",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                triggerPrice = BigDecimal("100"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val market = OrderRequest.Market("m", "C", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 0L)

        assertThat(CoFill.together(touch, market)).isTrue()
        assertThat(CoFill.together(touch, stop("s", Side.BUY, "1"))).isFalse()
    }

    @Test
    fun `an order below the volume minimum is rejected by the matching venue and forgotten`() {
        val f = ExchangeFixture()
        f.tick(f.sep, "63000")

        val ack = f.sim.submit(f.market("tiny", Side.BUY, "0.0005"))

        assertThat(ack.accepted).isFalse()
        val rejected = f.only<BrokerEvent.OrderRejected>().single()
        assertThat(rejected.clientOrderId).isEqualTo("tiny")
        assertThat(rejected.reason).contains("below venue volumeMin")
        f.tick(f.sep, "63010")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }
}
