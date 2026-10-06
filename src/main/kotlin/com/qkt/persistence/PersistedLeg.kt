package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal

/** On-disk shape of one open position leg, as a leg book persists it; [toPositionLeg] restores it. */
data class PersistedLeg(
    val legId: String,
    val parentLegId: String?,
    val role: LegRole,
    val side: Side,
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
    val brokerTicket: String? = null,
) {
    fun toPositionLeg(): PositionLeg =
        PositionLeg(
            legId = legId,
            parentLegId = parentLegId,
            role = role,
            side = side,
            symbol = symbol,
            quantity = quantity,
            entryPrice = entryPrice,
            openedAt = openedAt,
            brokerTicket = brokerTicket,
        )

    companion object {
        fun fromPositionLeg(leg: PositionLeg): PersistedLeg =
            PersistedLeg(
                legId = leg.legId,
                parentLegId = leg.parentLegId,
                role = leg.role,
                side = leg.side,
                symbol = leg.symbol,
                quantity = leg.quantity,
                entryPrice = leg.entryPrice,
                openedAt = leg.openedAt,
                brokerTicket = leg.brokerTicket,
            )
    }
}

/** On-disk shape of one strategy's leg book on [symbol]. */
data class PersistedLegBook(
    val strategyId: String,
    val symbol: String,
    val legs: List<PersistedLeg>,
)
