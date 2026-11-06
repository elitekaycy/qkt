package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `entry-1` asked for 1.00 and 0.40 was booked at 2400 before the restart; the residual is gone. */
class MT5BookedPartialResumeTest {
    private val clock = FixedClock(time = 50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1"))
    private val resume =
        MT5BookedPartialResume(
            MT5DefaultProfiles.exness,
            bus,
            clock,
            MT5PartialEntries(books, bus, clock),
            seedTrackedTickets = {},
        )
    private val events = mutableListOf<BrokerEvent>()

    init {
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { events.add(it) }
    }

    private val order =
        ManagedOrder(
            id = "entry-1",
            request =
                OrderRequest.Market(
                    id = "entry-1",
                    symbol = "EXNESS:XAUUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("1.00"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "gold",
                ),
            state = OrderState.PARTIALLY_FILLED,
            brokerOrderId = "500",
            cumulativeFilledQuantity = BigDecimal("0.40"),
            avgFillPrice = BigDecimal("2400"),
            createdAt = 0L,
            lastUpdatedAt = 0L,
        )

    private fun position(
        volume: String,
        average: String,
    ) = MT5Position(
        ticket = 500L,
        symbol = "XAUUSDm",
        type = 0,
        volume = BigDecimal(volume),
        priceOpen = BigDecimal(average),
        sl = BigDecimal.ZERO,
        tp = BigDecimal.ZERO,
        profit = BigDecimal.ZERO,
        magic = 10001,
        openTime = 40_000L,
    )

    @Test
    fun `growth while down short of the whole order is published as a slice, then the rest is cancelled`() {
        // 0.40 @ 2400 grew to 0.70 @ 2403 average: the new 0.30 traded at 2407.
        resume.resume(order, MT5TicketMeta("entry-1", "gold"), position("0.70", "2403"), residual = null)

        assertThat(events).hasSize(2)
        val slice = events[0] as BrokerEvent.OrderPartiallyFilled
        assertThat(slice.quantity).isEqualByComparingTo("0.30")
        assertThat(slice.price).isEqualByComparingTo("2407")
        assertThat(slice.cumulativeFilled).isEqualByComparingTo("0.70")
        assertThat(slice.brokerOrderId).isEqualTo("500")
        val cancelled = events[1] as BrokerEvent.OrderCancelled
        assertThat(cancelled.reason).isEqualTo("residual absent during partial-entry recovery")
    }

    @Test
    fun `no growth while down only cancels the rest`() {
        resume.resume(order, MT5TicketMeta("entry-1", "gold"), position("0.40", "2400"), residual = null)

        assertThat(events.single()).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }
}
