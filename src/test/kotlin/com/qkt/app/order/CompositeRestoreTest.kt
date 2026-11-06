package com.qkt.app.order

import com.qkt.app.OrderManager
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
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompositeRestoreTest {
    private val clock = FixedClock(5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private fun limit(
        id: String,
        side: Side = Side.BUY,
        price: String = "100",
    ) = OrderRequest.Limit(id, "XAUUSD", side, BigDecimal.ONE, BigDecimal(price), TimeInForce.GTC, 0L, "alpha")

    private fun bracket(stop: StopLossSpec) =
        OrderRequest.Bracket(
            id = "bracket-1",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal.ONE,
            entry = limit("entry-1"),
            takeProfit = BigDecimal("120"),
            stopLoss = stop,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private fun oto(
        id: String,
        parent: OrderRequest,
        children: List<OrderRequest>,
    ) = OrderRequest.OTO(id, "XAUUSD", Side.BUY, BigDecimal.ONE, parent, children, TimeInForce.GTC, 0L, "alpha")

    private fun restore(
        tmp: Path,
        caps: Set<OrderTypeCapability>,
        pending: Map<String, OrderRequest>,
    ): Triple<OrderManager, RecordingBroker, FakeBroker> {
        val persistor = FileStatePersistor(tmp)
        persistor.savePendingOrders("alpha", pending)
        val fake = FakeBroker(bus, clock, caps)
        val broker = RecordingBroker(fake)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)
        manager.restore(listOf("alpha"))
        return Triple(manager, broker, fake)
    }

    @Test
    fun `an attached bracket with an engine managed stop restores that stop held until the entry fills`(
        @TempDir tmp: Path,
    ) {
        val caps = setOf(OrderTypeCapability.BRACKET, OrderTypeCapability.POSITION_MODIFY, OrderTypeCapability.LIMIT)
        val request = bracket(StopLossSpec.ArmedTrail(BigDecimal("5"), BigDecimal("10")))
        val (manager, broker, _) = restore(tmp, caps, mapOf("entry-1" to request))

        assertThat(broker.recovered.map { it.id }).containsExactly("entry-1")
        val entry = manager.getOrder("entry-1")!!
        assertThat(entry.state).isEqualTo(OrderState.WORKING)
        assertThat((entry.request as OrderRequest.Bracket).id).isEqualTo("entry-1")
        val stop = manager.getOrder("bracket-1-sl")!!
        assertThat(stop.state).isEqualTo(OrderState.CREATED)
        assertThat(stop.parentClientOrderId).isEqualTo("bracket-1")
        assertThat(stop.createdAt).isEqualTo(5L)
    }

    @Test
    fun `a fixed bracket on a venue with native brackets but no position modify restores whole`(
        @TempDir tmp: Path,
    ) {
        val caps = setOf(OrderTypeCapability.BRACKET, OrderTypeCapability.LIMIT)
        val pending = mapOf("entry-1" to bracket(StopLossSpec.Fixed(BigDecimal("90"))))
        val (manager, broker, _) = restore(tmp, caps, pending)

        assertThat(broker.recovered.map { it.id }).containsExactly("bracket-1")
        assertThat(manager.getOrder("bracket-1")!!.state).isEqualTo(OrderState.WORKING)
        assertThat(manager.getOrder("entry-1")).isNull()
    }

    @Test
    fun `a decomposed bracket with a priced entry holds its exit oco and places it on the fill`(
        @TempDir tmp: Path,
    ) {
        val caps = setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val pending = mapOf("entry-1" to bracket(StopLossSpec.Fixed(BigDecimal("90"))))
        val (manager, broker, fake) = restore(tmp, caps, pending)
        assertThat(broker.recovered.map { it.id }).containsExactly("entry-1")

        bus.publish(
            BrokerEvent.OrderFilled("entry-1", "v-1", "XAUUSD", Side.BUY, BigDecimal("101"), BigDecimal.ONE, "alpha"),
        )

        assertThat(manager.getOrder("entry-1")!!.state).isEqualTo(OrderState.FILLED)
        assertThat(fake.submits.filterIsInstance<OrderRequest.Limit>().single().limitPrice).isEqualByComparingTo("120")
        assertThat(fake.submits.filterIsInstance<OrderRequest.Stop>().single().stopPrice).isEqualByComparingTo("90")
    }

    @Test
    fun `a persisted oto whose parent reuses the wrapper id aborts the restore`(
        @TempDir tmp: Path,
    ) {
        val request = oto("same", limit("same"), listOf(limit("c1", Side.SELL, "110")))

        assertThatThrownBy { restore(tmp, setOf(OrderTypeCapability.LIMIT), mapOf("same" to request)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parent must have a distinct id")
    }

    @Test
    fun `a persisted oto whose child reuses the parent id aborts the restore`(
        @TempDir tmp: Path,
    ) {
        val request = oto("w", limit("p"), listOf(limit("p", Side.SELL, "110")))

        assertThatThrownBy { restore(tmp, setOf(OrderTypeCapability.LIMIT), mapOf("p" to request)) }
            .hasMessageContaining("child ids must differ")
    }

    @Test
    fun `a persisted oto with duplicate child ids aborts the restore`(
        @TempDir tmp: Path,
    ) {
        val request = oto("w", limit("p"), listOf(limit("c", Side.SELL, "110"), limit("c", Side.SELL, "111")))

        assertThatThrownBy { restore(tmp, setOf(OrderTypeCapability.LIMIT), mapOf("p" to request)) }
            .hasMessageContaining("child ids must be unique")
    }

    @Test
    fun `a persisted oto colliding with an already restored order aborts the restore`(
        @TempDir tmp: Path,
    ) {
        val request = oto("w", limit("p"), listOf(limit("c", Side.SELL, "110")))

        assertThatThrownBy {
            val pending = linkedMapOf("c" to limit("c", Side.SELL, "110"), "p" to request)
            restore(tmp, setOf(OrderTypeCapability.LIMIT), pending)
        }.hasMessageContaining("collides with already-restored order state")
    }
}
