package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal

/** Role of a position leg within its [LegBook]. */
enum class LegRole {
    /** Opened via a normal BUY/SELL signal. At most one PRIMARY per LegBook. */
    PRIMARY,

    /**
     * Opened via a `STACK_AT` conditional clause attached to a primary action.
     * Each STACK leg has its own bracket and tracks independently after the primary closes.
     */
    STACK,

    /**
     * A standalone position that coexists with other positions on the same symbol without
     * netting against them — e.g. each leg of an OCO_ENTRY straddle, where a filled long and
     * a filled short are two real positions, not one net-zero position. Multiple INDEPENDENT
     * legs are allowed; each carries its own bracket/exit and closes on its own. Unlike a
     * STACK it has no parent — its peers are equals, not a primary it was stacked onto.
     */
    INDEPENDENT,
}

/**
 * A single leg of a multi-leg position.
 *
 * Phase 27 introduces this to support `STACK_AT` clauses — independent micro-trades that
 * open conditionally during a live position's lifecycle. Each leg has its own bracket,
 * entry price, and lifecycle. Net quantity on a symbol is the signed sum across all legs.
 *
 * [legId] is the qkt-side stable identifier, distinct from the broker ticket. STACK legs
 * carry [parentLegId] = the PRIMARY leg's id that spawned them, used for diagnostics
 * and for the stack engine to know when to clean up its tier state.
 */
data class PositionLeg(
    val legId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
    val role: LegRole,
    val parentLegId: String? = null,
    /**
     * The venue position ticket this leg corresponds to, captured from the opening fill's
     * `brokerOrderId`. Lets a close target the exact position on a hedging account (close it
     * by ticket) rather than send an opposite order that would open a counter. Null for legs
     * opened before this was tracked, or restored as null after a restart until reconciliation
     * re-attaches the venue ticket.
     */
    val brokerTicket: String? = null,
) {
    init {
        require(quantity.signum() > 0) { "PositionLeg.quantity must be > 0: $quantity" }
        require(entryPrice.signum() > 0) { "PositionLeg.entryPrice must be > 0: $entryPrice" }
        if (role == LegRole.STACK) {
            requireNotNull(parentLegId) { "STACK leg must carry a parentLegId" }
        }
    }
}
