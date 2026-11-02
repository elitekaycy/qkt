package com.qkt.cli.daemon.limits

import com.qkt.app.DailyLimitReading
import com.qkt.cli.daemon.DaemonHardLimitGuard
import com.qkt.cli.daemon.GuardedSession
import com.qkt.common.FixedClock
import com.qkt.risk.DaemonRiskScope
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A breach resting on open P&L must hold for two reads in a row. Realized is -5 throughout, under the limit of 30,
 * so only the open P&L decides: a dip that lapses between reads, however often, must never flatten.
 */
class HardLimitTwoReadTest {
    private val clock = FixedClock(DAY_MS * 20_758)
    private val scope = DaemonRiskScope(clock, hardDailyLoss = BigDecimal("30"))
    private val session = ScriptedSession()
    private val guard = DaemonHardLimitGuard(scope, { listOf(GuardedSession("s", session)) })

    private fun open(value: String) {
        session.reading = DailyLimitReading("s", BigDecimal("-5"), BigDecimal(value), emptyMap(), 1, false)
    }

    private fun readAt(value: String) {
        open(value)
        guard.checkOnce()
    }

    private fun assertUntouched() {
        assertThat(scope.hardHaltReason()).isNull()
        assertThat(session.halts).isEqualTo(0)
        assertThat(session.flattens).isEqualTo(0)
    }

    @Test
    fun `a breach that lapses between reads never trips, however many times it returns`() {
        repeat(5) {
            readAt("-40")
            assertUntouched()
            readAt("-10")
            assertUntouched()
        }
    }

    @Test
    fun `the first read over touches nothing and the second in a row halts and flattens once`() {
        readAt("-40")
        assertUntouched()
        readAt("-26")
        assertThat(scope.hardHaltReason()).contains("hard_daily_loss").contains("-5").contains("-26")
        assertThat(session.halts).isEqualTo(1)
        assertThat(session.flattens).isEqualTo(1)
    }

    @Test
    fun `a read exactly at the limit breaks the run`() {
        readAt("-40")
        readAt("-25")
        readAt("-40")
        assertUntouched()
    }

    @Test
    fun `a session that does not answer breaks the run instead of counting as confirmed`() {
        readAt("-40")
        session.answers = false
        guard.checkOnce()
        session.answers = true
        readAt("-40")
        assertUntouched()
        readAt("-40")
        assertThat(session.flattens).isEqualTo(1)
    }

    @Test
    fun `an unmeasured symbol between two reads over the limit breaks the run`() {
        readAt("-40")
        session.reading = DailyLimitReading("s", BigDecimal("-5"), BigDecimal.ZERO, mapOf("A" to "stale"), 1, false)
        guard.checkOnce()
        readAt("-40")
        assertUntouched()
    }

    @Test
    fun `on its own thread the confirming read comes a poll after the first`() {
        open("-40")
        val polled = DaemonHardLimitGuard(scope, { listOf(GuardedSession("s", session)) }, pollMs = 300L)
        polled.use {
            it.start()
            val startedAt = System.nanoTime()
            await("the first read") { session.reads.get() >= 1 }
            assertThat(session.flattens).describedAs("nothing on the first read").isEqualTo(0)
            await("the flatten") { session.flattens == 1 }
            assertThat((System.nanoTime() - startedAt) / 1_000_000L).isGreaterThanOrEqualTo(250L)
            assertThat(session.reads.get()).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `real sessions keep their positions through a dip that lapses, then flatten on one that holds`(
        @TempDir tmp: Path,
    ) {
        val rigClock = FixedClock(DAY_MS * 20_758 + 3_600_000L)
        val rig = SharedLimitsRig(tmp, rigClock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val solo = rig.standalone("solo", "BACKTEST:S", loser = false)
            val child = rig.book("bk", Triple("b", "BACKTEST:B", false)).children.single()
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(solo, 1)
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(child, 1)

            fun readAt(price: String) {
                rig.mark("BACKTEST:S", price)
                rig.mark("BACKTEST:B", price)
                rig.guard.checkOnce()
            }
            repeat(3) {
                readAt("80")
                readAt("99")
            }
            readAt("80")
            rig.settle()
            assertThat(rig.scope.hardHaltReason()).isNull()
            assertThat(solo.live.isHalted()).isFalse()
            assertThat(child.live.isHalted()).isFalse()
            assertThat(rig.fills(solo)).isEqualTo(1)
            assertThat(rig.fills(child)).isEqualTo(1)
            assertThat(rig.legs(solo)).isEqualTo(1)
            assertThat(rig.legs(child)).isEqualTo(1)

            readAt("80")
            rig.awaitTrades(solo, 2)
            rig.awaitTrades(child, 2)
            assertThat(solo.live.haltReason()).contains("hard_daily_loss")
            assertThat(child.live.haltReason()).contains("hard_daily_loss")
        } finally {
            rig.close()
        }
    }
}
