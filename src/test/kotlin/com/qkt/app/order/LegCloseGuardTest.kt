package com.qkt.app.order

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LegCloseGuardTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val log = LoggerFactory.getLogger(LegCloseGuardTest::class.java)
    private var mode = PositionAccountingMode.HEDGING
    private var net: BigDecimal? = BigDecimal("2")
    private val openLegs = mutableMapOf<String, BigDecimal>()

    private fun guard(
        withNet: Boolean = true,
        withLegs: Boolean = true,
    ) = LegCloseGuard(
        book,
        { mode },
        if (withNet) { _, _ -> net ?: BigDecimal.ZERO } else null,
        if (withLegs) { _, legId -> openLegs[legId] } else null,
        log,
    )

    private fun close(
        id: String,
        qty: String,
        intent: LegIntent,
        side: Side = Side.SELL,
    ) = OrderRequest.Market(id, "XAUUSD", side, BigDecimal(qty), TimeInForce.GTC, 0L, "alpha", legIntent = intent)

    private fun working(
        request: OrderRequest.Market,
        filled: String = "0",
    ) = book.put(
        ManagedOrder(
            id = request.id,
            request = request,
            state = OrderState.SUBMITTED,
            cumulativeFilledQuantity = BigDecimal(filled),
            createdAt = 0L,
            lastUpdatedAt = 0L,
        ),
    )

    private fun released(
        guard: LegCloseGuard,
        coverId: String,
        filled: Boolean,
    ): List<OrderRequest> = mutableListOf<OrderRequest>().also { out -> guard.release(coverId, filled) { out += it } }

    @Test
    fun `a close of a leg another working close names by ticket is held until that close ends`() {
        val guard = guard()
        working(close("c1", "1", LegIntent.Close(ticket = "T1")))

        val ack = guard.hold(close("c2", "1", LegIntent.Close(ticket = "T1")))!!

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("close c1 of the same position is still working; held until it ends")
        assertThat(guard.isEmpty()).isFalse()
        // A ticket close with no leg id: once the cover filled, nothing of the position is known to be left.
        assertThat(released(guard, "c1", filled = true)).isEmpty()
        assertThat(guard.isEmpty()).isTrue()
    }

    @Test
    fun `a held ticket close whose cover was cancelled is sent unchanged`() {
        val guard = guard()
        working(close("c1", "1", LegIntent.Close(ticket = "T1")))
        val held = close("c2", "1", LegIntent.Close(ticket = "T1"))
        guard.hold(held)

        assertThat(released(guard, "c1", filled = false)).containsExactly(held)
        assertThat(released(guard, "c1", filled = false)).isEmpty()
    }

    @Test
    fun `a held leg close is cut to what the leg still holds, or dropped when it is gone`() {
        val guard = guard()
        working(close("c1", "1", LegIntent.Close(legId = "L1")))
        guard.hold(close("c2", "1", LegIntent.Close(legId = "L1")))
        guard.hold(close("c3", "1", LegIntent.Close(legId = "L2")))
        working(close("c4", "1", LegIntent.Close(legId = "L2")))
        guard.hold(close("c5", "1", LegIntent.Close(legId = "L2")))
        openLegs["L1"] = BigDecimal("0.4")

        val cut = released(guard, "c1", filled = false).single() as OrderRequest.Market
        assertThat(cut.quantity).isEqualByComparingTo("0.4")
        assertThat(cut.partialClose).isTrue()
        assertThat(cut.legIntent).isEqualTo(LegIntent.Close(legId = "L1", partial = true))
        assertThat(released(guard, "c4", filled = true)).isEmpty()
    }

    @Test
    fun `without a leg resolver a held leg close is sent unchanged`() {
        val guard = guard(withLegs = false)
        working(close("c1", "1", LegIntent.Close(legId = "L1")))
        val held = close("c2", "1", LegIntent.Close(legId = "L1"))
        guard.hold(held)

        assertThat(released(guard, "c1", filled = true)).containsExactly(held)
    }

    @Test
    fun `on a netting venue working closes covering the whole net position hold a further close`() {
        mode = PositionAccountingMode.NETTING
        net = BigDecimal("1.5")
        val guard = guard()
        working(close("c1", "1", LegIntent.Net))
        working(close("c2", "1", LegIntent.Close(legId = "L9")), filled = "0.5")

        val ack = guard.hold(close("c3", "1", LegIntent.Close(legId = "L1")))

        assertThat(ack!!.rejectReason).startsWith("close c1 of the same position")
        // Net 1.5 long still open when the cover ends: the held close is sent at its own size.
        assertThat(released(guard, "c1", filled = true).single().quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `on a netting venue closes covering less than the position let a further close through`() {
        mode = PositionAccountingMode.NETTING
        net = BigDecimal("-3")
        val guard = guard()
        working(close("c1", "1", LegIntent.Net, side = Side.BUY))

        assertThat(guard.hold(close("c2", "1", LegIntent.Close(legId = "L1"), side = Side.BUY))).isNull()
        assertThat(guard.coveringClose("alpha", "XAUUSD", Side.BUY, null)).isNull()
    }

    @Test
    fun `a netting venue with no net reader never holds a close`() {
        mode = PositionAccountingMode.NETTING
        val guard = guard(withNet = false)
        working(close("c1", "5", LegIntent.Net))

        assertThat(guard.hold(close("c2", "1", LegIntent.Close(legId = "L1")))).isNull()
    }

    @Test
    fun `entries, other strategies, other sides and non close requests are never held`() {
        val guard = guard()
        working(close("c1", "1", LegIntent.Close(ticket = "T1")))

        assertThat(guard.hold(close("c2", "1", LegIntent.Close(ticket = "T1"), side = Side.BUY))).isNull()
        assertThat(guard.hold(close("c3", "1", LegIntent.Unplanned))).isNull()
        assertThat(guard.hold(close("c4", "1", LegIntent.Close(ticket = "T2")))).isNull()
        assertThat(guard.hold(close("c1", "1", LegIntent.Close(ticket = "T1")))).isNull()
        val limit =
            OrderRequest.Limit(
                "l1",
                "XAUUSD",
                Side.SELL,
                BigDecimal.ONE,
                BigDecimal("1"),
                TimeInForce.GTC,
                0L,
                "alpha",
            )
        assertThat(guard.hold(limit)).isNull()
        assertThat(guard.coveringClose("alpha", "EURUSD", Side.SELL, null)).isNull()
        assertThat(guard.isEmpty()).isTrue()
    }
}
