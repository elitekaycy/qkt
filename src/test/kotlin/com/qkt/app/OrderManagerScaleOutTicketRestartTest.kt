package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.RecordingBroker
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
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

/**
 * A hedging account (several positions per symbol, exits must close their own ticket, as on MT5
 * hedging) restarts over a scale-out whose basis filled in part, and the venue cancels the remainder
 * while qkt is down (#1342): the legs close the ticket the partial fill opened, with no operator alert.
 */
class OrderManagerScaleOutTicketRestartTest {
    private val clock = FixedClock(0L)
    private val hedging = setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.MULTI_POSITION_PER_SYMBOL)
    private val alerts = mutableListOf<String>()
    private val basis =
        OrderRequest.Limit("e1", "X", Side.BUY, BigDecimal("2"), BigDecimal("100"), TimeInForce.GTC, 0L, "alpha")

    private fun manager(
        broker: com.qkt.broker.Broker,
        bus: EventBus,
        state: Path,
    ) = OrderManager(
        broker,
        bus,
        MarketPriceTracker(),
        clock,
        FileStatePersistor(state),
        requireArmedTrailTicket = true,
        onProtectionFailure = { _, message -> alerts += message },
    )

    @Test
    fun `after a restart the legs of a part-filled scale-out close the ticket its fill opened`(
        @TempDir state: Path,
    ) {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        manager(FakeBroker(bus, clock, hedging), bus, state).submit(scaleOut(basis, strategyId = "alpha"))
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                "e1",
                "position-9",
                "X",
                Side.BUY,
                BigDecimal("100"),
                BigDecimal("0.4"),
                BigDecimal("0.4"),
                "alpha",
            ),
        )

        val restartBus = EventBus(clock, MonotonicSequenceGenerator())
        // The venue cancelled the remainder while qkt was down; recovery reports it as the restart resolves the basis.
        val broker =
            RecordingBroker(FakeBroker(restartBus, clock, hedging)) {
                restartBus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled while down", "alpha"))
            }
        val restarted = manager(broker, restartBus, state)
        restarted.restore(listOf("alpha"))

        val legs = listOf("s1-leg-0", "s1-leg-1").mapNotNull { restarted.getOrder(it) }
        assertThat(alerts).isEmpty()
        assertThat(broker.recovered.single().cumulativeFilledQuantity).isEqualByComparingTo("0.4")
        assertThat(legs).hasSize(2)
        assertThat(legs).allSatisfy { leg ->
            val exit = leg.request as OrderRequest.IfTouched
            assertThat(leg.state).isEqualTo(OrderState.PENDING)
            assertThat(exit.quantity).isEqualByComparingTo("0.2")
            assertThat(exit.closesTicket).isEqualTo("position-9")
        }
        assertThat(restarted.getOrder("s1")?.state).isNotEqualTo(OrderState.REJECTED)
    }
}
