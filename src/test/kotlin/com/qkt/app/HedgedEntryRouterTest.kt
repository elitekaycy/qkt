package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HedgedEntryRouterTest {
    private var minted = 0

    private fun nextId(): String = "extra-${minted++}"

    private fun market(
        side: Side,
        qty: String,
        id: String = "ORD-s-7",
    ) = OrderRequest.Market(
        id = id,
        symbol = "XAUUSD",
        side = side,
        quantity = BigDecimal(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
        strategyId = "s",
    )

    private fun leg(
        id: String,
        side: Side,
        qty: String,
        openedAt: Long,
        ticket: String? = "t-$id",
        role: LegRole = LegRole.INDEPENDENT,
    ) = PositionLeg(
        legId = id,
        symbol = "XAUUSD",
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal("2400"),
        openedAt = openedAt,
        role = role,
        parentLegId = null,
        brokerTicket = ticket,
    )

    @Test
    fun `an opposite entry on a hedging venue closes the open leg by ticket`() {
        val routed =
            HedgedEntryRouter.route(
                market(Side.SELL, "0.01"),
                PositionAccountingMode.HEDGING,
                listOf(leg("a", Side.BUY, "0.01", openedAt = 1L)),
                ::nextId,
            )
        assertThat(routed).containsExactly(
            market(Side.SELL, "0.01").copy(closesTicket = "t-a", closesLegId = "a", partialClose = false),
        )
    }

    @Test
    fun `a smaller opposite entry partially closes the oldest opposite leg`() {
        val routed =
            HedgedEntryRouter.route(
                market(Side.SELL, "0.01"),
                PositionAccountingMode.HEDGING,
                listOf(
                    leg("young", Side.BUY, "0.05", openedAt = 9L),
                    leg("old", Side.BUY, "0.03", openedAt = 2L),
                ),
                ::nextId,
            )
        assertThat(routed).containsExactly(
            market(Side.SELL, "0.01").copy(closesTicket = "t-old", closesLegId = "old", partialClose = true),
        )
    }

    @Test
    fun `an entry larger than the opposite exposure closes every leg and opens the remainder`() {
        val routed =
            HedgedEntryRouter.route(
                market(Side.SELL, "0.05"),
                PositionAccountingMode.HEDGING,
                listOf(
                    leg("a", Side.BUY, "0.02", openedAt = 1L),
                    leg("b", Side.BUY, "0.01", openedAt = 2L),
                    leg("short", Side.SELL, "0.10", openedAt = 3L),
                ),
                ::nextId,
            )
        assertThat(routed).containsExactly(
            market(Side.SELL, "0.02").copy(closesTicket = "t-a", closesLegId = "a", partialClose = false),
            market(
                Side.SELL,
                "0.01",
                id = "extra-0",
            ).copy(closesTicket = "t-b", closesLegId = "b", partialClose = false),
            market(Side.SELL, "0.02", id = "extra-1"),
        )
    }

    @Test
    fun `same-side entries and netting venues pass through untouched`() {
        val entry = market(Side.BUY, "0.01")
        val legs = listOf(leg("a", Side.BUY, "0.01", openedAt = 1L))
        assertThat(HedgedEntryRouter.route(entry, PositionAccountingMode.HEDGING, legs, ::nextId))
            .containsExactly(entry)
        val opposite = market(Side.SELL, "0.01")
        assertThat(HedgedEntryRouter.route(opposite, PositionAccountingMode.NETTING, legs, ::nextId))
            .containsExactly(opposite)
        assertThat(HedgedEntryRouter.route(opposite, PositionAccountingMode.HEDGING, emptyList(), ::nextId))
            .containsExactly(opposite)
    }

    @Test
    fun `requests that already carry an intent or a close target are left alone`() {
        val legs = listOf(leg("a", Side.BUY, "0.01", openedAt = 1L))
        val planned = market(Side.SELL, "0.01").copy(legIntent = LegIntent.Open("x", LegRole.STACK, "p"))
        assertThat(HedgedEntryRouter.route(planned, PositionAccountingMode.HEDGING, legs, ::nextId))
            .containsExactly(planned)
        val close = market(Side.SELL, "0.01").copy(closesTicket = "t-a")
        assertThat(HedgedEntryRouter.route(close, PositionAccountingMode.HEDGING, legs, ::nextId))
            .containsExactly(close)
    }

    @Test
    fun `a leg without a venue ticket cannot be closed by ticket and is skipped`() {
        val routed =
            HedgedEntryRouter.route(
                market(Side.SELL, "0.01"),
                PositionAccountingMode.HEDGING,
                listOf(
                    leg("restored", Side.BUY, "0.01", openedAt = 1L, ticket = null),
                    leg("live", Side.BUY, "0.01", openedAt = 2L),
                ),
                ::nextId,
            )
        assertThat(routed).containsExactly(
            market(Side.SELL, "0.01").copy(closesTicket = "t-live", closesLegId = "live", partialClose = false),
        )
    }
}
