package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContinuousOrderShapesTest {
    private val f = ContinuousFixture()

    @Test
    fun `a stop limit works on the front contract and fills once its stop and limit are reached`() {
        f.tick("63000.0")
        val stopLimit =
            OrderRequest.StopLimit(
                "sl",
                f.front,
                Side.BUY,
                BigDecimal("0.01"),
                BigDecimal("63100.0"),
                BigDecimal("63150.0"),
                TimeInForce.GTC,
                f.clock.time,
                "s",
            )

        assertThat(f.broker.submit(stopLimit).accepted).isTrue()
        f.tick("63050.0")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
        f.tick("63120.0")

        val fill = f.only<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("sl")
        assertThat(fill.symbol).isEqualTo(f.front)
        assertThat(fill.price).isEqualByComparingTo("63120.0")
    }

    @Test
    fun `an order shape the contract venue cannot take is refused on the stream`() {
        f.tick("63000.0")
        val touch =
            OrderRequest.IfTouched(
                id = "it",
                symbol = f.front,
                side = Side.BUY,
                quantity = BigDecimal("0.01"),
                triggerPrice = BigDecimal("62000.0"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = f.clock.time,
                strategyId = "s",
            )

        val ack = f.broker.submit(touch)

        assertThat(ack.accepted).isFalse()
        val rejected = f.only<BrokerEvent.OrderRejected>().single()
        assertThat(rejected.clientOrderId).isEqualTo("it")
        assertThat(rejected.reason).endsWith("does not accept IfTouched orders")
    }

    @Test
    fun `a venue rejection of an engine order reaches the engine under the order's own id`() {
        f.tick("63000.0")

        f.broker.submit(f.market("tiny", Side.BUY, qty = "0.0005"))

        val rejected = f.only<BrokerEvent.OrderRejected>().single()
        assertThat(rejected.clientOrderId).isEqualTo("tiny")
        assertThat(rejected.reason).contains("below venue volumeMin")
        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }
}
