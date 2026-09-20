package com.qkt.app

import com.qkt.app.OrderManagerTrailingStopRestoreFixtures.RecoveryRecordingBroker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTrailingStopRestoreStopLimitTest {
    private fun stopLimit(id: String = "stop-limit") =
        OrderRequest.StopLimit(
            id = id,
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            stopPrice = Money.of("110"),
            limitPrice = Money.of("111"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `emulated stop-limit restores pending and only reaches broker after its trigger`() {
        val persistor = NoopStatePersistor()
        persistor.savePendingOrders("alpha", mapOf("stop-limit" to stopLimit()))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val delegate = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val broker = RecoveryRecordingBroker(delegate)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(manager.getOrder("stop-limit")?.state).isEqualTo(OrderState.PENDING)
        assertThat(broker.recovered).isEmpty()
        assertThat(delegate.submits).isEmpty()

        bus.publish(TickEvent(Tick("X", Money.of("109"), 1L)))
        assertThat(delegate.submits).isEmpty()
        bus.publish(TickEvent(Tick("X", Money.of("110"), 2L)))

        val fired = delegate.submits.single() as OrderRequest.Limit
        assertThat(fired.limitPrice).isEqualByComparingTo("111")
    }

    @Test
    fun `native stop-limit still restores working through venue recovery`() {
        val persistor = NoopStatePersistor()
        persistor.savePendingOrders("alpha", mapOf("stop-limit" to stopLimit()))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val delegate =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP_LIMIT),
            )
        val broker = RecoveryRecordingBroker(delegate)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(manager.getOrder("stop-limit")?.state).isEqualTo(OrderState.WORKING)
        assertThat(broker.recovered.map { it.id }).containsExactly("stop-limit")
    }
}
