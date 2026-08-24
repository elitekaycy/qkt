package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import com.qkt.persistence.StatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #436 — the engine-side armed trailing-stop monitor must survive a restart. The arm flag and
 * high-water mark live only in memory on [OrderManager]; without persistence a winner that had
 * already armed comes back stop-less and never re-arms (the arming gate is keyed on `== false`,
 * so a missing entry is skipped entirely).
 */
class OrderManagerTrailingStopRestoreTest {
    private class RecoveryRecordingBroker(
        delegate: Broker,
    ) : Broker by delegate {
        val recovered = mutableListOf<ManagedOrder>()

        override fun recoverPendingOrders(orders: List<ManagedOrder>): Set<String> {
            recovered += orders
            return orders.mapTo(LinkedHashSet()) { it.id }
        }
    }

    private class CountingPersistor(
        private val delegate: NoopStatePersistor = NoopStatePersistor(),
    ) : StatePersistor by delegate {
        var trailingSaves = 0

        override fun saveTrailingStops(
            strategyId: String,
            stops: List<PersistedTrailingStop>,
        ) {
            trailingSaves++
            delegate.saveTrailingStops(strategyId, stops)
        }
    }

    private fun armedSl(id: String) =
        OrderRequest.ArmedTrailingStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            trailDistance = Money.of("5"),
            mfeThreshold = Money.of("10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

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
    fun `arming an armed trail persists its arm flag and high-water mark`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val persistor = NoopStatePersistor()
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor = persistor)

        om.submit(armedSl("b1-sl"))
        // Price rises to 110: MFE = 10 ≥ threshold → arms, hwm = 110.
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))

        val saved = persistor.loadTrailingStops("alpha")
        assertThat(saved).hasSize(1)
        assertThat(saved[0].clientOrderId).isEqualTo("b1-sl")
        assertThat(saved[0].armed).isTrue
        assertThat(saved[0].hwm).isEqualByComparingTo("110")
    }

    @Test
    fun `bounded flush persists an advanced hwm once and ignores unchanged prices`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val persistor = CountingPersistor()
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor = persistor)

        om.submit(armedSl("b1-sl"))
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        persistor.trailingSaves = 0

        bus.publish(TickEvent(Tick("X", Money.of("120"), 2L)))
        assertThat(persistor.loadTrailingStops("alpha").single().hwm).isEqualByComparingTo("110")

        om.persistTrailingStateIfDirty()
        assertThat(persistor.trailingSaves).isEqualTo(1)
        assertThat(persistor.loadTrailingStops("alpha").single().hwm).isEqualByComparingTo("120")

        bus.publish(TickEvent(Tick("X", Money.of("119"), 3L)))
        om.persistTrailingStateIfDirty()
        assertThat(persistor.trailingSaves).isEqualTo(1)
    }

    @Test
    fun `restore resumes an armed trail at its persisted hwm and fires on a drop`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val persistor = NoopStatePersistor()
        // A winner that armed before the restart: armed, hwm 110 → trail sits at 110 − 5 = 105.
        persistor.saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = "b1-sl",
                    brokerOrderId = "b1-sl",
                    strategyId = "alpha",
                    request = armedSl("b1-sl"),
                    armed = true,
                    hwm = Money.of("110"),
                ),
            ),
        )
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                closeTicketFor = { _, exitId -> if (exitId == "b1-sl") "tkt-99" else null },
            )

        om.restore(listOf("alpha"))
        // A drop to 104 (≤ trail 105) fires immediately — proving the trail resumed ARMED at hwm
        // 110, not reset to the entry (100), where 104 would not fire and it would re-arm first.
        bus.publish(TickEvent(Tick("X", Money.of("104"), 1L)))

        val fired = broker.submits.firstOrNull { it.id == "b1-sl" } as? OrderRequest.Market
        assertThat(fired).isNotNull
        assertThat(fired!!.closesTicket).isEqualTo("tkt-99")
        assertThat(fired.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `restore of an un-armed trail does not fire on a shallow drop`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val persistor = NoopStatePersistor()
        // Not armed yet: hwm at the entry (100) → pre-arm stop sits at 100 − 5 = 95.
        persistor.saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = "b1-sl",
                    brokerOrderId = "b1-sl",
                    strategyId = "alpha",
                    request = armedSl("b1-sl"),
                    armed = false,
                    hwm = Money.of("100"),
                ),
            ),
        )
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                closeTicketFor = { _, exitId -> if (exitId == "b1-sl") "tkt-99" else null },
            )

        om.restore(listOf("alpha"))
        // 104 is above the un-armed pre-arm stop (95), so it must not fire — the restored
        // un-armed state is honored, not treated as armed.
        bus.publish(TickEvent(Tick("X", Money.of("104"), 1L)))

        assertThat(broker.submits.none { it.id == "b1-sl" }).isTrue
    }

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
