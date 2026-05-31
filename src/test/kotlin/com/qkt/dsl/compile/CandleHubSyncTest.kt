package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #45 — Phase 35 Task 4. Smoke coverage for the sync-group registration surface
 * on [CandleHub]: registration tracking, listener accept, and cleanup via
 * [CandleHub.unregister]. Atomic-fire-on-tick behavior lands in Task 5 and is
 * tested separately.
 */
class CandleHubSyncTest {
    private val gold = HubKey("EXNESS", "XAUUSD", "1h")
    private val silver = HubKey("EXNESS", "XAGUSD", "1h")

    private fun group() =
        SyncGroupKey(
            members = mapOf("gold" to gold, "silver" to silver),
            timeoutMs = null,
        )

    @Test
    fun `registerSyncGroup tracks the group under syncGroupKeys`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")

        val g = group()
        hub.registerSyncGroup(g, strategyId = "alpha")

        assertThat(hub.syncGroupKeys()).containsExactly(g)
    }

    @Test
    fun `registerSyncGroup is idempotent for the same owner`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")
        val g = group()

        hub.registerSyncGroup(g, strategyId = "alpha")
        hub.registerSyncGroup(g, strategyId = "alpha")

        assertThat(hub.syncGroupKeys()).containsExactly(g)
    }

    @Test
    fun `onSyncClosed on an unregistered group throws`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")

        assertThatThrownBy {
            hub.onSyncClosed(group(), "alpha") { _ -> }
        }.hasMessageContaining("unknown sync group")
    }

    @Test
    fun `unregister drops sync-group ownership and listeners`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")
        val g = group()
        hub.registerSyncGroup(g, strategyId = "alpha")
        hub.onSyncClosed(g, "alpha") { _ -> }

        hub.unregister("alpha")

        assertThat(hub.syncGroupKeys()).doesNotContain(g)
    }

    @Test
    fun `SyncGroupKey rejects members with mixed timeframes`() {
        val gold1h = HubKey("EXNESS", "XAUUSD", "1h")
        val silver1m = HubKey("EXNESS", "XAGUSD", "1m")

        assertThatThrownBy {
            SyncGroupKey(
                members = mapOf("gold" to gold1h, "silver" to silver1m),
                timeoutMs = null,
            )
        }.hasMessageContaining("same timeframe")
    }

    @Test
    fun `unregister keeps the sync group when other owners remain`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")
        hub.register(gold, retention = 5, strategyId = "beta")
        hub.register(silver, retention = 5, strategyId = "beta")
        val g = group()
        hub.registerSyncGroup(g, strategyId = "alpha")
        hub.registerSyncGroup(g, strategyId = "beta")

        hub.unregister("alpha")

        assertThat(hub.syncGroupKeys()).containsExactly(g)
    }
}
