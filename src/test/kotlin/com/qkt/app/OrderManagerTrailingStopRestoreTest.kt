package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #436 — the engine-side armed trailing-stop monitor must survive a restart. The arm flag and
 * high-water mark live only in memory on [OrderManager]; without persistence a winner that had
 * already armed comes back stop-less and never re-arms (the arming gate is keyed on `== false`,
 * so a missing entry is skipped entirely).
 */
class OrderManagerTrailingStopRestoreTest {
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
}
