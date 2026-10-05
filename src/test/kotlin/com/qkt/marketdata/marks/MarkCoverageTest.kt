package com.qkt.marketdata.marks

import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A backtest reading marks needs every UTC day of its run stored at the stream's window. */
class MarkCoverageTest {
    private val minute = 60_000L
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val from = 1_790_985_600_000L // 2026-10-03T00:00Z
    private val to = from + 2 * 86_400_000L

    @Test
    fun `stored days covering the run serve it`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)
        listOf("2026-10-03", "2026-10-04").forEach { store.write(perp, minute, LocalDate.parse(it), emptyList()) }

        assertThat(MarkCoverage.problem(store, listOf(perp to minute), from, to)).isNull()
    }

    @Test
    fun `a missing day is refused naming the fetch at the stream's window over the run`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)
        store.write(perp, minute, LocalDate.parse("2026-10-03"), emptyList())
        store.write(perp, 3_600_000L, LocalDate.parse("2026-10-04"), emptyList())

        val problem = MarkCoverage.problem(store, listOf(perp to minute), from, to)

        assertThat(problem)
            .contains("1 day(s) of the run are not stored (from 2026-10-04)")
            .contains("`qkt fetch $perp --marks --tf 1m --from 2026-10-03 --to 2026-10-04`")
    }

    @Test
    fun `a continuous futures stream has no marks of its own`(
        @TempDir root: Path,
    ) {
        val problem = MarkCoverage.problem(MarkStore(root), listOf("BINANCE_UM:BTCUSDT@front" to minute), from, to)

        assertThat(problem).contains("continuous futures stream")
    }
}
