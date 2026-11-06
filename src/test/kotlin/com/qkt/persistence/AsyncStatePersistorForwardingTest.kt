package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AsyncStatePersistorForwardingTest {
    private val store = NoopStatePersistor()

    private val riskState =
        PersistedRiskState(
            epochDay = 20_000L,
            realizedToday = BigDecimal("-40"),
            perStrategyRealizedToday = mapOf("alpha" to BigDecimal("-40")),
            halted = true,
            haltReason = "daily loss",
            haltScope = "GLOBAL",
            haltEpochDay = 20_000L,
            strategyHalts = emptyList(),
        )

    private fun binding(stopIds: MutableList<String>) =
        PersistedExitHookBinding(
            bindingId = "b1",
            strategyId = "alpha",
            symbol = "XAUUSD",
            entrySide = Side.BUY,
            definitionId = "hook",
            fingerprint = "fp",
            entryOrderIds = listOf("e1"),
            stopOrderIds = stopIds,
            takeProfitOrderIds = emptyList(),
            activeQuantity = BigDecimal.ONE,
            exitQuantity = BigDecimal.ZERO,
            exitPnl = BigDecimal.ZERO,
        )

    @Test
    fun `queued writes of every state kind reach the delegate in order`() {
        val pair = BracketPair("entry", "sl", "tp", "leg-1")
        val exit =
            PersistedTimeExit(
                id = "t1",
                strategyId = "alpha",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                legId = "leg-1",
                ticket = null,
                deadlineMs = 9_000L,
            )
        AsyncStatePersistor(store).use { async ->
            async.saveBracketPairs("alpha", listOf(pair))
            async.saveTimedExits("alpha", listOf(exit))
            async.saveRiskState("alpha", riskState)
            async.savePnl("alpha", PersistedPnl(BigDecimal("125.5")))
            async.savePendingStacks("alpha", emptyMap())
            async.saveOcoLegs("alpha", emptyList())
            async.saveTrailingStops("alpha", emptyList())
            async.saveStructures("alpha", emptyList())
            assertThat(async.awaitDrain()).isTrue
        }
        assertThat(store.loadBracketPairs("alpha")).containsExactly(pair)
        assertThat(store.loadTimedExits("alpha")).containsExactly(exit)
        assertThat(store.loadRiskState("alpha")).isEqualTo(riskState)
        assertThat(store.loadPnl("alpha")).isEqualTo(PersistedPnl(BigDecimal("125.5")))
    }

    @Test
    fun `exit hooks are detached from the caller's lists before queueing`() {
        val stops = mutableListOf("s1")
        AsyncStatePersistor(store).use { async ->
            async.saveExitHooks("alpha", listOf(binding(stops)))
            stops += "s2"
            assertThat(async.awaitDrain()).isTrue
        }
        assertThat(store.loadExitHooks("alpha").single().stopOrderIds).containsExactly("s1")
    }

    @Test
    fun `clear strategy is queued behind earlier writes`() {
        AsyncStatePersistor(store).use { async ->
            async.savePnl("alpha", PersistedPnl(BigDecimal.TEN))
            async.clearStrategy("alpha")
            assertThat(async.awaitDrain()).isTrue
        }
        assertThat(store.loadPnl("alpha")).isNull()
    }

    @Test
    fun `a write after close is dropped instead of reaching the delegate`() {
        val async = AsyncStatePersistor(store)
        async.close()
        async.savePnl("alpha", PersistedPnl(BigDecimal.ONE))
        assertThat(async.awaitDrain()).isTrue
        assertThat(store.loadPnl("alpha")).isNull()
    }

    @Test
    fun `health reports the delegate's counters with the queue depth`() {
        val delegate =
            object : StatePersistor by store {
                override fun healthSnapshot() = PersistenceHealth(enabled = true, totalWrites = 7L)
            }
        AsyncStatePersistor(delegate).use { async ->
            val health = async.healthSnapshot()
            assertThat(health.enabled).isTrue
            assertThat(health.totalWrites).isEqualTo(7L)
            assertThat(health.queueSize).isZero
            assertThat(health.callerRunsTotal).isZero
        }
    }
}
