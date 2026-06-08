package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal

/**
 * Durable storage for the in-memory engine state that doesn't survive restart:
 * leg metadata, bracket linkages, in-flight orders, and STACK_AT tier-fired state.
 *
 * Production implementations ([FileStatePersistor]) write atomic JSON files under
 * `<stateRoot>/<strategyId>/`, where the root is the daemon state directory (honors
 * `QKT_STATE_DIR`). Tests use [NoopStatePersistor] (in-memory) by default.
 *
 * All methods are write-on-mutate: the call site invokes a `save*` method synchronously
 * after every mutation to the underlying state object. Reads happen once at boot via
 * [com.qkt.persistence.LegBookReconciler].
 */
interface StatePersistor {
    fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    )

    fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook?

    fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    )

    fun loadBracketPairs(strategyId: String): List<BracketPair>

    fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    )

    fun loadPendingOrders(strategyId: String): Map<String, OrderRequest>

    fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    )

    fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState>

    /** Persist the live OCO legs for [strategyId] — identity + linkage for restart recovery. */
    fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    )

    /** Restore the live OCO legs for [strategyId]; empty when none persisted. */
    fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg>

    fun clearStrategy(strategyId: String)
}

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

data class PersistedLegBook(
    val strategyId: String,
    val symbol: String,
    val legs: List<PersistedLeg>,
)

data class BracketPair(
    val entryClientOrderId: String,
    val stopLossClientOrderId: String?,
    val takeProfitClientOrderId: String?,
    val legId: String?,
)

data class PersistedTier(
    val index: Int,
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
    val fired: Boolean,
    val firedAt: Long?,
    val firedLegId: String?,
)

data class PersistedTierState(
    val primaryClientOrderId: String,
    val tiers: List<PersistedTier>,
)
