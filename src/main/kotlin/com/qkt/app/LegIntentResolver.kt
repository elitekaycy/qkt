package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.openingLegIntent
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg

/**
 * Recovers the [LegIntent] behind an execution, in a fixed precedence:
 *
 * 1. the intent carried by the order the fill names — the normal path, restart included, since
 *    pending orders persist with their intent;
 * 2. a leg this strategy already owns under the fill's venue ticket — the venue-detected close
 *    or a late slice arriving after the order record is gone;
 * 3. the venue's default: net where the venue nets; on a hedging venue an unknown execution is
 *    a new ticket, never a netting fill.
 *
 * Every step is an O(1) lookup or a single in-place scan of one symbol's legs; nothing here
 * allocates per fill.
 */
class LegIntentResolver(
    private val orderFor: (clientOrderId: String) -> OrderRequest?,
    /** The leg of any role carrying a venue ticket; the resolver applies the role rule itself. */
    private val legByTicket: (strategyId: String, symbol: String, ticket: String) -> PositionLeg?,
    private val positionMode: (symbol: String) -> PositionAccountingMode,
) {
    /** Where the resolved intent came from — reported alongside the intent for diagnostics. */
    enum class Source { ORDER, TICKET, VENUE_DEFAULT }

    data class Resolution(
        val intent: LegIntent,
        val source: Source,
    )

    fun resolve(fill: BrokerEvent.OrderFilled): Resolution {
        val ticket = fill.brokerOrderId?.takeIf { it.isNotBlank() }
        val order = orderFor(fill.clientOrderId)
        if (order != null) {
            val intent = order.openingLegIntent()
            // A venue-detected close is reported under the ENTRY's client id (the MT5 poller
            // has no other id for it). An opening order only ever executes on its own side, so
            // an opposite-side execution under that id can only be closing the leg it opened.
            if (intent is LegIntent.Open && fill.side != order.side) {
                return Resolution(LegIntent.Close(legId = intent.legId, ticket = ticket), Source.ORDER)
            }
            if (intent != LegIntent.Unplanned) return Resolution(intent, Source.ORDER)
        }
        if (ticket != null) {
            val owned =
                legByTicket(fill.strategyId, fill.symbol, ticket)
                    ?.takeIf { ticketIsOnePosition(it, fill.symbol) }
            if (owned != null) {
                val intent =
                    if (owned.side == fill.side) {
                        LegIntent.Open(owned.legId, owned.role, owned.parentLegId)
                    } else {
                        LegIntent.Close(legId = owned.legId, ticket = ticket)
                    }
                return Resolution(intent, Source.TICKET)
            }
        }
        val fallback =
            if (positionMode(fill.symbol) == PositionAccountingMode.HEDGING) {
                LegIntent.Open(fill.clientOrderId, LegRole.INDEPENDENT)
            } else {
                LegIntent.Net
            }
        return Resolution(fallback, Source.VENUE_DEFAULT)
    }

    /**
     * Whether an execution on [leg]'s ticket can only be an execution on that leg. On a
     * hedging venue a ticket is one position, whatever the leg's role. On a netting venue the
     * PRIMARY is the venue's netted position and keeps its ticket across a reversal, so an
     * execution there nets — the STACK and INDEPENDENT legs are still one position each.
     */
    private fun ticketIsOnePosition(
        leg: PositionLeg,
        symbol: String,
    ): Boolean = leg.role != LegRole.PRIMARY || positionMode(symbol) == PositionAccountingMode.HEDGING
}
