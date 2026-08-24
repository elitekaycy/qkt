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
        private val onRecover: (List<ManagedOrder>) -> Unit = {},
    ) : Broker by delegate {
        val recovered = mutableListOf<ManagedOrder>()

        /** Ids the venue reports no counterpart for (nothing pending, no position, no ticket). */
        val unaccounted = mutableSetOf<String>()

        override fun recoverPendingOrders(orders: List<ManagedOrder>): Set<String> {
            recovered += orders
            onRecover(orders)
            return orders.filterNot { it.id in unaccounted }.mapTo(LinkedHashSet()) { it.id }
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
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isEqualTo(1)
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1990"), clock.now())))
        assertThat(om.getOrder("entry-stop")).isNull()
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isZero()
        assertThat(persistor.loadPendingOrders("alpha")).isEmpty()
    }

    @Test
    fun `restore retires a working order the venue cannot account for`(
        @TempDir tmp: Path,
    ) {
        // bot1 carried 204 pre-#1048 bracket wrappers whose positions had closed weeks earlier:
        // no pending ticket, no position, nothing for the broker to track. Restoring them as
        // WORKING kept their exposure registered for the whole session. The venue's answer is
        // authoritative: an unaccounted order is retired through the normal cancel path.
        val persistor = FileStatePersistor(tmp)
        val stale =
            OrderRequest.Stop(
                id = "stale-entry",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.45"),
                stopPrice = BigDecimal("4350"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val live =
            OrderRequest.Stop(
                id = "live-entry",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf(stale.id to stale, live.id to live))
        val clock = FixedClock(2_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = RecordingBroker(LogBroker(bus, clock)).apply { unaccounted += "stale-entry" }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactlyInAnyOrder("stale-entry", "live-entry")
        assertThat(om.getOrder("live-entry")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder("stale-entry")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(persistor.loadPendingOrders("alpha").keys).containsExactly("live-entry")
    }

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
                override fun recoverPendingOrders(orders: List<ManagedOrder>): Set<String> {
                    error("venue truth unavailable")
                }
            }
        val om = OrderManager(broker, bus, MarketPriceTracker(), FixedClock(0L), persistor)

        assertThatThrownBy { om.restore(listOf("alpha")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("venue truth unavailable")
    }
}
