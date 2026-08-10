package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerScaleOutTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `ScaleOut submits basis only`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = basis,
                legs =
                    listOf(
                        ScaleOutLeg(Money.of("110"), Money.of("0.33")),
                        ScaleOutLeg(Money.of("120"), Money.of("0.33")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("e1")
    }

    @Test
    fun `basis fill activates leg orders sized by fraction`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(
                    OrderTypeCapability.MARKET,
                    OrderTypeCapability.LIMIT,
                    OrderTypeCapability.IF_TOUCHED,
                ),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = basis,
                legs =
                    listOf(
                        ScaleOutLeg(Money.of("110"), Money.of("0.5")),
                        ScaleOutLeg(Money.of("120"), Money.of("0.5")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        val legs = om.pendingOrders().map { it.request }.filterIsInstance<OrderRequest.IfTouched>()
        assertThat(legs).hasSize(2)
        assertThat(legs.first().quantity).isEqualByComparingTo(Money.of("1.5"))
    }

    @Test
    fun `ScaleOut leaves legs dormant until a terminal basis fill`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.IF_TOUCHED),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val basis =
            OrderRequest.Limit(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("110"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "e1",
                brokerOrderId = "e1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
            ),
        )

        assertThat(om.getOrder("s1-leg-0")).isNull()

        broker.emitFill(basis, price = Money.of("100"), quantity = Money.of("0.6"))

        assertThat(om.getOrder("s1-leg-0")?.state).isEqualTo(OrderState.PENDING)
    }

    @Test
    fun `ScaleOut leg side is opposite of basis side`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.IF_TOUCHED))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        val leg =
            om
                .pendingOrders()
                .map { it.request }
                .filterIsInstance<OrderRequest.IfTouched>()
                .single()
        assertThat(leg.side).isEqualTo(Side.SELL)
    }

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

    @Test
    fun `restart restores wrapper activation and ticketed remaining exits`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val basis = marketBasis(quantity = "2")
        val scaleOut = scaleOut(basis, strategyId = "alpha")
        val firstBus = newBus()
        val firstBroker = FakeBroker(firstBus, FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        OrderManager(firstBroker, firstBus, MarketPriceTracker(), FixedClock(0L), persistor).submit(scaleOut)

        assertThat(persistor.loadPendingOrders("alpha"))
            .containsExactlyEntriesOf(mapOf(basis.id to scaleOut.copy(basis = basis.copy(strategyId = "alpha"))))

        val secondBus = newBus()
        val secondBroker = FakeBroker(secondBus, FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val second = OrderManager(secondBroker, secondBus, MarketPriceTracker(), FixedClock(0L), persistor)
        second.restore(listOf("alpha"))
        secondBus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = basis.id,
                brokerOrderId = "9001",
                symbol = basis.symbol,
                side = basis.side,
                price = Money.of("100"),
                quantity = basis.quantity,
                strategyId = "alpha",
            ),
        )
        assertThat(persistor.loadPendingOrders("alpha").keys)
            .containsExactlyInAnyOrder("s1", "s1-leg-0", "s1-leg-1")

        val thirdBus = newBus()
        val thirdBroker = FakeBroker(thirdBus, FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val third = OrderManager(thirdBroker, thirdBus, MarketPriceTracker(), FixedClock(0L), persistor)
        third.restore(listOf("alpha"))
        assertThat(thirdBroker.recovered).isEmpty()
        assertThat(third.pendingOrders().map { it.id }).containsExactlyInAnyOrder("s1-leg-0", "s1-leg-1")

        thirdBus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))

        val fired = thirdBroker.submits.filterIsInstance<OrderRequest.Market>().single()
        assertThat(fired.id).isEqualTo("s1-leg-0")
        assertThat(fired.strategyId).isEqualTo("alpha")
        assertThat(fired.closesTicket).isEqualTo("9001")
        assertThat(fired.partialClose).isTrue()
        thirdBroker.emitFill(fired, Money.of("110"))
        assertThat(persistor.loadPendingOrders("alpha").keys)
            .containsExactlyInAnyOrder("s1", "s1-leg-1")

        val fourthBus = newBus()
        val fourthBroker = FakeBroker(fourthBus, FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val fourth = OrderManager(fourthBroker, fourthBus, MarketPriceTracker(), FixedClock(0L), persistor)
        fourth.restore(listOf("alpha"))
        val remaining =
            fourth
                .pendingOrders()
                .map { it.request }
                .filterIsInstance<OrderRequest.IfTouched>()
                .single()
        assertThat(remaining.id).isEqualTo("s1-leg-1")
        assertThat(remaining.closesTicket).isEqualTo("9001")
        fourth.cancel("s1")
        assertThat(fourthBroker.cancels).isEmpty()
        assertThat(fourth.getOrder("s1-leg-1")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(persistor.loadPendingOrders("alpha")).isEmpty()
    }

    @Test
    fun `cancelling ScaleOut before basis fill cancels basis`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("s1")

        assertThat(broker.cancels).contains("e1")
    }

    private fun marketBasis(quantity: String): OrderRequest.Market =
        OrderRequest.Market(
            id = "e1",
            symbol = "X",
            side = Side.BUY,
            quantity = BigDecimal(quantity),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun scaleOut(
        basis: OrderRequest.Market,
        strategyId: String,
    ): OrderRequest.ScaleOut =
        OrderRequest.ScaleOut(
            id = "s1",
            symbol = basis.symbol,
            side = basis.side,
            quantity = basis.quantity,
            basis = basis,
            legs =
                listOf(
                    ScaleOutLeg(Money.of("110"), Money.of("0.5")),
                    ScaleOutLeg(Money.of("120"), Money.of("0.5")),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )
}
