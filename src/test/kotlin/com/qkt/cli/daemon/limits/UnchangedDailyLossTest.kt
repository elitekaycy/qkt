package com.qkt.cli.daemon.limits

import com.qkt.common.FixedClock
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What #1565 must leave as it was, and a book's own limits in a mixed daemon. */
class UnchangedDailyLossTest {
    private val clock = FixedClock(DAY_MS * 20_758 + 3_600_000L)

    @Test
    fun `one portfolio alone still flattens and halts on max_daily_loss`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val book = rig.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false))
            val (a, b) = book.children
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(b, 1)
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, 1)
            rig.bar("BACKTEST:A", "90")
            rig.awaitTrades(a, 2)
            rig.bar("BACKTEST:B", "101")

            await("the book flattened") { rig.fills(b) == 2 }
            assertThat(b.live.isHalted()).isTrue()
            assertThat(tmp.resolve("state-root/__daemon__")).doesNotExist()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `standalones alone still only block entries, and keep no daemon record`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val loser = rig.standalone("loser", "BACKTEST:S")
            val holder = rig.standalone("holder", "BACKTEST:T", loser = false)
            rig.bar("BACKTEST:T", "100")
            rig.awaitTrades(holder, 1)
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(loser, 1)
            rig.bar("BACKTEST:S", "90")
            rig.awaitTrades(loser, 2)
            rig.bar("BACKTEST:T", "101")

            await("the other standalone halted") { holder.live.isHalted() }
            assertThat(rig.legs(holder)).isEqualTo(1)
            assertThat(tmp.resolve("state-root/__daemon__")).doesNotExist()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `two portfolios with no standalone keep a budget each`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val p = rig.book("p", Triple("a", "BACKTEST:A", true)).children.single()
            val q = rig.book("q", Triple("a", "BACKTEST:B", true)).children.single()
            for ((child, symbol) in listOf(p to "BACKTEST:A", q to "BACKTEST:B")) {
                rig.bar(symbol, "100")
                rig.awaitTrades(child, 1)
                rig.bar(symbol, "95")
                rig.awaitTrades(child, 2)
            }
            rig.bar("BACKTEST:A", "98")
            rig.bar("BACKTEST:B", "98")

            assertThat(p.live.isHalted()).isFalse()
            assertThat(q.live.isHalted()).isFalse()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a book's own drawdown in a mixed daemon flattens that book and nothing else`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "0", maxDrawdownPct = BigDecimal("0.00005"))
        try {
            val solo = rig.standalone("solo", "BACKTEST:S", loser = false)
            val b = rig.book("bk", Triple("b", "BACKTEST:B", false)).children.single()
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(solo, 1)
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(b, 1)
            rig.bar("BACKTEST:B", "90")

            await("the book flattened by its own drawdown") { rig.fills(b) == 2 }
            assertThat(b.live.isHalted()).isTrue()
            assertThat(solo.live.isHalted()).isFalse()
            assertThat(rig.legs(solo)).isEqualTo(1)
        } finally {
            rig.close()
        }
    }
}
