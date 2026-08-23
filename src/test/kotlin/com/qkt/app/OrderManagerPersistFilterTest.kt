package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.BracketPair
import com.qkt.persistence.PersistedLegBook
import com.qkt.persistence.PersistedOcoLeg
import com.qkt.persistence.PersistedTierState
import com.qkt.persistence.StatePersistor
import com.qkt.positions.LegBook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerPersistFilterTest {
    private class RecordingPersistor : StatePersistor {
        val pendingSnapshots: MutableList<Map<String, OrderRequest>> = mutableListOf()
        val pendingWritesByStrategy: MutableMap<String, Int> = mutableMapOf()
        var onSyncSave: (() -> Unit)? = null

        override fun saveLegBook(
            strategyId: String,
            symbol: String,
            legBook: LegBook,
        ) = Unit

        override fun loadLegBook(
            strategyId: String,
            symbol: String,
        ): PersistedLegBook? = null

        override fun saveBracketPairs(
            strategyId: String,
            pairs: List<BracketPair>,
        ) = Unit

        override fun loadBracketPairs(strategyId: String): List<BracketPair> = emptyList()

        override fun savePendingOrders(
            strategyId: String,
            orders: Map<String, OrderRequest>,
        ) {
            pendingSnapshots.add(orders.toMap())
            pendingWritesByStrategy.merge(strategyId, 1, Int::plus)
        }

        override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> = emptyMap()

        override fun savePendingOrdersSync(
            strategyId: String,
            orders: Map<String, OrderRequest>,
        ) {
            pendingSnapshots.add(orders.toMap())
            onSyncSave?.invoke()
        }

        override fun savePendingStacks(
            strategyId: String,
            perPrimary: Map<String, PersistedTierState>,
        ) = Unit

        override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> = emptyMap()

        override fun saveOcoLegs(
            strategyId: String,
            legs: List<PersistedOcoLeg>,
        ) = Unit

        override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> = emptyList()

        override fun clearStrategy(strategyId: String) = Unit
    }

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = id,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        strategyId = "s1",
    )

    @Test
    fun `StandaloneOCO submission never persists the composite parent`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val persistor = RecordingPersistor()
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco-parent",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "s1",
            ),
        )

        val everyValueAcrossSnapshots = persistor.pendingSnapshots.flatMap { it.values }
        assertThat(everyValueAcrossSnapshots)
            .noneMatch { it is OrderRequest.StandaloneOCO }
            .noneMatch { it is OrderRequest.OTO }
            .noneMatch { it is OrderRequest.Bracket }
            .noneMatch { it is OrderRequest.ScaleOut }
            .noneMatch { it is OrderRequest.TimeExit }
            .noneMatch { it is OrderRequest.Stack }
    }

    @Test
    fun `venue submission happens only after durable intent is recorded`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val persistor = RecordingPersistor()
        var intentRecorded = false
        persistor.onSyncSave = { intentRecorded = true }
        val broker =
            object : Broker {
                override val name = "ordering"
                override val capabilities = setOf(OrderTypeCapability.LIMIT)

                override fun submit(request: OrderRequest): SubmitAck {
                    check(intentRecorded) { "broker submit preceded durable intent" }
                    return SubmitAck(request.id, request.id, accepted = true)
                }

                override fun cancel(orderId: String) = Unit
            }
        val manager = OrderManager(broker, bus, MarketPriceTracker(), FixedClock(0L), persistor)

        manager.submit(limit("limit-1", Side.BUY, "100"))

        assertThat(persistor.pendingSnapshots.last()).containsKey("limit-1")
    }

    @Test
    fun `state change for one strategy does not rewrite another strategy's unchanged files`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val persistor = RecordingPersistor()
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.submit(limit("a1", Side.BUY, "100").copy(strategyId = "sA"))
        om.submit(limit("b1", Side.BUY, "100").copy(strategyId = "sB"))
        val writesBefore = persistor.pendingWritesByStrategy["sA"] ?: 0

        broker.emitFill(broker.submits.first { it.id == "b1" }, price = Money.of("100"))
        broker.emitFill(broker.submits.first { it.id == "b1" }, price = Money.of("100"))

        assertThat(persistor.pendingWritesByStrategy["sA"]).isEqualTo(writesBefore)
        assertThat(persistor.pendingSnapshots.last()).doesNotContainKey("b1")
    }
}
