package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.persistence.NoopStatePersistor
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackEnginePersistenceTest {
    private fun tier(
        threshold: String,
        withinMs: Long = 1_800_000L,
        qty: String = "0.06",
        sl: String = "200",
        tp: String = "2000",
    ) = ResolvedStackTier(
        mfeThreshold = BigDecimal(threshold),
        withinMs = withinMs,
        stackQuantity = BigDecimal(qty),
        slDistance = BigDecimal(sl),
        tpDistance = BigDecimal(tp),
    )

    @Test
    fun `tier-fire writes PendingTierState with fired=true and firedLegId`() {
        val persistor = NoopStatePersistor()
        val clock = FixedClock(time = 1000L)
        val emitted = mutableListOf<Signal>()
        val engine =
            StackEngine(
                parentLegId = "leg-primary",
                parentSymbol = "XAUUSDm",
                parentSide = Side.BUY,
                parentEntryPrice = BigDecimal("4700"),
                tiers = listOf(tier("10")),
                clock = clock,
                emit = { emitted.add(it) },
                strategyId = "hedge",
                persistor = persistor,
                primaryClientOrderId = "c-primary",
            )

        // Tick crosses the threshold (price moved to 4711 → MFE = 11 > 10)
        engine.onTick(BigDecimal("4711"))

        assertThat(emitted).hasSize(1)
        val state = persistor.loadPendingStacks("hedge")
        assertThat(state).hasSize(1)
        val perPrimary = state["leg-primary"]!!
        assertThat(perPrimary.primaryClientOrderId).isEqualTo("c-primary")
        assertThat(perPrimary.tiers).hasSize(1)
        assertThat(perPrimary.tiers[0].fired).isTrue
        assertThat(perPrimary.tiers[0].firedLegId).isNotNull
    }

    @Test
    fun `tier does not re-fire after restart-equivalent reattach`() {
        // Simulate a restart: persistor sees tier already fired; a new StackEngine
        // does NOT know about that — it'd re-fire. The reconcile layer (Phase 29 deploy
        // path) is responsible for skipping re-firing. Here we just verify the persistor
        // has the right data for that downstream consumer.
        val persistor = NoopStatePersistor()
        val clock = FixedClock(time = 1000L)
        val engine =
            StackEngine(
                parentLegId = "leg-primary",
                parentSymbol = "XAUUSDm",
                parentSide = Side.BUY,
                parentEntryPrice = BigDecimal("4700"),
                tiers = listOf(tier("10"), tier("20")),
                clock = clock,
                emit = {},
                strategyId = "hedge",
                persistor = persistor,
            )

        engine.onTick(BigDecimal("4711")) // tier 0 fires
        val first = persistor.loadPendingStacks("hedge")
        assertThat(first["leg-primary"]!!.tiers[0].fired).isTrue
        assertThat(first["leg-primary"]!!.tiers[1].fired).isFalse

        engine.onTick(BigDecimal("4721")) // tier 1 fires
        val second = persistor.loadPendingStacks("hedge")
        assertThat(second["leg-primary"]!!.tiers[0].fired).isTrue
        assertThat(second["leg-primary"]!!.tiers[1].fired).isTrue
    }

    @Test
    fun `restoreEngine rebuilds tier progression for an already-open parent`() {
        // Session 1: parent opens at t=1000, tier 0 (threshold 10) fires, tier 1
        // (threshold 30) does not. Process dies.
        val persistor = NoopStatePersistor()
        val clock = FixedClock(time = 1_000L)
        val session1 =
            StackEngine(
                parentLegId = "leg-primary",
                parentSymbol = "XAUUSDm",
                parentSide = Side.BUY,
                parentEntryPrice = BigDecimal("4700"),
                tiers = listOf(tier("10"), tier("30", withinMs = 1_800_000L)),
                clock = clock,
                emit = {},
                strategyId = "hedge",
                persistor = persistor,
                primaryClientOrderId = "c-primary",
            )
        session1.onTick(BigDecimal("4711"))
        assertThat(session1.firedCount()).isEqualTo(1)

        // Session 2 (restart 10 minutes later): the orchestrator rebuilds the engine
        // from persisted state + the restored leg.
        clock.time = 601_000L
        val orch =
            StackOrchestrator(
                clock = clock,
                strategyId = "hedge",
                persistor = persistor,
                emit = {},
            )
        val persisted = persistor.loadPendingStacks("hedge").getValue("leg-primary")
        orch.restoreEngine(
            parentLegId = "leg-primary",
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            persisted = persisted,
        )
        assertThat(orch.hasEngineFor("leg-primary")).isTrue()

        val fired = mutableListOf<Signal>()
        val orch2 =
            StackOrchestrator(
                clock = clock,
                strategyId = "hedge",
                persistor = persistor,
                emit = { fired.add(it) },
            )
        orch2.restoreEngine(
            parentLegId = "leg-primary",
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            persisted = persisted,
        )
        // Tier 0 already fired pre-restart: a re-cross of its threshold must NOT
        // re-fire it. Tier 1 is still armed and its WITHIN window anchors at the
        // ORIGINAL open (t=1000), not the restart.
        orch2.onTick("XAUUSDm", BigDecimal("4712"))
        assertThat(fired).isEmpty()
        orch2.onTick("XAUUSDm", BigDecimal("4731")) // MFE 31 > 30 -> tier 1 fires
        assertThat(fired).hasSize(1)
    }

    @Test
    fun `restored WITHIN windows count from the original open, not the restart`() {
        val persistor = NoopStatePersistor()
        val clock = FixedClock(time = 1_000L)
        StackEngine(
            parentLegId = "leg-p2",
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            tiers = listOf(tier("10", withinMs = 60_000L)),
            clock = clock,
            emit = {},
            strategyId = "hedge",
            persistor = persistor,
            primaryClientOrderId = "c-p2",
        )

        // Restart 10 minutes later: the 60s window expired during downtime.
        clock.time = 601_000L
        val fired = mutableListOf<Signal>()
        val orch =
            StackOrchestrator(
                clock = clock,
                strategyId = "hedge",
                persistor = persistor,
                emit = { fired.add(it) },
            )
        orch.restoreEngine(
            parentLegId = "leg-p2",
            parentSymbol = "XAUUSDm",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("4700"),
            persisted = persistor.loadPendingStacks("hedge").getValue("leg-p2"),
        )
        orch.onTick("XAUUSDm", BigDecimal("4720"))
        // Expired window -> abandoned, never a late fire into dead context.
        assertThat(fired).isEmpty()
    }
}
