package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.RecordingBroker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestoreOtoTest {
    @Test
    fun `restore re-arms OTO children before broker recovery replays the parent fill`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val parent =
            OrderRequest.Limit(
                id = "oto-parent",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val child =
            OrderRequest.Limit(
                id = "oto-child",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal("2010"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val oto =
            OrderRequest.OTO(
                id = "oto-wrapper",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf(parent.id to oto))
        val clock = FixedClock(10L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val broker =
            RecordingBroker(fake) { recovered ->
                val restoredParent = recovered.single().request
                bus.publish(
                    BrokerEvent.OrderFilled(
                        clientOrderId = restoredParent.id,
                        brokerOrderId = "venue-parent",
                        symbol = restoredParent.symbol,
                        side = restoredParent.side,
                        price = BigDecimal("2000"),
                        quantity = restoredParent.quantity,
                        strategyId = restoredParent.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactly(parent.id)
        assertThat(fake.submits.map { it.id }).containsExactly(child.id)
        assertThat(manager.getOrder(parent.id)?.state).isEqualTo(OrderState.FILLED)
        assertThat(manager.getOrder(child.id)?.state).isEqualTo(OrderState.WORKING)
        assertThat(persistor.loadPendingOrders("alpha")).containsExactlyEntriesOf(mapOf(child.id to child))
    }
}
