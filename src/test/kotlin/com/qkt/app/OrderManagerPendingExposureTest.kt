package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.rules.MaxPositionSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerPendingExposureTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
    private val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)

    private fun limit(
        id: String,
        quantity: String,
        strategyId: String = "alpha",
    ) = OrderRequest.Limit(
        id = id,
        symbol = "X",
        side = Side.BUY,
        quantity = Money.of(quantity),
        limitPrice = Money.of("90"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
        strategyId = strategyId,
    )

    @Test
    fun `working entry blocks another order that would breach the cap`() {
        val risk = RiskEngine(listOf(MaxPositionSize("X", Money.of("3"))), PositionTracker())
        risk.bindPendingExposure(manager)
        val first = limit("first", "2")

        assertThat(risk.approve(first)).isEqualTo(Decision.Approve)
        manager.submit(first)

        assertThat(risk.approve(limit("second", "2"))).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `partial fills reduce and cancellation releases pending exposure`() {
        val first = limit("first", "2")
        manager.submit(first)
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = first.id,
                brokerOrderId = first.id,
                symbol = first.symbol,
                side = first.side,
                price = Money.of("90"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
                timestamp = clock.now(),
            ),
        )

        assertThat(manager.quantityFor("X", Side.BUY, null)).isEqualByComparingTo(Money.of("1"))

        manager.cancel(first.id)

        assertThat(manager.quantityFor("X", Side.BUY, null)).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `OCO reserves only the largest mutually exclusive same-side leg`() {
        manager.submit(
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                leg1 = limit("leg-1", "2"),
                leg2 = limit("leg-2", "2"),
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
                strategyId = "alpha",
            ),
        )

        assertThat(manager.quantityFor("X", Side.BUY, null)).isEqualByComparingTo(Money.of("2"))
        assertThat(manager.quantityFor("X", Side.BUY, "alpha")).isEqualByComparingTo(Money.of("2"))
        assertThat(manager.quantityFor("X", Side.BUY, "beta")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `submit rejection without a broker event releases exposure`() {
        val silentRejectingBroker =
            object : Broker {
                override val name = "silent-reject"
                override val capabilities = setOf(OrderTypeCapability.LIMIT)

                override fun submit(request: OrderRequest) =
                    SubmitAck(request.id, brokerOrderId = null, accepted = false, rejectReason = "rejected")

                override fun cancel(orderId: String) = Unit
            }
        val rejectingManager = OrderManager(silentRejectingBroker, bus, MarketPriceTracker(), clock)

        rejectingManager.submit(limit("rejected", "2"))

        assertThat(rejectingManager.quantityFor("X", Side.BUY, null)).isEqualByComparingTo(Money.ZERO)
    }
}
