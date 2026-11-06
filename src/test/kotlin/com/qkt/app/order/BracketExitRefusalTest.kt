package com.qkt.app.order

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BracketExitRefusalTest {
    private val clock = FixedClock(0L)
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val siblings = SiblingLinks()
    private val ops = RecordingOrderOps()
    private val guard = OcoExecutionGuard(book, siblings, clock, ops)
    private val refusal = BracketExitRefusal(book, PendingExposureBook(book), siblings, guard, clock, ops)
    private val gtc = TimeInForce.GTC

    private val target =
        OrderRequest.Limit("b-tp", "XAUUSD", Side.SELL, BigDecimal.ONE, BigDecimal("2050"), gtc, 0L, "alpha")

    @Test
    fun `a refused stop that is not a plain stop cannot be held and is alerted as unprotected`() {
        val trailing =
            OrderRequest.ArmedTrailingStop(
                "b-sl",
                "XAUUSD",
                Side.SELL,
                BigDecimal.ONE,
                BigDecimal("2000"),
                BigDecimal("5"),
                BigDecimal("10"),
                TimeInForce.GTC,
                0L,
                "alpha",
            )

        refusal.holdRefusedStop("b-oco", trailing, target, "b-tp")

        assertThat(ops.alerts).containsExactly("alpha: the venue refused stop b-sl; the position may be unprotected")
        assertThat(book["b-sl-held"]).isNull()
    }

    @Test
    fun `a refused stop whose target already ended is not held`() {
        val stop = OrderRequest.Stop("b-sl", "XAUUSD", Side.SELL, BigDecimal.ONE, BigDecimal("1990"), gtc, 0L, "alpha")
        book.put(ManagedOrder("b-tp", target, OrderState.FILLED, createdAt = 0L, lastUpdatedAt = 0L))

        refusal.holdRefusedStop("b-oco", stop, target, "b-tp")

        assertThat(ops.alerts.single()).contains("may be unprotected")
        assertThat(book["b-sl-held"]).isNull()
    }

    @Test
    fun `a refused target is alerted while the stop still protects`() {
        refusal.targetRefused(target)

        assertThat(ops.alerts).containsExactly("alpha: the venue refused take-profit b-tp; the stop still protects")
    }
}
