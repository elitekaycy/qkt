package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.instrument.PriceAdjustment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContinuousContractBrokerTest {
    private val f = ContinuousFixture()

    @Test
    fun `a market order fills on the front contract and the engine sees a continuous fill`() {
        f.tick("63000.0")

        f.broker.submit(f.market("buy", Side.BUY))

        val fill = f.only<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("buy")
        assertThat(fill.symbol).isEqualTo(f.front)
        assertThat(fill.price).isEqualByComparingTo("63000.0")
        assertThat(fill.strategyId).isEqualTo("s")
        assertThat(f.only<BrokerEvent.OrderAccepted>().single().clientOrderId).isEqualTo("buy")
    }

    @Test
    fun `a buy limit between ticks is snapped down in contract space`() {
        f.tick("63000.0")
        f.broker.submit(f.limit("bid", Side.BUY, "62999.95"))

        f.tick("63000.0")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()

        f.tick("62999.9")
        assertThat(f.only<BrokerEvent.OrderFilled>().single().price).isEqualByComparingTo("62999.9")
    }

    @Test
    fun `a sell stop between ticks is snapped down so it never triggers early`() {
        f.tick("63000.0")
        f.broker.submit(f.stop("protect", Side.SELL, "62900.05"))

        f.tick("62900.0")

        val fill = f.only<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("protect")
        assertThat(fill.price).isEqualByComparingTo("62900.0")
    }

    @Test
    fun `a cancel reaches the contract order and the engine sees its own id`() {
        f.tick("63000.0")
        f.broker.submit(f.limit("rest", Side.BUY, "62000.0"))

        f.broker.cancel("rest")
        f.tick("61000.0")

        assertThat(f.only<BrokerEvent.OrderCancelled>().single().clientOrderId).isEqualTo("rest")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `only the declared continuous streams are supported`() {
        assertThat(f.broker.supports(f.front)).isTrue()
        assertThat(f.broker.supports("EXNESS:XAUUSD")).isFalse()
        assertThat(f.broker.supports("BINANCE_UM:BTCUSDT_241227")).isFalse()
    }

    @Test
    fun `orders on a stream not adjusted by panama are rejected with the reason`() {
        val ratio = ContinuousFixture(adjust = PriceAdjustment.RATIO)
        ratio.tick("63000.0")

        val ack = ratio.broker.submit(ratio.market("buy", Side.BUY))

        assertThat(ack.accepted).isFalse()
        assertThat(ratio.only<BrokerEvent.OrderRejected>().single().reason)
            .contains("adjust: panama")
            .contains("ratio")
    }

    @Test
    fun `an order before the stream is served is rejected, not thrown`() {
        val early = ContinuousFixture(startIso = "2024-06-01T00:00:00Z")

        val ack = early.broker.submit(early.market("buy", Side.BUY))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("BINANCE_UM:BTCUSDT@front")
    }
}
