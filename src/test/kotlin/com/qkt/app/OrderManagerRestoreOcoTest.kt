package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.RecordingBroker
import com.qkt.app.OrderManagerRestoreFixtures.newBus
import com.qkt.app.OrderManagerRestoreFixtures.ocoLeg
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestoreOcoTest {
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
    fun `restore compensates the second OCO position when both legs filled during downtime`(
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
        val clock = FixedClock(10L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.MARKET, OrderTypeCapability.STOP),
            )
        val pendingCancels = mutableListOf<String>()
        val delayedCancelBroker =
            object : Broker by fake {
                override fun cancel(orderId: String) {
                    pendingCancels += orderId
                }
            }
        val broker =
            RecordingBroker(delayedCancelBroker) { recovered ->
                recovered.forEachIndexed { index, managed ->
                    val request = managed.request
                    bus.publish(
                        BrokerEvent.OrderFilled(
                            clientOrderId = request.id,
                            brokerOrderId = "position-${index + 1}",
                            symbol = request.symbol,
                            side = request.side,
                            price = BigDecimal("2000"),
                            quantity = request.quantity,
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                }
            }
        val alerts = mutableListOf<Pair<String, String>>()
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor,
                onProtectionFailure = { strategyId, message -> alerts += strategyId to message },
            )

        manager.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactlyInAnyOrder("oco1-a", "oco1-b")
        assertThat(pendingCancels).contains("oco1-b")
        val compensation = fake.submits.filterIsInstance<OrderRequest.Market>().single()
        assertThat(compensation.closesTicket).isEqualTo("position-2")
        assertThat(compensation.strategyId).isEqualTo("alpha")
        assertThat(alerts.single().second).contains("CRITICAL OCO invariant violated")
    }

    @Test
    fun `restore propagates broker recovery failure`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        persistor.saveOcoLegs("alpha", listOf(ocoLeg("oco1-a", Side.BUY, "1001", emptyList())))
        val bus = newBus()
        val broker =
            object : Broker by LogBroker(bus, FixedClock(0L)) {
                override fun recoverPendingOrders(
                    orders: List<ManagedOrder>,
                    bookedTickets: Set<String>,
                ): Set<String> {
                    error("venue truth unavailable")
                }
            }
        val om = OrderManager(broker, bus, MarketPriceTracker(), FixedClock(0L), persistor)

        assertThatThrownBy { om.restore(listOf("alpha")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("venue truth unavailable")
    }
}
