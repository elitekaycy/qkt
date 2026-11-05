package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1643: a timed close the venue refused was resent every minute for two days (945 retries) without one alert: the
 * refused order was forgotten on the next tick, so the retry never counted it as a failure. A refusal is now counted
 * and named in the alert, and one that no resend can change ends the exit at once with an alert. The entry fills
 * at 1s with a 90s hold, so the first close goes at 91s.
 */
class OrderManagerTimedCloseRefusalTest {
    private val clock = FixedClock(time = 1_000L)
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
    private val alerts = mutableListOf<String>()
    private val om =
        OrderManager(
            broker,
            bus,
            MarketPriceTracker(),
            clock,
            NoopStatePersistor(),
            onProtectionFailure = { _, message -> alerts += message },
            positionMode = { PositionAccountingMode.HEDGING },
            openLegQuantity = { _, _ -> BigDecimal.ONE },
        )

    private fun tickAt(ts: Long) {
        clock.time = ts
        bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
    }

    private fun closes() = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.id.contains("-close") }

    private fun refuse(reason: String) =
        bus.publish(BrokerEvent.OrderRejected(closes().last().id, null, reason, "alpha", clock.now()))

    private fun arm() {
        val entry =
            OrderRequest.Market("e1", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 1_000L, strategyId = "alpha")
        om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = entry,
                deadline = Instant.ofEpochMilli(91_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
                strategyId = "alpha",
                holdMs = 90_000L,
            ),
        )
        broker.emitFill(entry, price = Money.of("100"))
        tickAt(91_000L)
        assertThat(closes()).hasSize(1)
    }

    @Test
    fun `refusals forgotten between ticks still count, and the third raises an alert naming the refusal`() {
        arm()
        var at = 91_000L
        repeat(3) {
            refuse("venue_rejected: insufficient available balance")
            tickAt(at + 30_000L)
            at += 60_000L
            tickAt(at)
        }

        assertThat(closes()).hasSize(4)
        assertThat(alerts)
            .singleElement()
            .asString()
            .contains("EXIT NOT FILLED", "te1", "3 consecutive", "venue_rejected: insufficient available balance")
    }

    @Test
    fun `a close the gateway refuses as malformed ends the exit at once with an alert`() {
        arm()

        refuse("invalid_request: client_order_id is longer than 64 characters")
        tickAt(151_000L)
        tickAt(211_000L)

        assertThat(closes()).hasSize(1)
        assertThat(alerts)
            .singleElement()
            .asString()
            .contains("EXIT FAILED", "alpha", "te1", "X", "position 1", "invalid_request: client_order_id")
    }

    @Test
    fun `a close qkt cannot express for the venue, refused as it is sent, ends the exit at once`() {
        broker.rejectOrderIds += "te1-close-r1"
        broker.rejectReason = "unsupported: Market has no VGP v1 order type"
        arm()
        bus.publish(BrokerEvent.OrderCancelled("te1-close", null, "band", "alpha", clock.now()))

        tickAt(151_000L)
        tickAt(211_000L)

        assertThat(closes().map { it.id }).containsExactly("te1-close", "te1-close-r1")
        assertThat(alerts).singleElement().asString().contains("EXIT FAILED", "te1-close-r1", "unsupported: Market")
    }

    @Test
    fun `a close refused while the kill switch is on is sent again`() {
        arm()

        refuse("kill_switch: account scope engaged")
        tickAt(151_000L)

        assertThat(closes().map { it.id }).containsExactly("te1-close", "te1-close-r1")
        assertThat(alerts).isEmpty()
    }
}
