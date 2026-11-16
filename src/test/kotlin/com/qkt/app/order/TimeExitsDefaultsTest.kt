package com.qkt.app.order

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A fill-anchored timed exit wired with no ledger readers: it closes the whole entry once its hold has run. */
class TimeExitsDefaultsTest {
    private val clock = FixedClock(1_000L)
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val ops = BookOrderOps(book)
    private val exits = TimeExits(book, PendingExposureBook(book), clock, ops)

    private val entry = OrderRequest.Market("e1", "XAUUSD", Side.BUY, BigDecimal("2"), TimeInForce.GTC, 0L, "alpha")
    private val timed =
        OrderRequest.TimeExit(
            id = "t1",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("2"),
            target = entry,
            deadline = Instant.ofEpochMilli(0L),
            onExpiry = ExpiryAction.CLOSE_AT_MARKET,
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
            holdMs = 60_000L,
        )

    @Test
    fun `an entry filled at an unreported time is held from now and then closed in full`() {
        book.put(
            ManagedOrder(id = "t1", request = timed, state = OrderState.CREATED, createdAt = 0L, lastUpdatedAt = 0L),
        )
        exits.submit(timed)
        assertThat(ops.dispatched.map { it.id }).containsExactly("e1")
        assertThat(exits.targets("e1")).isTrue()

        exits.onFilled("e1")
        exits.expireDue(60_999L)
        assertThat(ops.dispatched.map { it.id }).containsExactly("e1")

        exits.expireDue(61_000L)
        val close = ops.dispatched.last() as OrderRequest.Market
        assertThat(close.id).isEqualTo("t1-close")
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("2")
        assertThat(close.legIntent).isEqualTo(LegIntent.Net)
        assertThat(exits.targets("t1-close")).isTrue()
    }
}
