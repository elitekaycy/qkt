package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.LogBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.PersistedOcoLeg
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestoreTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private class RecordingBroker(
        delegate: Broker,
    ) : Broker by delegate {
        val recovered = mutableListOf<ManagedOrder>()

        override fun recoverPendingOrders(orders: List<ManagedOrder>) {
            recovered += orders
        }
    }

    private fun ocoLeg(
        id: String,
        side: Side,
        ticket: String,
        siblings: List<String>,
    ) = PersistedOcoLeg(
        clientOrderId = id,
        brokerOrderId = ticket,
        strategyId = "alpha",
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "XAUUSD",
                side = side,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        siblingIds = siblings,
    )

    @Test
    fun `restore rebuilds working legs and sibling linkage from the persistor`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.saveOcoLegs(
            "alpha",
            listOf(
                ocoLeg("oco1-a", Side.BUY, "1001", listOf("oco1-b")),
                ocoLeg("oco1-b", Side.SELL, "1002", listOf("oco1-a")),
            ),
        )
        val broker = RecordingBroker(LogBroker(newBus(), FixedClock(0L)))
        val om = OrderManager(broker, newBus(), MarketPriceTracker(), FixedClock(0L), persistor)
        assertThat(om.getOrder("oco1-a")).isNull()

        om.restore(listOf("alpha"))

        val a = om.getOrder("oco1-a")!!
        assertThat(a.state).isEqualTo(OrderState.WORKING)
        assertThat(a.brokerOrderId).isEqualTo("1001")
        assertThat(om.getOrder("oco1-b")!!.state).isEqualTo(OrderState.WORKING)
        assertThat(om.siblingsOf("oco1-a")).containsExactly("oco1-b")
        assertThat(broker.recovered.map { it.id }).containsExactlyInAnyOrder("oco1-a", "oco1-b")
    }

    @Test
    fun `restore is a no-op when nothing was persisted`(
        @TempDir tmp: Path,
    ) {
        val broker = RecordingBroker(LogBroker(newBus(), FixedClock(0L)))
        val om = OrderManager(broker, newBus(), MarketPriceTracker(), FixedClock(0L), FileStatePersistor(tmp))

        om.restore(listOf("alpha"))

        assertThat(broker.recovered).isEmpty()
    }
}
