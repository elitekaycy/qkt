package com.qkt.risk.rules

import com.qkt.broker.Broker
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarginFloorTest {
    private class MutableMarginBroker(
        var level: BigDecimal?,
    ) : Broker,
        MarginLevelProvider {
        override val name = "fake"
        override val capabilities = emptySet<OrderTypeCapability>()
        override val supportsMarginLevel = true

        override fun submit(request: OrderRequest) = error("not used")

        override fun cancel(orderId: String) = error("not used")

        override fun modify(
            orderId: String,
            changes: com.qkt.broker.OrderModification,
        ) = error("not used")

        override fun marginLevel(): BigDecimal? = level
    }

    private fun brokerAt(level: String?): Broker =
        object : Broker, MarginLevelProvider {
            override val name = "fake"
            override val capabilities = emptySet<OrderTypeCapability>()
            override val supportsMarginLevel = true

            override fun submit(request: OrderRequest) = error("not used")

            override fun cancel(orderId: String) = error("not used")

            override fun modify(
                orderId: String,
                changes: com.qkt.broker.OrderModification,
            ) = error("not used")

            override fun marginLevel(): BigDecimal? = level?.let(::BigDecimal)
        }

    private fun entry(side: Side = Side.BUY) =
        OrderRequest.Market(
            id = "m1",
            symbol = "XAUUSD",
            side = side,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `entry rejected below the floor, approved above it`() {
        val positions = PositionTracker()
        val low = MarginFloor(brokerAt("150"), BigDecimal("200"))
        val decision = low.evaluate(entry(), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("margin level")

        val high = MarginFloor(brokerAt("850"), BigDecimal("200"))
        assertThat(high.evaluate(entry(), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `risk-reducing orders pass even below the floor`() {
        // Long 1 lot, margin level collapsed to 120%: the CLOSE must pass — shrinking
        // exposure is how the level recovers.
        val positions = PositionTracker()
        positions.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "o1",
                brokerOrderId = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2000"),
                quantity = Money.of("1"),
                timestamp = 0L,
            ),
        )
        val rule = MarginFloor(brokerAt("120"), BigDecimal("200"))
        assertThat(rule.evaluate(entry(Side.SELL), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(entry(Side.BUY), positions)).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `margin floor blocks reentry and reopens after headroom recovers`() {
        val broker = MutableMarginBroker(BigDecimal("850"))
        val rule = MarginFloor(broker, BigDecimal("200"))
        val positions = PositionTracker()
        positions.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "o1",
                brokerOrderId = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2000"),
                quantity = Money.of("1"),
                timestamp = 0L,
            ),
        )

        assertThat(rule.evaluate(entry(Side.BUY), positions)).isEqualTo(Decision.Approve)

        broker.level = BigDecimal("120")
        assertThat(rule.evaluate(entry(Side.BUY), positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(entry(Side.SELL), positions)).isEqualTo(Decision.Approve)

        broker.level = BigDecimal("850")
        assertThat(rule.evaluate(entry(Side.BUY), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `repeated missing margin reads fail closed for new exposure`() {
        val rule = MarginFloor(brokerAt(null), BigDecimal("200"))
        assertThat(rule.evaluate(entry(), PositionTracker())).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(entry(), PositionTracker())).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(entry(), PositionTracker())).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `unsupported margin reads never enter degradation`() {
        val broker =
            object : Broker {
                override val name = "paper"
                override val capabilities = emptySet<OrderTypeCapability>()

                override fun submit(request: OrderRequest) = error("not used")

                override fun cancel(orderId: String) = error("not used")
            }
        val rule = MarginFloor(broker, BigDecimal("200"))

        repeat(5) {
            assertThat(rule.evaluate(entry(), PositionTracker())).isEqualTo(Decision.Approve)
        }
    }
}
