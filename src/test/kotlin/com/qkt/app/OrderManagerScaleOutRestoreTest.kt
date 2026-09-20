package com.qkt.app

import com.qkt.app.OrderManagerScaleOutFixtures.marketBasis
import com.qkt.app.OrderManagerScaleOutFixtures.newBus
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerScaleOutRestoreTest {
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
}
