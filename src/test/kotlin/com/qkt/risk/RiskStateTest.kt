package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.RiskEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskStateTest {
    private fun newRiskState(clock: FixedClock = FixedClock(0L)): Pair<RiskState, MutableList<RiskEvent>> {
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val state = RiskState(pnl, strategyPnL, clock, bus)

        val events = mutableListOf<RiskEvent>()
        bus.subscribe<RiskEvent.Halted> { events.add(it) }
        bus.subscribe<RiskEvent.Resumed> { events.add(it) }

        return state to events
    }

    @Test
    fun `halt sets halted flag and publishes Halted event`() {
        val (state, events) = newRiskState()

        state.halt("test reason")

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("test reason")
        assertThat(events).hasSize(1)
        assertThat((events[0] as RiskEvent.Halted).strategyId).isNull()
    }

    @Test
    fun `halt is idempotent`() {
        val (state, events) = newRiskState()

        state.halt("first")
        state.halt("second")

        assertThat(events).hasSize(1)
        assertThat(state.haltReason).isEqualTo("first")
    }

    @Test
    fun `resume clears halted flag and publishes Resumed event`() {
        val (state, events) = newRiskState()

        state.halt("test")
        events.clear()
        state.resume()

        assertThat(state.halted).isFalse
        assertThat(state.haltReason).isNull()
        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(RiskEvent.Resumed::class.java)
    }

    @Test
    fun `haltStrategy halts only that strategy`() {
        val (state, events) = newRiskState()

        state.haltStrategy("A", "test")

        assertThat(state.halted).isFalse
        assertThat(state.isStrategyHalted("A")).isTrue
        assertThat(state.isStrategyHalted("B")).isFalse
        assertThat((events[0] as RiskEvent.Halted).strategyId).isEqualTo("A")
    }

    @Test
    fun `global halt makes isStrategyHalted true for all strategies`() {
        val (state, _) = newRiskState()

        state.halt("global")

        assertThat(state.isStrategyHalted("A")).isTrue
        assertThat(state.isStrategyHalted("Z")).isTrue
    }

    @Test
    fun `resumeStrategy is idempotent for non-halted strategy`() {
        val (state, events) = newRiskState()

        state.resumeStrategy("A")

        assertThat(events).isEmpty()
    }

    @Test
    fun `clearExpiredDailyHalts auto-resumes a daily halt on the next UTC day`() {
        val clock = FixedClock(0L)
        val (state, _) = newRiskState(clock)

        state.halt("daily loss", HaltScope.DAILY)
        state.clearExpiredDailyHalts() // same UTC day — stays halted
        assertThat(state.halted).isTrue

        clock.time = DAY_MS // next UTC day
        state.clearExpiredDailyHalts()
        assertThat(state.halted).isFalse
    }

    @Test
    fun `clearExpiredDailyHalts leaves a persistent halt latched across a UTC day`() {
        val clock = FixedClock(0L)
        val (state, _) = newRiskState(clock)

        state.halt("total drawdown", HaltScope.PERSISTENT)

        clock.time = DAY_MS
        state.clearExpiredDailyHalts()
        assertThat(state.halted).isTrue
    }

    @Test
    fun `clearExpiredDailyHalts auto-resumes daily strategy halts but not persistent ones`() {
        val clock = FixedClock(0L)
        val (state, _) = newRiskState(clock)

        state.haltStrategy("A", "daily loss", HaltScope.DAILY)
        state.haltStrategy("B", "total drawdown", HaltScope.PERSISTENT)

        clock.time = DAY_MS
        state.clearExpiredDailyHalts()

        assertThat(state.isStrategyHalted("A")).isFalse
        assertThat(state.isStrategyHalted("B")).isTrue
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
