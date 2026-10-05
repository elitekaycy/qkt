package com.qkt.app

import com.qkt.app.OrderManagerOcoFixtures.limit
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An OCO qkt holds together survives a restart. Its legs stay linked, so a leg filling after the
 * restart cancels the other. And when qkt stops after one leg filled but before the venue confirmed
 * the other leg's cancel, the restart remembers the executed leg: it cancels the other leg again,
 * and if that leg fills anyway the second position is closed by its ticket.
 */
class OrderManagerRestoreExecutedOcoLegTest {
    private val clock = FixedClock(0L)
    private val leg1 = limit("l1", Side.BUY, "100").copy(strategyId = "alpha")
    private val leg2 = limit("l2", Side.SELL, "120").copy(strategyId = "alpha")

    private class Session(
        persistor: FileStatePersistor,
        clock: FixedClock,
    ) {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val cancels = mutableListOf<String>()
        val alerts = mutableListOf<String>()

        // The venue never answers a cancel in time: the session stops with it unconfirmed.
        private val venue =
            object : Broker by fake {
                override fun cancel(orderId: String) {
                    cancels += orderId
                }
            }
        val manager =
            OrderManager(venue, bus, MarketPriceTracker(), clock, persistor, onProtectionFailure = {
                _,
                m,
                ->
                alerts += m
            })
    }

    private fun stopAfterLeg1Filled(tmp: Path): FileStatePersistor {
        val persistor = FileStatePersistor(tmp)
        val first = Session(persistor, clock)
        first.manager.submit(
            OrderRequest.StandaloneOCO(
                "oco",
                "X",
                Side.BUY,
                Money.of("1"),
                leg1,
                leg2,
                TimeInForce.GTC,
                0L,
                strategyId = "alpha",
            ),
        )
        first.bus.publish(
            BrokerEvent.OrderFilled("l1", "position-101", "X", Side.BUY, Money.of("100"), Money.of("1"), "alpha"),
        )
        assertThat(first.cancels).containsExactly("l2")
        return persistor
    }

    @Test
    fun `the restart cancels the other leg again`(
        @TempDir tmp: Path,
    ) {
        val restarted = Session(stopAfterLeg1Filled(tmp), clock)

        restarted.manager.restore(listOf("alpha"))

        assertThat(restarted.manager.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(restarted.manager.getOrder("l2")?.state).isEqualTo(OrderState.WORKING)
        assertThat(restarted.cancels).containsExactly("l2")
        assertThat(restarted.fake.recovered.map { it.id }).containsExactly("l2")
    }

    @Test
    fun `the other leg filling after the restart is closed by its ticket`(
        @TempDir tmp: Path,
    ) {
        val restarted = Session(stopAfterLeg1Filled(tmp), clock)
        restarted.manager.restore(listOf("alpha"))

        restarted.bus.publish(
            BrokerEvent.OrderFilled("l2", "position-202", "X", Side.SELL, Money.of("120"), Money.of("1"), "alpha"),
        )

        val close =
            restarted.fake.submits
                .filterIsInstance<OrderRequest.Market>()
                .single()
        assertThat(close.closesTicket).isEqualTo("position-202")
        assertThat(close.side).isEqualTo(Side.BUY)
        assertThat(restarted.alerts.single()).contains("CRITICAL OCO invariant violated", "position-202")
    }

    @Test
    fun `a leg filling after the restart still cancels the other leg`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        Session(persistor, clock).manager.submit(
            OrderRequest.StandaloneOCO(
                "oco",
                "X",
                Side.BUY,
                Money.of("1"),
                leg1,
                leg2,
                TimeInForce.GTC,
                0L,
                strategyId = "alpha",
            ),
        )
        val restarted = Session(persistor, clock)
        restarted.manager.restore(listOf("alpha"))

        restarted.bus.publish(
            BrokerEvent.OrderFilled("l1", "position-101", "X", Side.BUY, Money.of("100"), Money.of("1"), "alpha"),
        )

        assertThat(restarted.manager.siblingsOf("l1")).containsExactly("l2")
        assertThat(restarted.cancels).containsExactly("l2")
    }

    @Test
    fun `an oco whose legs both ended is not restored`(
        @TempDir tmp: Path,
    ) {
        val persistor = stopAfterLeg1Filled(tmp)
        val first = Session(persistor, clock)
        first.manager.restore(listOf("alpha"))
        first.bus.publish(BrokerEvent.OrderCancelled("l2", "l2", "cancelled", "alpha"))

        val second = Session(persistor, clock)
        second.manager.restore(listOf("alpha"))

        assertThat(second.manager.getOrder("l1")).isNull()
        assertThat(second.manager.getOrder("l2")).isNull()
        assertThat(second.cancels).isEmpty()
    }
}
