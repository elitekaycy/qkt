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
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.AsyncStatePersistor
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.PersistedOrderFill
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A live order's fill progress survives a restart (#1329): the restored order reaches the venue's
 * recovery with what it had filled, so only fills made while qkt was down are booked.
 */
class OrderManagerRestoreFillProgressTest {
    private val clock = FixedClock(10L)
    private val limit =
        OrderRequest.Limit("l1", "X", Side.BUY, BigDecimal("1"), BigDecimal("100"), TimeInForce.GTC, 0L, "alpha")

    private fun partFill(
        bus: EventBus,
        quantity: String,
        cumulative: String,
        price: String,
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            "l1",
            "v1",
            "X",
            Side.BUY,
            BigDecimal(price),
            BigDecimal(quantity),
            BigDecimal(cumulative),
            "alpha",
        ),
    )

    @Test
    fun `a partly filled order is restored with what it filled, and an ended one leaves nothing behind`(
        @TempDir tmp: Path,
    ) {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val manager =
            OrderManager(
                FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT)),
                bus,
                MarketPriceTracker(),
                clock,
                FileStatePersistor(tmp),
            )
        manager.submit(limit)
        partFill(bus, "0.2", "0.2", "100")
        partFill(bus, "0.2", "0.4", "98")
        val saved = FileStatePersistor(tmp).loadOrderFills("alpha")
        assertThat(saved.keys).containsExactly("l1")
        assertThat(saved.getValue("l1").filledQuantity).isEqualByComparingTo("0.4")
        assertThat(saved.getValue("l1").avgFillPrice).isEqualByComparingTo("99")

        val restartBus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = RecordingBroker(FakeBroker(restartBus, clock, setOf(OrderTypeCapability.LIMIT)))
        val restarted = OrderManager(broker, restartBus, MarketPriceTracker(), clock, FileStatePersistor(tmp))
        restarted.restore(listOf("alpha"))

        val recovered = broker.recovered.single()
        assertThat(recovered.id).isEqualTo("l1")
        assertThat(recovered.cumulativeFilledQuantity).isEqualByComparingTo("0.4")
        assertThat(recovered.avgFillPrice).isEqualByComparingTo("99")
        assertThat(restarted.getOrder("l1")?.cumulativeFilledQuantity).isEqualByComparingTo("0.4")

        restartBus.publish(BrokerEvent.OrderCancelled("l1", "v1", "cancelled at the venue", "alpha"))
        assertThat(FileStatePersistor(tmp).loadOrderFills("alpha")).isEmpty()
    }

    @Test
    fun `fill progress is queued behind the decorator, frozen at the call, and read back through it`(
        @TempDir tmp: Path,
    ) {
        val fills = mutableMapOf("l1" to PersistedOrderFill(BigDecimal("0.3"), BigDecimal("70000")))
        AsyncStatePersistor(FileStatePersistor(tmp)).use { async ->
            async.saveOrderFills("alpha", fills)
            fills["l2"] = PersistedOrderFill(BigDecimal("1"), null)
            assertThat(async.awaitDrain()).isTrue
            assertThat(async.loadOrderFills("alpha").keys).containsExactly("l1")
        }
        FileStatePersistor(tmp).saveOrderFills("beta", mapOf("l2" to PersistedOrderFill(BigDecimal("1"), null)))
        assertThat(FileStatePersistor(tmp).loadOrderFills("beta")["l2"]?.avgFillPrice).isNull()
        assertThat(FileStatePersistor(tmp).loadOrderFills("gamma")).isEmpty()
        FileStatePersistor(tmp).saveOrderFills(
            "delta",
            mapOf(
                "l3" to PersistedOrderFill(BigDecimal("1"), null, "position-9"),
            ),
        )
        assertThat(FileStatePersistor(tmp).loadOrderFills("delta")["l3"]?.positionTicket).isEqualTo("position-9")
    }
}
