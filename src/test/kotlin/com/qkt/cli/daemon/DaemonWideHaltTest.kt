package com.qkt.cli.daemon

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.risk.rules.DaemonWideHalt
import java.math.BigDecimal
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `risk.max_daily_loss` promises a global breach halts the whole daemon, but each deployed strategy
 * runs its own session and risk state. The bot2 soak's `soak-halt`, limit 8, on 2026-10-30: its strategies
 * had lost 8.05 together at 07:00:13, one of them crossed 8 alone at 07:04:17, and the other two kept
 * entering, 105 entries by the end of the day.
 */
class DaemonWideHaltTest {
    private val clock = FixedClock(DAY_MS * 20_756 + 1_000)

    private fun factory(maxDailyLoss: String = "8") =
        StrategyHandle.RealFactory(
            stateDir = StateDir.resolve(Files.createTempDirectory("daemon-wide-halt").toString()),
            marketSourceProvider = { error("no market data in this test") },
            maxDailyLoss = BigDecimal(maxDailyLoss),
            daemonRisk = DaemonRiskScope(clock),
        )

    private inner class Session(
        factory: StrategyHandle.RealFactory,
        val id: String,
    ) {
        val state: RiskState =
            StrategyPositionTracker().let { ledger ->
                val prices = MarketPriceTracker()
                RiskState(
                    PnLCalculator(ledger.account, prices),
                    StrategyPnL(ledger, prices),
                    clock,
                    EventBus(clock, MonotonicSequenceGenerator()),
                ).also { it.warmupComplete = true }
            }
        private val engine =
            RiskEngine(emptyList(), factory.sessionHaltRules(id), StrategyPositionTracker().account, state)

        fun lose(amount: String) {
            state.onFill(id, BigDecimal(amount).negate())
            engine.evaluateHaltRules()
        }

        fun tick() = engine.evaluateHaltRules()
    }

    @Test
    fun `the strategies' losses count together against the daemon's daily limit`() {
        val f = factory()
        val a = Session(f, "a")
        val b = Session(f, "b")
        a.lose("5")
        b.lose("3.05")

        assertThat(b.state.halted).isTrue()
        assertThat(b.state.globalHaltScope()).isEqualTo(HaltScope.DAILY)
        a.tick()
        assertThat(a.state.halted).isTrue()
    }

    @Test
    fun `one strategy crossing the limit alone halts a strategy that lost nothing`() {
        val f = factory()
        val a = Session(f, "a")
        val quiet = Session(f, "quiet")
        quiet.tick()
        a.lose("8.83")
        quiet.tick()

        assertThat(a.state.halted).isTrue()
        assertThat(quiet.state.halted).isTrue()
        assertThat(quiet.state.haltReason).contains("daily loss 8.83")
    }

    @Test
    fun `a loss at the limit exactly halts nothing and a zero limit is off`() {
        val at = factory()
        val a = Session(at, "a")
        a.lose("8")
        assertThat(a.state.halted).isFalse()

        val off = factory("0")
        val b = Session(off, "b")
        b.lose("1000")
        assertThat(b.state.halted).isFalse()
    }

    @Test
    fun `the next UTC day starts the budget again for every strategy`() {
        val f = factory()
        val a = Session(f, "a")
        val b = Session(f, "b")
        a.lose("9")
        b.tick()
        assertThat(b.state.halted).isTrue()

        clock.time += DAY_MS
        a.tick()
        b.tick()

        assertThat(a.state.halted).isFalse()
        assertThat(b.state.halted).isFalse()
        b.lose("7")
        a.tick()
        assertThat(a.state.halted).isFalse()
    }

    @Test
    fun `a strategy that alone is under the limit after a restart still counts what it restored`() {
        val f = factory()
        val a = Session(f, "a")
        val b = Session(f, "b")
        a.state.restore(a.state.snapshot().copy(realizedToday = BigDecimal("-5"), epochDay = clock.time / DAY_MS))
        b.lose("3.5")
        a.tick()
        b.tick()

        assertThat(a.state.halted).isTrue()
        assertThat(b.state.halted).isTrue()
    }

    @Test
    fun `a halt of one strategy for another reason leaves the others trading`() {
        val f = factory()
        val a = Session(f, "a")
        val b = Session(f, "b")
        a.state.halt("engine fault: tick")
        a.tick()
        b.tick()

        assertThat(b.state.halted).isFalse()
    }

    @Test
    fun `a drawdown breach in one session halts the others until the breach is resolved`() {
        var breached = true
        val scope = DaemonRiskScope(clock)
        val rule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState) =
                    if (breached) HaltDecision.Halt("drawdown", scope = HaltScope.PERSISTENT) else HaltDecision.Continue
            }
        val off =
            object : HaltRule {
                override fun evaluate(riskState: RiskState) = HaltDecision.Continue
            }
        val breacher = DaemonWideHalt("a", rule, scope)
        val peer = DaemonWideHalt("b", off, scope)
        val state = RiskState.noOp(clock)

        assertThat(breacher.evaluate(state)).isInstanceOf(HaltDecision.Halt::class.java)
        assertThat((peer.evaluate(state) as HaltDecision.Halt).reason).isEqualTo("drawdown")

        breached = false
        breacher.evaluate(state)
        assertThat(peer.evaluate(state)).isEqualTo(HaltDecision.Continue)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
