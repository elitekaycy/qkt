package com.qkt.app

import com.qkt.app.ExitHookManagerFixtures.StubDslStrategy
import com.qkt.app.ExitHookManagerFixtures.ref
import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTier
import com.qkt.persistence.PersistedTierState
import com.qkt.persistence.StatePersistor
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackOrchestratorBinderTest {
    private val clock = FixedClock(0L)
    private val bus = newBus()
    private val positions = StrategyPositionTracker()
    private val emitted = mutableListOf<Signal>()

    private fun tierState() =
        PersistedTierState(
            primaryClientOrderId = "entry-1",
            tiers =
                listOf(
                    PersistedTier(
                        index = 0,
                        mfeThreshold = BigDecimal("10"),
                        withinMs = 1_800_000L,
                        stackQuantity = BigDecimal("0.5"),
                        slDistance = BigDecimal("5"),
                        tpDistance = BigDecimal("20"),
                        fired = false,
                        firedAt = null,
                        firedLegId = null,
                    ),
                ),
            openedAtMs = 0L,
        )

    private fun openParent() =
        positions.addIndependentLeg(
            "s",
            PositionLeg("leg-1", "XAUUSD", Side.BUY, BigDecimal.ONE, BigDecimal("2000"), 0L, LegRole.INDEPENDENT),
        )

    @Test
    fun `a stack persisted for an open parent is rebuilt and fires on the next qualifying tick`() {
        val persistor = NoopStatePersistor()
        persistor.savePendingStacks("s", mapOf("leg-1" to tierState(), "gone-leg" to tierState()))
        openParent()

        StackOrchestratorBinder(clock, bus, persistor, positions).bind(StubDslStrategy(ref), "s") { emitted += it }
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("2050"), 1L)))

        assertThat(emitted).hasSize(1)
    }

    @Test
    fun `an unreadable stack store leaves the strategy bound with nothing to restore`() {
        val failing =
            object : StatePersistor by NoopStatePersistor() {
                override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> = error("disk gone")
            }
        openParent()

        StackOrchestratorBinder(clock, bus, failing, positions).bind(StubDslStrategy(ref), "s") { emitted += it }
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("2050"), 1L)))

        assertThat(emitted).isEmpty()
    }
}
