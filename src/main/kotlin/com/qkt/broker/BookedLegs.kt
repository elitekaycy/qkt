package com.qkt.broker

import com.qkt.positions.StrategyLegReads

/** The legs of [strategyIds] in this ledger that carry a venue ticket, as a broker watches them ([Broker.watchBookedLegs]). */
fun StrategyLegReads.bookedLegs(strategyIds: Collection<String>): List<BookedLeg> =
    strategyIds.flatMap { strategyId ->
        allLegsFor(strategyId).mapNotNull { leg ->
            val ticket = leg.brokerTicket ?: return@mapNotNull null
            BookedLeg(
                strategyId = strategyId,
                legId = leg.legId,
                ticket = ticket,
                symbol = leg.symbol,
                side = leg.side,
                quantity = leg.quantity,
                entryPrice = leg.entryPrice,
                openedAt = leg.openedAt,
            )
        }
    }
