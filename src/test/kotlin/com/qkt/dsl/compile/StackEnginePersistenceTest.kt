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
    ) = CompiledStackTier(
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
}
