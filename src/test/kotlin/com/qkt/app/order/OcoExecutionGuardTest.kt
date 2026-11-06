package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OcoExecutionGuardTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val siblings = SiblingLinks()
    private val ops = RecordingOrderOps()
    private val guard = OcoExecutionGuard(book, siblings, FixedClock(7L), ops)

    private fun leg(
        id: String,
        state: OrderState,
        filled: String = "0",
    ) = ManagedOrder(
        id = id,
        request =
            OrderRequest.Stop(id, "XAUUSD", Side.BUY, BigDecimal.ONE, BigDecimal("2000"), TimeInForce.GTC, 0L, "alpha"),
        state = state,
        cumulativeFilledQuantity = BigDecimal(filled),
        createdAt = 0L,
        lastUpdatedAt = 0L,
    )

    private fun ocoOf(
        first: ManagedOrder,
        second: ManagedOrder,
    ) {
        book.put(first)
        book.put(second)
        siblings[first.id] = listOf(second.id)
        siblings[second.id] = listOf(first.id)
        guard.markEmulated(first.id, "g1")
        guard.markEmulated(second.id, "g1")
    }

    private fun fill(
        id: String,
        ticket: String?,
        strategyId: String = "",
    ) = BrokerEvent.OrderFilled(id, ticket, "XAUUSD", Side.BUY, BigDecimal("2000"), BigDecimal("0.5"), strategyId)

    @Test
    fun `a second fill without a position ticket is alerted and never closed by side`() {
        ocoOf(leg("a", OrderState.FILLED, "1"), leg("b", OrderState.FILLED, "0"))

        guard.compensateDoubleFill(fill("b", ticket = " "), guard.executedSibling("b")!!)

        assertThat(ops.submitted).isEmpty()
        assertThat(ops.alerts.single())
            .startsWith("alpha: ")
            .contains("a and b both filled", "automatic close was refused")
    }

    @Test
    fun `a second fill is closed by ticket and a rejected close is alerted once`() {
        ops.ack = { SubmitAck(it.id, null, accepted = false, rejectReason = null) }
        ocoOf(leg("a", OrderState.FILLED, "1"), leg("b", OrderState.FILLED, "0"))

        guard.compensateDoubleFill(fill("b", ticket = "T-9", strategyId = "beta"), guard.executedSibling("b")!!)
        guard.onRejected("g1-oco-double-fill-close-b", "late reject")

        val close = ops.submitted.single() as OrderRequest.Market
        assertThat(close.id).isEqualTo("g1-oco-double-fill-close-b")
        assertThat(close.closesTicket).isEqualTo("T-9")
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("0.5")
        assertThat(close.strategyId).isEqualTo("beta")
        assertThat(close.timestamp).isEqualTo(7L)
        assertThat(ops.alerts).hasSize(2)
        assertThat(ops.alerts[1]).contains("was rejected for position T-9: unknown reason")
    }

    @Test
    fun `a compensation the venue rejects later is alerted, a filled one is not`() {
        ocoOf(leg("a", OrderState.FILLED, "1"), leg("b", OrderState.FILLED, "0"))
        guard.compensateDoubleFill(fill("b", ticket = "T-1"), guard.executedSibling("b")!!)

        guard.onRejected("unknown-close", "ignored")
        guard.onRejected("g1-oco-double-fill-close-b", "market closed")
        guard.onRejected("g1-oco-double-fill-close-b", "again")

        assertThat(ops.alerts).hasSize(2)
        assertThat(ops.alerts[1])
            .isEqualTo(
                "alpha: CRITICAL OCO compensation g1-oco-double-fill-close-b failed for position T-1: " +
                    "market closed",
            )
        assertThat(guard.executedSibling("a")).isNull()
    }

    @Test
    fun `a partly filled leg cancelled after its sibling executed closes what it filled on its ticket`() {
        ocoOf(leg("a", OrderState.FILLED, "1"), leg("b", OrderState.CANCELLED, "0.3"))
        guard.onPartialExecution("b", "T-3")
        guard.onPartialExecution("b", null)
        guard.onPartialExecution("not-emulated", "T-x")

        assertThat(guard.compensateCancelledExecution("b")).isTrue()

        val close = ops.submitted.single() as OrderRequest.Market
        assertThat(close.closesTicket).isEqualTo("T-3")
        assertThat(close.quantity).isEqualByComparingTo("0.3")
        guard.onFilled(close.id)
        guard.onRejected(close.id, "after fill")
        assertThat(ops.alerts).hasSize(1)
    }

    @Test
    fun `a cancelled leg that filled nothing or has no executed sibling needs no compensation`() {
        ocoOf(leg("a", OrderState.WORKING), leg("b", OrderState.CANCELLED, "0.2"))
        book.put(leg("c", OrderState.CANCELLED))

        assertThat(guard.compensateCancelledExecution("missing")).isFalse()
        assertThat(guard.compensateCancelledExecution("c")).isFalse()
        assertThat(guard.compensateCancelledExecution("b")).isFalse()
        assertThat(guard.isHoldingForSibling("b")).isTrue()
        assertThat(ops.submitted).isEmpty()
    }

    @Test
    fun `a forgotten leg no longer counts as part of an engine held oco`() {
        ocoOf(leg("a", OrderState.FILLED, "1"), leg("b", OrderState.WORKING))
        assertThat(guard.isHoldingForSibling("a")).isTrue()

        guard.forget("a")

        assertThat(guard.isHoldingForSibling("a")).isFalse()
        assertThat(guard.executedSibling("a")).isNull()
        assertThat(guard.executedSibling("b")!!.id).isEqualTo("a")
    }
}
