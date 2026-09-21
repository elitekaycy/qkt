package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Clock
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import org.slf4j.LoggerFactory

/**
 * Turns venue positions nobody persisted into ledger legs, for a start under
 * `--reconcile=ignore-mismatches`, e.g. venue ticket 9001 (BUY 0.10 XAUUSD) on strategy `gold`
 * becomes INDEPENDENT leg `gold-XAUUSD-reconciled-9001` carrying that ticket.
 */
internal class AdoptedLegs(
    private val clock: Clock,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    // Adopt each unmatched broker position as an INDEPENDENT leg carrying its
    // venue ticket, so CLOSE / winner-timeout flattens it per-leg by ticket. A
    // STACK leg with a synthetic parent — or any ticketless leg — can only be
    // closed by a net opposite order, which on a hedging account opens a counter
    // position instead of closing it (#437). Prefer the ticketed view; fall back
    // to the ticketless positions only on venues that expose no tickets, where a
    // net close still flattens correctly.

    /** The legs to attach for [strategyId] on [symbol]: one per ticket, else one per ticketless position. */
    fun from(
        strategyId: String,
        symbol: String,
        ticketsForStrategy: List<BrokerPositionTicket>,
        brokerForSymbol: List<Position>,
    ): List<PositionLeg> =
        if (ticketsForStrategy.isNotEmpty()) {
            ticketsForStrategy.map { t ->
                val venueStop = t.stopLoss?.takeIf { it.signum() > 0 }
                val venueTarget = t.takeProfit?.takeIf { it.signum() > 0 }
                if (venueStop == null) {
                    log.error(
                        "ADOPTING UNPROTECTED position after explicit ignore-mismatches ack: " +
                            "strategy={} symbol={} ticket={} venueStop=none venueTarget={}",
                        strategyId,
                        symbol,
                        t.ticket,
                        venueTarget?.toPlainString() ?: "none",
                    )
                } else {
                    log.warn(
                        "adopting position after explicit ignore-mismatches ack: " +
                            "strategy={} symbol={} ticket={} venueStop={} venueTarget={}",
                        strategyId,
                        symbol,
                        t.ticket,
                        venueStop.toPlainString(),
                        venueTarget?.toPlainString() ?: "none",
                    )
                }
                PositionLeg(
                    legId = "$strategyId-$symbol-reconciled-${t.ticket}",
                    symbol = symbol,
                    side = t.side,
                    quantity = t.qty.abs(),
                    entryPrice = t.entryPrice,
                    // The venue's open time, so time-based exits like
                    // holding_duration survive a restart instead of
                    // restarting their clock at adoption.
                    openedAt = t.openedAt ?: clock.now(),
                    role = com.qkt.positions.LegRole.INDEPENDENT,
                    brokerTicket = t.ticket,
                )
            }
        } else {
            brokerForSymbol.map { pos ->
                val side =
                    if (pos.quantity.signum() >= 0) {
                        com.qkt.common.Side.BUY
                    } else {
                        com.qkt.common.Side.SELL
                    }
                PositionLeg(
                    legId = "$strategyId-$symbol-reconciled-${pos.quantity}",
                    symbol = symbol,
                    side = side,
                    quantity = pos.quantity.abs(),
                    entryPrice = pos.avgEntryPrice,
                    openedAt = clock.now(),
                    role = com.qkt.positions.LegRole.INDEPENDENT,
                )
            }
        }
}
