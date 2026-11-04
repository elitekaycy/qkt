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
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import com.qkt.persistence.StatePersistor
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The heartbeat's trailing-stop flush rewrites only the strategies whose stops moved: in a portfolio session every
 * child's file used to be flushed, one synchronous write each on the engine thread, whenever any stop moved (#1609).
 */
class OrderManagerTrailingFlushTest {
    private class CountingPersistor : StatePersistor by NoopStatePersistor() {
        val trailingSaves = CopyOnWriteArrayList<String>()

        override fun saveTrailingStops(
            strategyId: String,
            stops: List<PersistedTrailingStop>,
        ) {
            trailingSaves += strategyId
        }
    }

    private fun trailingStop(
        id: String,
        symbol: String,
        strategyId: String,
    ) = OrderRequest.TrailingStop(
        id = id,
        symbol = symbol,
        side = Side.SELL,
        quantity = Money.of("1"),
        trailAmount = Money.of("5"),
        trailMode = TrailMode.ABSOLUTE,
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        strategyId = strategyId,
    )

    @Test
    fun `a stop that moved is flushed alone, and a flush with nothing new writes nothing`() {
        val persistor = CountingPersistor()
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val orders = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)
        orders.submit(trailingStop("a-trail", "X", "alpha"))
        orders.submit(trailingStop("b-trail", "Y", "beta"))
        bus.publish(TickEvent(Tick("X", Money.of("100"), 1L)))
        bus.publish(TickEvent(Tick("Y", Money.of("50"), 1L)))
        orders.persistTrailingStateIfDirty()
        persistor.trailingSaves.clear()

        bus.publish(TickEvent(Tick("X", Money.of("110"), 2L)))
        orders.persistTrailingStateIfDirty()
        orders.persistTrailingStateIfDirty()

        assertThat(persistor.trailingSaves).containsExactly("alpha")
    }
}
