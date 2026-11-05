package com.qkt.cli.daemon.limits

import com.qkt.common.FixedClock
import com.qkt.risk.HaltScope
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `risk.max_daily_loss` in a daemon mixing standalones and a book (#1565), on real sessions. */
class MixedDailyLossTest {
    private val clock = FixedClock(DAY_MS * 20_758 + 3_600_000L)

    @Test
    fun `the total across a standalone and a book blocks entries everywhere and flattens nothing`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val solo = rig.standalone("solo", "BACKTEST:S")
            val book = rig.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false))
            val (a, b) = book.children
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(b, 1)
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(solo, 1)
            rig.bar("BACKTEST:S", "95")
            rig.awaitTrades(solo, 2)
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, 1)
            rig.bar("BACKTEST:A", "96")
            rig.awaitTrades(a, 2)
            // -5 on the standalone and -4 on the book: 9 together, under 8 on neither side alone.
            rig.bar("BACKTEST:B", "101")
            rig.bar("BACKTEST:S", "98")

            await("every session halted") { listOf(solo, a, b).all { it.live.isHalted() } }
            assertThat(b.live.haltScope()).isEqualTo(HaltScope.DAILY)
            assertThat(b.live.haltReason()).contains("daily loss")
            assertThat(rig.legs(b)).describedAs("the book keeps its open position").isEqualTo(1)

            val soloFills = rig.fills(solo)
            rig.bar("BACKTEST:S", "100")
            assertThat(rig.fills(solo)).describedAs("no new standalone entry").isEqualTo(soloFills)
            val aFills = rig.fills(a)
            rig.bar("BACKTEST:A", "100")
            assertThat(rig.fills(a)).describedAs("no new book entry").isEqualTo(aFills)
            rig.bar("BACKTEST:B", "150")
            rig.awaitTrades(b, 2)
            assertThat(rig.legs(b)).describedAs("the exit still passes").isEqualTo(0)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a book that alone crosses the total in a mixed daemon is blocked and not flattened`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            rig.standalone("solo", "BACKTEST:S")
            val book = rig.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false))
            val (a, b) = book.children
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(b, 1)
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, 1)
            rig.bar("BACKTEST:A", "90")
            rig.awaitTrades(a, 2)
            rig.bar("BACKTEST:B", "101")

            await("the book's other child blocked") { b.live.isHalted() }
            rig.settle()
            assertThat(rig.legs(b)).isEqualTo(1)
            assertThat(rig.fills(b)).isEqualTo(1)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a resume while the total stands halts again and a day later everything trades`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val solo = rig.standalone("solo", "BACKTEST:S")
            val book = rig.book("bk", Triple("a", "BACKTEST:A", true))
            val a = book.children.single()
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, 1)
            rig.bar("BACKTEST:A", "90")
            rig.awaitTrades(a, 2)
            rig.bar("BACKTEST:S", "98")
            await("standalone sees the book's loss") { solo.live.isHalted() }

            solo.live.resume()
            a.live.resume()
            val aFills = rig.fills(a)
            rig.bar("BACKTEST:A", "100")
            assertThat(rig.fills(a)).describedAs("entry on the resume's first bar").isEqualTo(aFills)
            assertThat(a.live.isHalted()).isTrue()
            rig.bar("BACKTEST:S", "98")
            assertThat(solo.live.isHalted()).isTrue()

            clock.time += DAY_MS
            rig.bar("BACKTEST:A", "98")
            rig.bar("BACKTEST:S", "98")
            assertThat(a.live.isHalted()).isFalse()
            assertThat(solo.live.isHalted()).isFalse()
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, aFills + 1)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a restart with the book deploying first neither flattens it nor forgets the total`(
        @TempDir tmp: Path,
    ) {
        val first = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            first.standalone("solo", "BACKTEST:S")
            val a = first.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false)).children[0]
            first.bar("BACKTEST:A", "100")
            first.awaitTrades(a, 1)
            first.bar("BACKTEST:A", "90")
            first.awaitTrades(a, 2)
            await("book child halted") { a.live.isHalted() }
        } finally {
            first.close()
        }

        val second = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val book = second.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false))
            Thread.sleep(100L)
            assertThat(second.scope.composition.mixed()).isTrue()
            assertThat(book.children[1].live.isHalted()).describedAs("no book flatten and halt").isFalse()
            val solo = second.standalone("solo", "BACKTEST:S")
            second.bar("BACKTEST:S", "98")
            await("the restarted standalone reads the restored total") { solo.live.isHalted() }
        } finally {
            second.close()
        }
    }

    @Test
    fun `undeploying the last standalone leaves the day mixed and its loss counted`(
        @TempDir tmp: Path,
    ) {
        val rig = SharedLimitsRig(tmp, clock, maxDailyLoss = "8")
        try {
            val solo = rig.standalone("solo", "BACKTEST:S")
            val book = rig.book("bk", Triple("a", "BACKTEST:A", true), Triple("b", "BACKTEST:B", false))
            val (a, b) = book.children
            rig.bar("BACKTEST:B", "100")
            rig.awaitTrades(b, 1)
            rig.bar("BACKTEST:S", "100")
            rig.awaitTrades(solo, 1)
            rig.bar("BACKTEST:S", "94")
            rig.awaitTrades(solo, 2)
            rig.registry.stop("solo")
            rig.bar("BACKTEST:A", "100")
            rig.awaitTrades(a, 1)
            rig.bar("BACKTEST:A", "96")
            rig.awaitTrades(a, 2)
            rig.bar("BACKTEST:B", "101")

            await("the standalone's -6 and the book's -4 still count") { b.live.isHalted() }
            assertThat(rig.legs(b)).isEqualTo(1)
        } finally {
            rig.close()
        }
    }
}
