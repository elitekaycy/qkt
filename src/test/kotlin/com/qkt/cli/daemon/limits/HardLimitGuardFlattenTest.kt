package com.qkt.cli.daemon.limits

import com.qkt.app.DailyLimitReading
import com.qkt.app.LiveSessionHandle
import com.qkt.app.OrderManager
import com.qkt.cli.daemon.DaemonHardLimitGuard
import com.qkt.cli.daemon.GuardedSession
import com.qkt.common.FixedClock
import com.qkt.execution.Trade
import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltScope
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How often the hard limit's guard flattens a session that will not go flat, and what it counts. */
class HardLimitGuardFlattenTest {
    private class Stuck : LiveSessionHandle {
        var reading = DailyLimitReading("stuck", BigDecimal("-50"), BigDecimal.ZERO, emptyMap(), 1, false)
        var flattens = 0
        var answers = true

        override val running = true
        override val droppedTicks = 0L

        override fun stop() {}

        override fun awaitTermination(timeout: Duration) = true

        override fun recentTrades() = emptyList<Trade>()

        override fun pendingStackLayerInfos() = emptyList<OrderManager.PendingStackLayerInfo>()

        override fun halt(
            reason: String,
            scope: HaltScope,
        ) {}

        override fun flatten() {
            flattens++
        }

        override fun limitReading(): DailyLimitReading {
            check(answers) { "engine did not answer" }
            return reading
        }
    }

    private val clock = FixedClock(DAY_MS * 20_758)
    private val scope = DaemonRiskScope(clock, hardDailyLoss = BigDecimal("30"))
    private val stuck = Stuck()
    private val guard = DaemonHardLimitGuard(scope, { listOf(GuardedSession("stuck", stuck)) })

    @Test
    fun `a position that stays open is flattened three times, far apart, then left to the operator`() {
        guard.checkOnce()
        assertThat(stuck.flattens).isEqualTo(1)
        repeat(10) { guard.checkOnce() }
        assertThat(stuck.flattens).describedAs("not before the wait").isEqualTo(1)
        repeat(10) {
            clock.time += 31_000L
            guard.checkOnce()
        }
        assertThat(stuck.flattens).isEqualTo(3)
    }

    @Test
    fun `a close still working is not doubled`() {
        guard.checkOnce()
        stuck.reading = stuck.reading.copy(closing = true)
        repeat(5) {
            clock.time += 31_000L
            guard.checkOnce()
        }
        assertThat(stuck.flattens).isEqualTo(1)
    }

    @Test
    fun `a flat session is not flattened again and the next day starts clean`() {
        guard.checkOnce()
        stuck.reading = stuck.reading.copy(openLegs = 0, realizedToday = BigDecimal.ZERO)
        clock.time += 60_000L
        guard.checkOnce()
        assertThat(stuck.flattens).isEqualTo(1)

        clock.time += DAY_MS
        guard.checkOnce()
        assertThat(scope.hardHaltReason()).isNull()
        assertThat(stuck.flattens).isEqualTo(1)
    }

    @Test
    fun `a session that does not answer counts as nothing and does not stop the check`() {
        stuck.answers = false
        guard.checkOnce()
        assertThat(scope.hardHaltReason()).isNull()
        assertThat(stuck.flattens).isEqualTo(0)
    }

    @Test
    fun `a limit reached exactly does not trip`() {
        stuck.reading = stuck.reading.copy(realizedToday = BigDecimal("-30"))
        guard.checkOnce()
        guard.checkOnce()
        assertThat(scope.hardHaltReason()).isNull()
    }
}
