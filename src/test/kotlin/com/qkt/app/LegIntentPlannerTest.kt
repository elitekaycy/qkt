package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.execution.openingLegIntent
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegIntentPlannerTest {
    private val qty = BigDecimal.ONE

    private fun market(
        id: String = "m1",
        closesLegId: String? = null,
        closesTicket: String? = null,
        partial: Boolean = false,
    ) = OrderRequest.Market(
        id = id,
        symbol = "XAUUSD",
        side = Side.BUY,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
        strategyId = "s",
        closesLegId = closesLegId,
        closesTicket = closesTicket,
        partialClose = partial,
    )

    private fun bracket(id: String = "b1") =
        OrderRequest.Bracket(
            id = id,
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = qty,
            entry = market(id = "$id-entry"),
            takeProfit = BigDecimal("2450"),
            stopLoss = StopLossSpec.Fixed(BigDecimal("2350")),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s",
        )

    @Test
    fun `plain entries net on netting and unknown venues and open independent legs on hedging`() {
        assertThat(LegIntentPlanner.plan(market(), PositionAccountingMode.NETTING).legIntent)
            .isEqualTo(LegIntent.Net)
        assertThat(LegIntentPlanner.plan(market(), PositionAccountingMode.UNKNOWN).legIntent)
            .isEqualTo(LegIntent.Net)
        assertThat(LegIntentPlanner.plan(market(), PositionAccountingMode.HEDGING).legIntent)
            .isEqualTo(LegIntent.Open("m1", LegRole.INDEPENDENT))
    }

    @Test
    fun `close fields become a Close intent on every venue`() {
        val close = market(closesLegId = "leg-1", closesTicket = "42", partial = true)
        for (mode in PositionAccountingMode.entries) {
            assertThat(LegIntentPlanner.plan(close, mode).legIntent)
                .isEqualTo(LegIntent.Close(legId = "leg-1", ticket = "42", partial = true))
        }
    }

    @Test
    fun `a bracket entry opens the bracket's leg id on hedging and nets otherwise`() {
        val hedged = LegIntentPlanner.plan(bracket(), PositionAccountingMode.HEDGING) as OrderRequest.Bracket
        assertThat(hedged.entry.legIntent).isEqualTo(LegIntent.Open("b1", LegRole.INDEPENDENT))

        val netted = LegIntentPlanner.plan(bracket(), PositionAccountingMode.NETTING) as OrderRequest.Bracket
        assertThat(netted.entry.legIntent).isEqualTo(LegIntent.Net)
    }

    @Test
    fun `an OCO of two brackets is a straddle and both legs are independent on any venue`() {
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = qty,
                leg1 = bracket("long"),
                leg2 = bracket("short"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s",
            )
        val planned = LegIntentPlanner.plan(oco, PositionAccountingMode.NETTING) as OrderRequest.StandaloneOCO
        assertThat(planned.leg1.openingLegIntent()).isEqualTo(LegIntent.Open("long", LegRole.INDEPENDENT))
        assertThat(planned.leg2.openingLegIntent()).isEqualTo(LegIntent.Open("short", LegRole.INDEPENDENT))
    }

    @Test
    fun `an IfTouched with a ticket is a ticket close and without one is left alone`() {
        val touched =
            OrderRequest.IfTouched(
                id = "t1",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = qty,
                triggerPrice = BigDecimal("2500"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                closesTicket = "77",
                partialClose = true,
            )
        assertThat(LegIntentPlanner.plan(touched, PositionAccountingMode.HEDGING).legIntent)
            .isEqualTo(LegIntent.Close(ticket = "77", partial = true))
        assertThat(
            LegIntentPlanner
                .plan(
                    touched.copy(closesTicket = null, partialClose = false),
                    PositionAccountingMode.HEDGING,
                ).legIntent,
        ).isEqualTo(LegIntent.Unplanned)
    }

    @Test
    fun `planning is idempotent and never overwrites an intent already decided`() {
        val stack =
            bracket("tier").let {
                it.copy(
                    entry =
                        (it.entry as OrderRequest.Market).copy(
                            legIntent = LegIntent.Open("tier", LegRole.STACK, "parent"),
                        ),
                )
            }
        val once = LegIntentPlanner.plan(stack, PositionAccountingMode.NETTING)
        val twice = LegIntentPlanner.plan(once, PositionAccountingMode.HEDGING)
        assertThat(twice).isEqualTo(once)
        assertThat(twice.openingLegIntent()).isEqualTo(LegIntent.Open("tier", LegRole.STACK, "parent"))
    }
}
