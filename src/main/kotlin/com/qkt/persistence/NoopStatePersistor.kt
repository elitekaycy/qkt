package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [StatePersistor]. Tests use this to inspect what would have been persisted
 * without touching disk. Production code can also use it to disable persistence entirely
 * (e.g. `state.enabled = false` in `qkt.config.yaml`).
 */
class NoopStatePersistor : StatePersistor {
    private data class StrategyState(
        val legBooks: ConcurrentHashMap<String, PersistedLegBook> = ConcurrentHashMap(),
        var bracketPairs: List<BracketPair> = emptyList(),
        var pendingOrders: Map<String, OrderRequest> = emptyMap(),
        var pendingStacks: Map<String, PersistedTierState> = emptyMap(),
        var ocoLegs: List<PersistedOcoLeg> = emptyList(),
        var riskState: PersistedRiskState? = null,
        var pnl: PersistedPnl? = null,
    )

    private val state: ConcurrentHashMap<String, StrategyState> = ConcurrentHashMap()

    private fun stateFor(strategyId: String): StrategyState = state.getOrPut(strategyId) { StrategyState() }

    override fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    ) {
        stateFor(strategyId).legBooks[symbol] =
            PersistedLegBook(
                strategyId = strategyId,
                symbol = symbol,
                legs = legBook.all().map { PersistedLeg.fromPositionLeg(it) },
            )
    }

    override fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook? = state[strategyId]?.legBooks?.get(symbol)

    override fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    ) {
        stateFor(strategyId).bracketPairs = pairs
    }

    override fun loadBracketPairs(strategyId: String): List<BracketPair> =
        state[strategyId]?.bracketPairs ?: emptyList()

    override fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        stateFor(strategyId).pendingOrders = orders
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> =
        state[strategyId]?.pendingOrders ?: emptyMap()

    override fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) {
        stateFor(strategyId).pendingStacks = perPrimary
    }

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> =
        state[strategyId]?.pendingStacks ?: emptyMap()

    override fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    ) {
        stateFor(strategyId).ocoLegs = legs
    }

    override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> = state[strategyId]?.ocoLegs ?: emptyList()

    override fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) {
        stateFor(strategyId).riskState = state
    }

    override fun loadRiskState(strategyId: String): PersistedRiskState? = state[strategyId]?.riskState

    override fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) {
        stateFor(strategyId).pnl = state
    }

    override fun loadPnl(strategyId: String): PersistedPnl? = state[strategyId]?.pnl

    override fun clearStrategy(strategyId: String) {
        state.remove(strategyId)
    }
}
