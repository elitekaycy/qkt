package com.qkt.broker.mt5

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

internal class MT5VenueCostLedger(
    private val closedRetentionMs: Long,
) {
    private class PositionCosts {
        val dealTickets: MutableSet<Long> = mutableSetOf()

        @Volatile
        var closedAtMs: Long? = null
    }

    private val byPosition: MutableMap<Long, PositionCosts> = ConcurrentHashMap()

    fun book(
        positionTicket: Long,
        deals: List<MT5Deal>,
        positionClosed: Boolean,
        nowMs: Long,
    ): BigDecimal {
        byPosition.entries.removeIf { (_, costs) ->
            costs.closedAtMs?.let { nowMs - it >= closedRetentionMs } == true
        }
        val position = byPosition.computeIfAbsent(positionTicket) { PositionCosts() }
        return synchronized(position) {
            var newlyBooked = BigDecimal.ZERO
            for (deal in deals) {
                val identity = dealIdentity(deal)
                if (!position.dealTickets.add(identity)) continue
                val reported = deal.commission.add(deal.swap).add(deal.fee)
                newlyBooked = newlyBooked.add(reported.negate())
            }
            if (positionClosed && position.closedAtMs == null) position.closedAtMs = nowMs
            newlyBooked
        }
    }

    private fun dealIdentity(deal: MT5Deal): Long {
        if (deal.ticket != 0L) return deal.ticket
        var result = deal.orderTicket
        result = 31L * result + deal.timeMs
        result = 31L * result + deal.entry
        result = 31L * result + deal.volume.hashCode()
        result = 31L * result + deal.price.hashCode()
        return result
    }
}
