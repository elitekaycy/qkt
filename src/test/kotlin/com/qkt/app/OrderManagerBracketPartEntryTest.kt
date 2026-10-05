package com.qkt.app

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
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bracket the engine decomposes (the venue holds no SL/TP on a position, a VGP gateway say) whose
 * market entry the venue filled in part and then cancelled the rest of: the part held is protected.
 */
class OrderManagerBracketPartEntryTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val broker =
        FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
    private val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)
    private val entry = OrderRequest.Market("e1", "X", Side.BUY, BigDecimal("50"), TimeInForce.GTC, 0L, "alpha")

    private fun submitBracket() =
        manager.submit(
            OrderRequest.Bracket(
                "b1",
                "X",
                Side.BUY,
                BigDecimal("50"),
                entry,
                BigDecimal("110"),
                StopLossSpec.Fixed(BigDecimal("90")),
                TimeInForce.GTC,
                0L,
                "alpha",
            ),
        )

    private fun fillPartThenCancel() {
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                "e1",
                "v1",
                "X",
                Side.BUY,
                BigDecimal("100"),
                BigDecimal("30"),
                BigDecimal("30"),
                "alpha",
            ),
        )
        bus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled at the venue", "alpha"))
    }

    private fun exits() = broker.submits.filter { it.id != "e1" }

    @Test
    fun `the part filled before the venue cancelled the rest gets its stop and target, sized to that part`() {
        submitBracket()

        fillPartThenCancel()

        assertThat(exits().map { it.id }).containsExactlyInAnyOrder("b1-sl", "b1-tp")
        assertThat(exits().map { it.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo("30") }
        assertThat((exits().single { it.id == "b1-sl" } as OrderRequest.Stop).stopPrice).isEqualByComparingTo("90")
        assertThat((exits().single { it.id == "b1-tp" } as OrderRequest.Limit).limitPrice).isEqualByComparingTo("110")
        assertThat(manager.getOrder("e1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `an entry cancelled with nothing filled still takes its exits with it`() {
        submitBracket()

        bus.publish(BrokerEvent.OrderCancelled("e1", "v1", "cancelled at the venue", "alpha"))

        assertThat(exits()).isEmpty()
        assertThat(manager.getOrder("b1-oco")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `a bracket the strategy cancels as a whole arms nothing for its part-filled entry`() {
        submitBracket()
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                "e1",
                "v1",
                "X",
                Side.BUY,
                BigDecimal("100"),
                BigDecimal("30"),
                BigDecimal("30"),
                "alpha",
            ),
        )

        manager.cancel("b1")

        assertThat(exits()).isEmpty()
    }
}
