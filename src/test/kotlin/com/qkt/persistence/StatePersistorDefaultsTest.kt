package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatePersistorDefaultsTest {
    /** Implements only the abstract members, so every optional member runs its default. */
    private class Minimal : StatePersistor {
        val pendingSaves = mutableListOf<Map<String, OrderRequest>>()

        override fun saveLegBook(
            strategyId: String,
            symbol: String,
            legBook: LegBook,
        ) = Unit

        override fun loadLegBook(
            strategyId: String,
            symbol: String,
        ): PersistedLegBook? = null

        override fun saveBracketPairs(
            strategyId: String,
            pairs: List<BracketPair>,
        ) = Unit

        override fun loadBracketPairs(strategyId: String): List<BracketPair> = emptyList()

        override fun savePendingOrders(
            strategyId: String,
            orders: Map<String, OrderRequest>,
        ) {
            pendingSaves += orders
        }

        override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> = emptyMap()

        override fun savePendingStacks(
            strategyId: String,
            perPrimary: Map<String, PersistedTierState>,
        ) = Unit

        override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> = emptyMap()

        override fun saveOcoLegs(
            strategyId: String,
            legs: List<PersistedOcoLeg>,
        ) = Unit

        override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> = emptyList()

        override fun clearStrategy(strategyId: String) = Unit
    }

    @Test
    fun `optional state reads empty when a persistor does not store it`() {
        val p = Minimal()
        p.savePnl("a", PersistedPnl(BigDecimal.ONE))
        p.saveTradeHistory("a", PersistedTradeHistory(emptyList()))
        p.saveSequences("a", emptyMap())
        p.saveExitHooks("a", emptyList())

        assertThat(p.loadStructures("a")).isEmpty()
        assertThat(p.legBookSymbols("a")).isEmpty()
        assertThat(p.loadTrailingStops("a")).isEmpty()
        assertThat(p.loadExcursion("a", "XAUUSD")).isNull()
        assertThat(p.loadRiskState("a")).isNull()
        assertThat(p.loadPnl("a")).isNull()
        assertThat(p.loadTradeHistory("a")).isNull()
        assertThat(p.loadSequences("a")).isEmpty()
        assertThat(p.loadExitHooks("a")).isEmpty()
        assertThat(p.loadTimedExits("a")).isEmpty()
        assertThat(p.loadStreamLane("a", "gold")).isNull()
        assertThat(p.healthSnapshot()).isEqualTo(PersistenceHealth.DISABLED)
    }

    @Test
    fun `synchronous pending save falls back to the ordinary save`() {
        val p = Minimal()
        p.savePendingOrdersSync("a", emptyMap())
        p.close()
        assertThat(p.pendingSaves).containsExactly(emptyMap())
    }
}
