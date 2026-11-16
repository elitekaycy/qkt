package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.ocoLeg
import com.qkt.app.OrderManagerScaleOutFixtures.marketBasis
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTrailingStop
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Persisted order state that contradicts itself aborts the restore instead of resuming a wrong book. */
class OrderManagerRestoreCorruptStateTest {
    private val clock = FixedClock(10L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val persistor = NoopStatePersistor()
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
    private val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

    private fun limit(
        id: String,
        symbol: String = "X",
    ) = OrderRequest.Limit(id, symbol, Side.BUY, BigDecimal.ONE, BigDecimal("100"), TimeInForce.GTC, 0L, "alpha")

    private fun trail(id: String) =
        OrderRequest.TrailingStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = BigDecimal.ONE,
            trailAmount = BigDecimal("5"),
            trailMode = TrailMode.ABSOLUTE,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private fun restoreFails(message: String) {
        assertThatThrownBy { manager.restore(listOf("alpha")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(message)
    }

    @Test
    fun `an oto stored under an id that is not its parent's aborts the restore`() {
        val oto =
            OrderRequest.OTO(
                id = "oto-1",
                symbol = "X",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                parent = limit("parent-1"),
                children = listOf(limit("child-1")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf("stray" to oto))

        restoreFails("persisted OTO oto-1 keyed by stray instead of parent parent-1")
        assertThat(broker.recovered).isEmpty()
    }

    @Test
    fun `a scale out stored under an id that is neither its own nor its basis's aborts the restore`() {
        persistor.savePendingOrders("alpha", mapOf("stray" to scaleOut(marketBasis("1"), "alpha")))

        restoreFails("persisted ScaleOut s1 keyed by stray instead of basis e1")
    }

    @Test
    fun `a pending scale out whose id another restored order already holds aborts the restore`() {
        val pending = linkedMapOf("s1" to limit("s1"), "e1" to scaleOut(marketBasis("1"), "alpha"))
        persistor.savePendingOrders("alpha", pending)

        restoreFails("persisted ScaleOut s1 collides with already-restored order state")
    }

    @Test
    fun `an active scale out whose id a restored oco leg already holds aborts the restore`() {
        persistor.saveOcoLegs("alpha", listOf(ocoLeg("s1", Side.SELL, "t-1", siblings = listOf("other"))))
        val pending = linkedMapOf("s1" to scaleOut(marketBasis("1"), "alpha"), "s1-leg-0" to limit("s1-leg-0"))
        persistor.savePendingOrders("alpha", pending)

        restoreFails("persisted active ScaleOut s1 collides with already-restored order state")
    }

    @Test
    fun `an engine held order stored under another order's id aborts the restore`() {
        persistor.savePendingOrders("alpha", mapOf("trail-a" to trail("trail-b")))

        restoreFails("persisted engine-held order trail-a contains request trail-b")
        assertThat(manager.getOrder("trail-a")).isNull()
    }

    @Test
    fun `saved trailing progress that names another order's request aborts the restore`() {
        val saved =
            PersistedTrailingStop(
                clientOrderId = "trail-a",
                brokerOrderId = null,
                strategyId = "alpha",
                request = trail("trail-b"),
                armed = true,
                hwm = BigDecimal("105"),
            )
        persistor.saveTrailingStops("alpha", listOf(saved))

        restoreFails("persisted engine-held order trail-a contains request trail-b")
    }
}
