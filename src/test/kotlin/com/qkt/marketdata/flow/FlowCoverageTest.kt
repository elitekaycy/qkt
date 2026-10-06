package com.qkt.marketdata.flow

import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A run is served only when every day from its earliest read to its end is stored for each read series. */
class FlowCoverageTest {
    @TempDir lateinit var root: Path
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val oct4 = 1_791_072_000_000L
    private val day = 86_400_000L

    @Test
    fun `missing days are refused naming the fetch from the earliest day a read reaches`() {
        val store = TapeStore(root)
        store.write(perp, FlowKind.TRADES, LocalDate.parse("2026-10-04"), emptyList())
        val reads = listOf(FlowCoverage.Read(perp, FlowKind.TRADES, oct4 - 60_000))

        val problem = FlowCoverage.problem(store, reads, oct4 + day)

        assertThat(problem)
            .contains("1 day(s)")
            .contains("from 2026-10-03")
            .contains("qkt fetch $perp --tape --from 2026-10-03 --to 2026-10-04")
    }

    @Test
    fun `stored days serve the run, and each series is checked on its own`() {
        val store = TapeStore(root)
        store.write(perp, FlowKind.TRADES, LocalDate.parse("2026-10-04"), emptyList())

        assertThat(
            FlowCoverage.problem(store, listOf(FlowCoverage.Read(perp, FlowKind.TRADES, oct4)), oct4 + day),
        ).isNull()
        assertThat(
            FlowCoverage.problem(store, listOf(FlowCoverage.Read(perp, FlowKind.LIQUIDATIONS, oct4)), oct4 + day),
        ).contains("--liquidations")
    }

    @Test
    fun `a continuous stream has no tape of its own`() {
        val problem =
            FlowCoverage.problem(
                TapeStore(root),
                listOf(FlowCoverage.Read("CME:ES@front", FlowKind.TRADES, oct4)),
                oct4 + day,
            )

        assertThat(problem).contains("continuous futures stream")
    }
}
