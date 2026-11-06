package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.PersistedRiskState
import com.qkt.persistence.PersistedStrategyHalt
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Day 10 throughout: what a restore revives depends on each halt's scope and day. */
class RiskStateRestoreScopesTest {
    private val day = 86_400_000L
    private val clock = FixedClock(10 * day + 1L)

    /** A provider whose realized total cannot be restored. */
    private val fixedPnL =
        object : PnLProvider {
            override fun realizedTotal(): BigDecimal = BigDecimal("7")

            override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

            override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

            override fun totalPnL(): BigDecimal = BigDecimal("7")
        }

    private fun state(persist: ((PersistedRiskState) -> Unit)? = null): RiskState {
        val prices = MarketPriceTracker()
        return RiskState(
            fixedPnL,
            StrategyPnL(StrategyPositionTracker(), prices),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            persist = persist,
        )
    }

    private fun persisted(
        halted: Boolean = false,
        scope: String = "PERSISTENT",
        haltDay: Long = 10L,
        strategyHalts: List<PersistedStrategyHalt> = emptyList(),
    ) = PersistedRiskState(
        epochDay = 10L,
        realizedToday = BigDecimal.ZERO,
        perStrategyRealizedToday = emptyMap(),
        halted = halted,
        haltReason = "why",
        haltScope = scope,
        haltEpochDay = haltDay,
        strategyHalts = strategyHalts,
        globalRealizedTotal = BigDecimal("99"),
        monthKey = 0L,
        realizedMonth = null,
    )

    @Test
    fun `an unreadable halt scope is restored as persistent`() {
        val s = state()

        s.restore(persisted(halted = true, scope = "SOMETIMES", haltDay = 1L))

        assertThat(s.halted).isTrue()
        assertThat(s.globalHaltScope()).isEqualTo(HaltScope.PERSISTENT)
        assertThat(s.haltReason).isEqualTo("why")
    }

    @Test
    fun `transient and stale daily halts stay down while today's daily halt comes back`() {
        val s = state()

        s.restore(persisted(halted = true, scope = "TRANSIENT"))
        assertThat(s.halted).isFalse()

        s.restore(persisted(halted = true, scope = "DAILY", haltDay = 9L))
        assertThat(s.halted).isFalse()

        s.restore(persisted(halted = true, scope = "DAILY", haltDay = 10L))
        assertThat(s.globalHaltScope()).isEqualTo(HaltScope.DAILY)
    }

    @Test
    fun `strategy halts come back only when persistent, unreadable or from today`() {
        val s = state()
        val halts =
            listOf(
                PersistedStrategyHalt("persistent", "r", "PERSISTENT", 1L),
                PersistedStrategyHalt("garbled", "r", "???", 1L),
                PersistedStrategyHalt("today", "r", "DAILY", 10L),
                PersistedStrategyHalt("yesterday", "r", "DAILY", 9L),
                PersistedStrategyHalt("transient", "r", "TRANSIENT", 10L),
            )

        s.restore(persisted(strategyHalts = halts))

        assertThat(s.strategyHalts().map { it.strategyId })
            .containsExactlyInAnyOrder("persistent", "garbled", "today")
    }

    @Test
    fun `a realized total is not restored into a provider that cannot take it, and a missing month reads zero`() {
        val s = state()

        s.restore(persisted())

        assertThat(s.snapshot().globalRealizedTotal).isEqualByComparingTo("7")
        assertThat(s.monthlyPnLTracker.globalRealizedToday()).isEqualByComparingTo("0")
    }

    @Test
    fun `a transient halt is never written to the restart record`() {
        val s = state()
        s.halt("blip", HaltScope.TRANSIENT)
        s.haltStrategy("a", "blip", HaltScope.TRANSIENT)
        s.haltStrategy("b", "real", HaltScope.PERSISTENT)

        val snap = s.snapshot()

        assertThat(snap.halted).isFalse()
        assertThat(snap.haltReason).isNull()
        assertThat(snap.haltedAtMs).isEqualTo(clock.now())
        assertThat(snap.strategyHalts.map { it.strategyId }).containsExactly("b")
    }

    @Test
    fun `a new equity peak flushes once to the sink and is held without one`() {
        val writes = mutableListOf<PersistedRiskState>()
        val s = state { writes += it }
        s.onTick()

        s.persistAnchorsIfDirty()
        s.persistAnchorsIfDirty()
        assertThat(writes).hasSize(1)
        assertThat(writes.single().peakTotalEquity).isEqualByComparingTo("7")

        val sinkless = state()
        sinkless.onTick()
        sinkless.persistAnchorsIfDirty()
        assertThat(sinkless.snapshot().peakTotalEquity).isEqualByComparingTo("7")
    }

    @Test
    fun `initializing anchors on a new peak writes the restart record`() {
        val writes = mutableListOf<PersistedRiskState>()
        val s = state { writes += it }

        s.initializeAnchors(listOf("a"))

        assertThat(writes).hasSize(1)
        assertThat(writes.single().peakTotalEquity).isEqualByComparingTo("7")
    }
}
