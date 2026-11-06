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
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedOcoLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerRestoreOcoTrailTest {
    @Test
    fun `an oco trailing leg restored without its saved mark restarts trailing from the next tick`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val persistor = NoopStatePersistor()
        val trail =
            OrderRequest.TrailingStop(
                id = "oco-trail",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                trailAmount = BigDecimal("5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val leg = PersistedOcoLeg("oco-trail", "v-1", "alpha", trail, listOf("oco-target"))
        persistor.saveOcoLegs("alpha", listOf(leg))
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(manager.getOrder("oco-trail")!!.state).isEqualTo(OrderState.PENDING)
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("2000"), 1L)))
        assertThat(broker.submits).isEmpty()
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1994"), 2L)))
        assertThat(broker.submits.single().id).isEqualTo("oco-trail")
    }
}
