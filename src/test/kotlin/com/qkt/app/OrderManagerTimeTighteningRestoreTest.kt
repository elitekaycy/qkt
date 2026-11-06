package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTimeTighteningRestoreTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val persistor = NoopStatePersistor()
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))

    private val stop =
        OrderRequest.TimeTighteningStop(
            id = "tt-1",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal.ONE,
            entryPrice = BigDecimal("2000"),
            initialDistance = BigDecimal("6"),
            tightenBy = BigDecimal("1"),
            intervalMs = 3_600_000L,
            floorDistance = BigDecimal("2"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private fun restore(): OrderManager {
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)
        manager.restore(listOf("alpha"))
        return manager
    }

    @Test
    fun `a time tightening stop restored without saved progress starts from its initial distance`() {
        persistor.savePendingOrders("alpha", mapOf(stop.id to stop))
        val manager = restore()
        assertThat(manager.getOrder("tt-1")!!.state).isEqualTo(OrderState.PENDING)

        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1994.5"), 1L)))
        assertThat(broker.submits).isEmpty()
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1994"), 2L)))

        val close = broker.submits.single() as OrderRequest.Market
        assertThat(close.id).isEqualTo("tt-1")
        assertThat(close.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a time tightening stop restored with saved progress resumes at its tightened level`() {
        persistor.savePendingOrders("alpha", mapOf(stop.id to stop))
        persistor.saveTrailingStops(
            "alpha",
            listOf(
                PersistedTrailingStop(
                    clientOrderId = "tt-1",
                    brokerOrderId = null,
                    strategyId = "alpha",
                    request = stop,
                    armed = false,
                    hwm = BigDecimal("2000"),
                    elapsedIntervals = 3L,
                    stopLevel = BigDecimal("1997"),
                ),
            ),
        )
        restore()

        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1997.5"), 1L)))
        assertThat(broker.submits).isEmpty()
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1996.9"), 2L)))

        assertThat(broker.submits.single().id).isEqualTo("tt-1")
    }
}
