package com.qkt.execution

import com.qkt.positions.LegRole

/**
 * What a fill of this order means to the position ledger.
 *
 * Carried on every leaf [OrderRequest] so the booking decision travels with the order — through
 * the journal, persistence, the broker, and back on the fill — instead of living in transient
 * side state keyed by client order id. Decided once by the planner at emit time; composites
 * ([OrderRequest.Bracket], stacks, OCO wrappers) stamp the leaves they mint.
 */
sealed interface LegIntent {
    /** Open (or extend) leg [legId] with [role]; STACK legs name their [parentLegId]. */
    data class Open(
        val legId: String,
        val role: LegRole,
        val parentLegId: String? = null,
    ) : LegIntent {
        init {
            require(legId.isNotBlank()) { "Open.legId must not be blank" }
            if (role == LegRole.STACK) {
                requireNotNull(parentLegId) { "STACK open intent must carry a parentLegId" }
            }
        }
    }

    /**
     * Close (or reduce, when [partial]) one specific leg — by qkt [legId], by venue [ticket], or
     * both. At least one identifier is required.
     */
    data class Close(
        val legId: String? = null,
        val ticket: String? = null,
        val partial: Boolean = false,
    ) : LegIntent {
        init {
            require(legId != null || ticket != null) { "Close intent needs a legId or a ticket" }
        }
    }

    /** Net against the single PRIMARY leg — legal only where the venue itself nets. */
    data object Net : LegIntent

    /** Not decided yet. A leaf must be planned before it reaches a broker. */
    data object Unplanned : LegIntent
}
