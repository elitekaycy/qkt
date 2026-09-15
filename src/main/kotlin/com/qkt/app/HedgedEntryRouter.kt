package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionLeg
import org.slf4j.LoggerFactory

/**
 * Gives a plain opposite-side `BUY`/`SELL` its netting meaning on a hedging venue.
 *
 * On a netting venue an opposite market order reduces the position. A hedging venue instead
 * opens a second ticket against the first, so the engine's net-zero book and the venue's two
 * live tickets disagree until each is closed by hand (#1154). The router turns the overlapped
 * quantity into close-by-ticket requests — the shape the `CLOSE` action already sends — oldest
 * leg first, and keeps only any excess as a fresh entry.
 *
 * Cold path — once per strategy-emitted market order.
 */
object HedgedEntryRouter {
    private val log = LoggerFactory.getLogger(HedgedEntryRouter::class.java)

    /**
     * Route [request] against the strategy's [openLegs] on its symbol. Extra requests beyond
     * the first take ids from [nextId]; the first keeps the request's own id so decision links
     * and exit hooks still find it.
     */
    fun route(
        request: OrderRequest,
        mode: PositionAccountingMode,
        openLegs: List<PositionLeg>,
        nextId: () -> String,
    ): List<OrderRequest> {
        if (mode != PositionAccountingMode.HEDGING) return listOf(request)
        if (request !is OrderRequest.Market) return listOf(request)
        if (request.legIntent != LegIntent.Unplanned) return listOf(request)
        if (request.closesLegId != null || request.closesTicket != null) return listOf(request)
        val opposite =
            openLegs
                .filter { it.symbol == request.symbol && it.side != request.side }
                .sortedWith(compareBy({ it.openedAt }, { it.legId }))
        if (opposite.isEmpty()) return listOf(request)

        val routed = mutableListOf<OrderRequest>()
        var remaining = request.quantity
        for (leg in opposite) {
            if (remaining.signum() <= 0) break
            val ticket = leg.brokerTicket
            if (ticket == null) {
                log.warn(
                    "{} {} {} on hedging venue: leg {} has no venue ticket yet, cannot close it by ticket",
                    request.strategyId,
                    request.side,
                    request.symbol,
                    leg.legId,
                )
                continue
            }
            val closing = remaining.min(leg.quantity)
            routed +=
                request.copy(
                    id = if (routed.isEmpty()) request.id else nextId(),
                    quantity = closing,
                    closesTicket = ticket,
                    closesLegId = leg.legId,
                    partialClose = closing < leg.quantity,
                )
            remaining = remaining.subtract(closing)
        }
        if (routed.isEmpty()) return listOf(request)
        if (remaining.signum() > 0) {
            routed += request.copy(id = nextId(), quantity = remaining)
        }
        log.info(
            "{} {} {} {} on hedging venue routed as {} close(s) of the opposite leg(s){}",
            request.strategyId,
            request.side,
            request.quantity.toPlainString(),
            request.symbol,
            routed.count { (it as OrderRequest.Market).closesTicket != null },
            if (remaining.signum() > 0) " + ${remaining.toPlainString()} fresh entry" else "",
        )
        return routed
    }
}
