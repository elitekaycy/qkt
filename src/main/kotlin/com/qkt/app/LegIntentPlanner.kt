package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.withLegIntent
import com.qkt.positions.LegRole

/**
 * Decides, once, what a fill of each leaf in [OrderRequest] means to the position ledger.
 *
 * This is the only place the venue's [PositionAccountingMode] influences booking. It runs at
 * the pipeline's emit path and again at [OrderManager.submit] (flatten and operator-kill
 * requests reach the manager without passing the pipeline), so it must be idempotent: an
 * already-planned leaf is returned unchanged.
 *
 * Cold path — once per order, never per tick or per fill.
 */
object LegIntentPlanner {
    /** Plan [request] for a symbol whose venue accounts positions in [mode]. */
    fun plan(
        request: OrderRequest,
        mode: PositionAccountingMode,
    ): OrderRequest =
        when (request) {
            is OrderRequest.Market -> planMarket(request, mode)
            is OrderRequest.IfTouched -> planIfTouched(request)
            is OrderRequest.Limit -> planEntry(request, mode)
            is OrderRequest.Stop -> planEntry(request, mode)
            is OrderRequest.Bracket -> planBracket(request, mode)
            is OrderRequest.StandaloneOCO -> planOco(request, mode)
            // Engine-managed shapes mint their own leaves inside OrderManager, which stamps
            // them there; strategy-built OTO/ScaleOut/TimeExit leaves are booked the way the
            // venue nets them today. Trailing and managed stops are exits minted by the
            // manager and carry their intent from the bracket they protect.
            is OrderRequest.StopLimit,
            is OrderRequest.TrailingStop,
            is OrderRequest.TrailingStopLimit,
            is OrderRequest.ArmedTrailingStop,
            is OrderRequest.SteppedStop,
            is OrderRequest.TimeTighteningStop,
            is OrderRequest.OTO,
            is OrderRequest.ScaleOut,
            is OrderRequest.TimeExit,
            is OrderRequest.Stack,
            -> request
        }

    private fun planMarket(
        request: OrderRequest.Market,
        mode: PositionAccountingMode,
    ): OrderRequest {
        if (request.legIntent != LegIntent.Unplanned) return request
        if (request.closesLegId != null || request.closesTicket != null) {
            return request.copy(
                legIntent =
                    LegIntent.Close(
                        legId = request.closesLegId,
                        ticket = request.closesTicket,
                        partial = request.partialClose,
                    ),
            )
        }
        return planEntry(request, mode)
    }

    private fun planIfTouched(request: OrderRequest.IfTouched): OrderRequest {
        if (request.legIntent != LegIntent.Unplanned || request.closesTicket == null) return request
        return request.copy(
            legIntent = LegIntent.Close(ticket = request.closesTicket, partial = request.partialClose),
        )
    }

    /**
     * A plain entry books its own coexisting leg on a hedging venue and nets everywhere else.
     * UNKNOWN keeps the netting book: reconciliation treats it conservatively, but the engine
     * only opens independent legs when the venue has confirmed it holds them that way.
     */
    private fun planEntry(
        request: OrderRequest,
        mode: PositionAccountingMode,
    ): OrderRequest {
        if (request.legIntent != LegIntent.Unplanned) return request
        return request.withLegIntent(entryIntent(request.id, mode))
    }

    private fun planBracket(
        request: OrderRequest.Bracket,
        mode: PositionAccountingMode,
    ): OrderRequest {
        if (request.entry.legIntent != LegIntent.Unplanned) return request
        return request.withLegIntent(entryIntent(request.id, mode))
    }

    /**
     * Two brackets under one OCO are an entry straddle: whichever fills is a real position and
     * the pair may both fill on a fast market, so each leg is independent on every venue. Any
     * other OCO plans its legs individually.
     */
    private fun planOco(
        request: OrderRequest.StandaloneOCO,
        mode: PositionAccountingMode,
    ): OrderRequest {
        val leg1 = request.leg1
        val leg2 = request.leg2
        if (leg1 is OrderRequest.Bracket && leg2 is OrderRequest.Bracket) {
            return request.copy(
                leg1 = planStraddleLeg(leg1),
                leg2 = planStraddleLeg(leg2),
            )
        }
        return request.copy(leg1 = plan(leg1, mode), leg2 = plan(leg2, mode))
    }

    private fun planStraddleLeg(leg: OrderRequest.Bracket): OrderRequest =
        if (leg.entry.legIntent != LegIntent.Unplanned) {
            leg
        } else {
            leg.withLegIntent(LegIntent.Open(leg.id, LegRole.INDEPENDENT))
        }

    private fun entryIntent(
        legId: String,
        mode: PositionAccountingMode,
    ): LegIntent =
        if (mode == PositionAccountingMode.HEDGING) {
            LegIntent.Open(legId, LegRole.INDEPENDENT)
        } else {
            LegIntent.Net
        }
}
