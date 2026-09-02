package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.withLegIntent
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegIntentResolverTest {
    private val fill =
        BrokerEvent.OrderFilled(
            clientOrderId = "o1",
            brokerOrderId = "T-9",
            symbol = "XAUUSD",
            side = Side.SELL,
            price = BigDecimal("2400"),
            quantity = BigDecimal.ONE,
            strategyId = "s",
        )

    private val market =
        OrderRequest.Market("o1", "XAUUSD", Side.SELL, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")

    private val ownedLong =
        PositionLeg(
            legId = "leg-a",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal.ONE,
            entryPrice = BigDecimal("2390"),
            openedAt = 0L,
            role = LegRole.INDEPENDENT,
            brokerTicket = "T-9",
        )

    private fun resolver(
        order: OrderRequest? = null,
        leg: PositionLeg? = null,
        mode: PositionAccountingMode = PositionAccountingMode.NETTING,
    ) = LegIntentResolver(
        orderFor = { if (it == "o1") order else null },
        legByTicket = { _, _, ticket -> leg?.takeIf { it.brokerTicket == ticket } },
        positionMode = { mode },
    )

    @Test
    fun `the order's own intent wins`() {
        val planned = market.withLegIntent(LegIntent.Close(legId = "leg-a"))
        val r = resolver(order = planned, leg = ownedLong, mode = PositionAccountingMode.HEDGING).resolve(fill)
        assertThat(r.intent).isEqualTo(LegIntent.Close(legId = "leg-a"))
        assertThat(r.source).isEqualTo(LegIntentResolver.Source.ORDER)
    }

    @Test
    fun `an opposite-side execution under an opening order is a venue close of that leg`() {
        val entry = market.copy(side = Side.BUY).withLegIntent(LegIntent.Open("leg-a", LegRole.INDEPENDENT))
        val r = resolver(order = entry).resolve(fill)
        assertThat(r.intent).isEqualTo(LegIntent.Close(legId = "leg-a", ticket = "T-9"))
        assertThat(r.source).isEqualTo(LegIntentResolver.Source.ORDER)

        val sameSide = resolver(order = entry).resolve(fill.copy(side = Side.BUY))
        assertThat(sameSide.intent).isEqualTo(LegIntent.Open("leg-a", LegRole.INDEPENDENT))
    }

    @Test
    fun `an unplanned order defers to the venue ticket`() {
        val r = resolver(order = market, leg = ownedLong).resolve(fill)
        assertThat(r.intent).isEqualTo(LegIntent.Close(legId = "leg-a", ticket = "T-9"))
        assertThat(r.source).isEqualTo(LegIntentResolver.Source.TICKET)
    }

    @Test
    fun `a same-side slice on an owned ticket extends that leg`() {
        val r = resolver(leg = ownedLong).resolve(fill.copy(side = Side.BUY))
        assertThat(r.intent).isEqualTo(LegIntent.Open("leg-a", LegRole.INDEPENDENT))
    }

    @Test
    fun `unknown executions net on netting venues and open a leg on hedging venues`() {
        assertThat(resolver().resolve(fill).intent).isEqualTo(LegIntent.Net)
        assertThat(resolver(mode = PositionAccountingMode.UNKNOWN).resolve(fill).intent).isEqualTo(LegIntent.Net)
        val hedged = resolver(mode = PositionAccountingMode.HEDGING).resolve(fill)
        assertThat(hedged.intent).isEqualTo(LegIntent.Open("o1", LegRole.INDEPENDENT))
        assertThat(hedged.source).isEqualTo(LegIntentResolver.Source.VENUE_DEFAULT)
    }

    private val primaryLong = ownedLong.copy(legId = "primary", role = LegRole.PRIMARY)

    @Test
    fun `a primary's ticket is one position on a hedging venue`() {
        val r = resolver(leg = primaryLong, mode = PositionAccountingMode.HEDGING).resolve(fill)
        assertThat(r.intent).isEqualTo(LegIntent.Close(legId = "primary", ticket = "T-9"))
        assertThat(r.source).isEqualTo(LegIntentResolver.Source.TICKET)
    }

    @Test
    fun `a primary's ticket nets on a netting venue because a reversal keeps it`() {
        val r = resolver(leg = primaryLong, mode = PositionAccountingMode.NETTING).resolve(fill)
        assertThat(r.intent).isEqualTo(LegIntent.Net)
        assertThat(r.source).isEqualTo(LegIntentResolver.Source.VENUE_DEFAULT)
    }
}
