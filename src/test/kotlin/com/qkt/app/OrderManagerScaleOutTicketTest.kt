package com.qkt.app

import com.qkt.app.OrderManagerScaleOutFixtures.marketBasis
import com.qkt.app.OrderManagerScaleOutFixtures.newBus
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerScaleOutTicketTest {
    @Test
    fun `basis completion uses cumulative fill and owns its MT5 ticket`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val basis = marketBasis(quantity = "3")

        om.submit(scaleOut(basis, strategyId = "alpha"))
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = basis.id,
                brokerOrderId = "9001",
                symbol = basis.symbol,
                side = basis.side,
                price = Money.of("100"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
                strategyId = "alpha",
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = basis.id,
                brokerOrderId = "9001",
                symbol = basis.symbol,
                side = basis.side,
                price = Money.of("101"),
                quantity = Money.of("0.5"),
                strategyId = "alpha",
            ),
        )

        val legs = om.pendingOrders().map { it.request }.filterIsInstance<OrderRequest.IfTouched>()
        assertThat(legs).hasSize(2)
        assertThat(legs).allSatisfy { leg ->
            assertThat(leg.quantity).isEqualByComparingTo(Money.of("0.75"))
            assertThat(leg.strategyId).isEqualTo("alpha")
            assertThat(leg.closesTicket).isEqualTo("9001")
            assertThat(leg.partialClose).isTrue()
        }
    }

    @Test
    fun `live hedging fill without owned ticket never arms opposite exits`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.MULTI_POSITION_PER_SYMBOL),
            )
        val failures = mutableListOf<String>()
        val om =
            OrderManager(
                broker = broker,
                bus = bus,
                priceProvider = MarketPriceTracker(),
                clock = clock,
                requireArmedTrailTicket = true,
                onProtectionFailure = { _, message -> failures += message },
            )
        val basis = marketBasis(quantity = "2")
        om.submit(scaleOut(basis, strategyId = "alpha"))

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = basis.id,
                brokerOrderId = null,
                symbol = basis.symbol,
                side = basis.side,
                price = Money.of("100"),
                quantity = basis.quantity,
                strategyId = "alpha",
            ),
        )

        assertThat(om.pendingOrders()).isEmpty()
        assertThat(broker.submits.map { it.id }).containsExactly(basis.id)
        assertThat(failures.single()).contains("without an owned position ticket")
    }
}
