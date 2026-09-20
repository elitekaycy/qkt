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
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTrailingStopRestoreBasicTrailTest {
    private fun trailingStop(id: String = "trail") =
        OrderRequest.TrailingStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            trailAmount = Money.of("5"),
            trailMode = TrailMode.ABSOLUTE,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private fun trailingStopLimit(id: String = "trail-limit") =
        OrderRequest.TrailingStopLimit(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            trailAmount = Money.of("5"),
            trailMode = TrailMode.ABSOLUTE,
            limitOffset = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `basic trailing stop restores pending at its persisted hwm without venue recovery`() {
        val persistor = NoopStatePersistor()
        val beforeClock = FixedClock(0L)
        val beforeBus = EventBus(beforeClock, MonotonicSequenceGenerator())
        val beforeBroker = FakeBroker(beforeBus, beforeClock, setOf(OrderTypeCapability.MARKET))
        val before = OrderManager(beforeBroker, beforeBus, MarketPriceTracker(), beforeClock, persistor)
        before.submit(trailingStop())
        beforeBus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        before.persistTrailingStateIfDirty()

        assertThat(persistor.loadTrailingStops("alpha").single().hwm).isEqualByComparingTo("110")

        val afterClock = FixedClock(2L)
        val afterBus = EventBus(afterClock, MonotonicSequenceGenerator())
        val afterDelegate = FakeBroker(afterBus, afterClock, setOf(OrderTypeCapability.MARKET))
        val afterBroker = RecoveryRecordingBroker(afterDelegate)
        val after = OrderManager(afterBroker, afterBus, MarketPriceTracker(), afterClock, persistor)

        after.restore(listOf("alpha"))

        assertThat(after.getOrder("trail")?.state).isEqualTo(OrderState.PENDING)
        assertThat(afterBroker.recovered).isEmpty()
        // The first post-restart tick is already below the saved 105 stop. Resetting HWM to this
        // tick would move the stop to 100 and miss the trigger.
        afterBus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        assertThat(afterDelegate.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `basic trailing stop-limit restores its hwm and fires the derived limit`() {
        val persistor = NoopStatePersistor()
        persistor.savePendingOrders("alpha", mapOf("trail-limit" to trailingStopLimit()))
        persistor.saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = "trail-limit",
                    brokerOrderId = "trail-limit",
                    strategyId = "alpha",
                    request = trailingStopLimit(),
                    armed = false,
                    hwm = Money.of("110"),
                ),
            ),
        )
        val clock = FixedClock(2L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val delegate = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val broker = RecoveryRecordingBroker(delegate)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        assertThat(manager.getOrder("trail-limit")?.state).isEqualTo(OrderState.WORKING)
        assertThat(broker.recovered).isEmpty()
        val fired = delegate.submits.single() as OrderRequest.Limit
        assertThat(fired.limitPrice).isEqualByComparingTo("104")
    }
}
