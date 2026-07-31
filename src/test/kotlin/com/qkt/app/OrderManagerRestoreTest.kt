package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.BracketPair
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.PersistedOcoLeg
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `restore adopts a single pending order and enforces its GTD expiry`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val request =
            OrderRequest.Stop(
                id = "entry-stop",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTD,
                timestamp = 0L,
                strategyId = "alpha",
                expiresAt = 1_000L,
            )
        persistor.savePendingOrders("alpha", mapOf(request.id to request))
        persistor.saveBracketPairs(
            "alpha",
            listOf(BracketPair(request.id, "entry-stop-sl", "entry-stop-tp", null)),
        )
        val clock = FixedClock(2_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = RecordingBroker(LogBroker(bus, clock))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactly("entry-stop")
        assertThat(om.getOrder("entry-stop")?.state).isEqualTo(OrderState.WORKING)
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1990"), clock.now())))
        assertThat(om.getOrder("entry-stop")).isNull()
        assertThat(persistor.loadPendingOrders("alpha")).isEmpty()
    }

    @Test
    fun `restored fill-anchored fallback bracket places exits from the actual fill`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val entry =
            OrderRequest.Limit(
                id = "entry-limit",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "bracket-1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                entry = entry,
                takeProfit = BigDecimal("120"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
                takeProfitAst = ChildBy(NumLit(BigDecimal("20"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("10"))),
            )
        persistor.savePendingOrders("alpha", mapOf(entry.id to bracket))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val broker = RecordingBroker(fake)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = entry.id,
                brokerOrderId = "venue-entry-7",
                symbol = entry.symbol,
                side = entry.side,
                price = BigDecimal("105"),
                quantity = BigDecimal.ONE,
                strategyId = "alpha",
            ),
        )

        assertThat(broker.recovered.map { it.id }).containsExactly(entry.id)
        assertThat(manager.getOrder(entry.id)?.state).isEqualTo(OrderState.FILLED)
        val takeProfit = fake.submits.filterIsInstance<OrderRequest.Limit>().single()
        val stopLoss = fake.submits.filterIsInstance<OrderRequest.Stop>().single()
        assertThat(takeProfit.limitPrice).isEqualByComparingTo("125")
        assertThat(stopLoss.stopPrice).isEqualByComparingTo("95")
        assertThat(takeProfit.strategyId).isEqualTo("alpha")
        assertThat(stopLoss.strategyId).isEqualTo("alpha")
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
                override fun recoverPendingOrders(orders: List<ManagedOrder>) {
                    error("venue truth unavailable")
                }
            }
        val om = OrderManager(broker, bus, MarketPriceTracker(), FixedClock(0L), persistor)

        assertThatThrownBy { om.restore(listOf("alpha")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("venue truth unavailable")
    }
}
