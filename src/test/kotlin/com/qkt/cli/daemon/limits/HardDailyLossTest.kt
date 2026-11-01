package com.qkt.cli.daemon.limits

import com.qkt.cli.daemon.StrategyHandle
import com.qkt.common.FixedClock
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `risk.hard_daily_loss` across a daemon's sessions (#1565), on real sessions and the paper venue. */
class HardDailyLossTest {
    private val clock = FixedClock(DAY_MS * 20_758 + 3_600_000L)

    private fun SharedLimitsRig.bothHoldingAtALoss(): Pair<StrategyHandle, StrategyHandle> {
        val solo = standalone("solo", "BACKTEST:S", loser = false)
        val b = book("bk", Triple("b", "BACKTEST:B", false)).children.single()
        bar("BACKTEST:S", "100")
        awaitTrades(solo, 1)
        bar("BACKTEST:B", "100")
        awaitTrades(b, 1)
        bar("BACKTEST:S", "80")
        bar("BACKTEST:B", "80")
        mark("BACKTEST:S", "80")
        mark("BACKTEST:B", "80")
        return solo to b
    }

    private fun SharedLimitsRig.checkTwice() {
        guard.checkOnce()
        guard.checkOnce()
    }

    @Test
    fun `crossing it on open loss flattens everything once and halts`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val (solo, b) = rig.bothHoldingAtALoss()
            assertThat(solo.live.isHalted()).isFalse()
            rig.checkTwice()

            rig.awaitTrades(solo, 2)
            rig.awaitTrades(b, 2)
            assertThat(solo.live.haltReason()).contains("hard_daily_loss")
            assertThat(b.live.haltReason()).contains("hard_daily_loss")
            assertThat(rig.scope.hardHaltReason()).isNotNull()

            repeat(5) { rig.guard.checkOnce() }
            rig.settle()
            assertThat(rig.fills(solo)).describedAs("no second flatten").isEqualTo(2)
            assertThat(rig.fills(b)).isEqualTo(2)

            rig.bar("BACKTEST:S", "100")
            rig.bar("BACKTEST:B", "100")
            assertThat(rig.fills(solo)).describedAs("no re-entry").isEqualTo(2)
            assertThat(rig.fills(b)).isEqualTo(2)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `one read over the limit from open loss alone does not flatten`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val (solo, b) = rig.bothHoldingAtALoss()
            rig.guard.checkOnce()
            rig.settle()
            assertThat(rig.fills(solo)).isEqualTo(1)
            assertThat(rig.fills(b)).isEqualTo(1)
            rig.mark("BACKTEST:S", "99")
            rig.mark("BACKTEST:B", "99")
            rig.guard.checkOnce()
            rig.settle()
            assertThat(rig.fills(solo)).isEqualTo(1)
            assertThat(rig.scope.hardHaltReason()).isNull()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `it survives a restart within the day without another flatten or an entry`(
        @TempDir tmp: Path,
    ) {
        val first = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val (solo, b) = first.bothHoldingAtALoss()
            first.checkTwice()
            first.awaitTrades(solo, 2)
            first.awaitTrades(b, 2)
        } finally {
            first.close()
        }

        val second = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            assertThat(second.scope.hardHaltReason()).contains("hard_daily_loss")
            val solo = second.standalone("solo", "BACKTEST:S", loser = false)
            val b = second.book("bk", Triple("b", "BACKTEST:B", false)).children.single()
            second.checkTwice()
            second.bar("BACKTEST:S", "100")
            second.bar("BACKTEST:B", "100")
            assertThat(solo.live.isHalted()).isTrue()
            assertThat(b.live.isHalted()).isTrue()
            assertThat(second.fills(solo)).isEqualTo(0)
            assertThat(second.fills(b)).isEqualTo(0)

            clock.time += DAY_MS
            second.bar("BACKTEST:S", "98")
            second.guard.checkOnce()
            second.bar("BACKTEST:S", "100")
            second.awaitTrades(solo, 1)
            assertThat(second.scope.hardHaltReason()).isNull()
        } finally {
            second.close()
        }
    }

    @Test
    fun `a resume while it stands is halted again, without a new flatten or an entry`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val (solo, b) = rig.bothHoldingAtALoss()
            rig.checkTwice()
            rig.awaitTrades(solo, 2)
            rig.awaitTrades(b, 2)

            solo.live.resume()
            rig.bar("BACKTEST:S", "100")
            assertThat(rig.fills(solo)).describedAs("the resume's first bar").isEqualTo(2)
            assertThat(solo.live.isHalted()).isTrue()
            rig.guard.checkOnce()
            rig.settle()
            assertThat(rig.fills(solo)).isEqualTo(2)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an open loss with no fresh price does not flatten until a fresh price confirms it`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val (solo, b) = rig.bothHoldingAtALoss()
            clock.time += 3_600_000L
            rig.checkTwice()
            rig.settle()
            assertThat(solo.live.limitReadingOrFail().unmeasured).containsKey("BACKTEST:S")
            assertThat(rig.fills(solo)).isEqualTo(1)
            assertThat(rig.fills(b)).isEqualTo(1)
            assertThat(rig.scope.hardHaltReason()).isNull()

            rig.mark("BACKTEST:S", "60")
            rig.mark("BACKTEST:B", "80")
            rig.checkTwice()
            rig.awaitTrades(solo, 2)
            rig.awaitTrades(b, 2)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `realized loss alone past it trips on the first read and an unset limit does nothing`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "1000", hardDailyLoss = "30")
        try {
            val loser = rig.standalone("loser", "BACKTEST:S")
            val holder = rig.standalone("holder", "BACKTEST:T", loser = false)
            rig.bar("BACKTEST:T", "100")
            rig.awaitTrades(holder, 1)
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(loser, 1)
            rig.bar("BACKTEST:S", "60")
            rig.awaitTrades(loser, 2)
            rig.guard.checkOnce()
            rig.awaitTrades(holder, 2)
            assertThat(holder.live.isHalted()).isTrue()
        } finally {
            rig.close()
        }

        val off = SharedLimitsRig(tmp.resolve("off"), clock, maxDailyLoss = "1000")
        try {
            val (solo, _) = off.bothHoldingAtALoss()
            off.checkTwice()
            off.settle()
            assertThat(off.fills(solo)).isEqualTo(1)
            assertThat(solo.live.isHalted()).isFalse()
        } finally {
            off.close()
        }
    }
}
