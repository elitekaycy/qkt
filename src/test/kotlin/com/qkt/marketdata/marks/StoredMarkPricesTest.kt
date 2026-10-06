package com.qkt.marketdata.marks

import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A backtest sees a stored mark only once it was known: strictly before the instant it reads at. */
class StoredMarkPricesTest {
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val day = LocalDate.parse("2026-10-04")
    private val midnight = 1_791_072_000_000L // 2026-10-04T00:00Z

    private fun sample(
        at: Long,
        mark: String,
    ) = MarkSample(at, BigDecimal(mark), BigDecimal("100"))

    private fun prices(root: Path): StoredMarkPrices {
        val store = MarkStore(root)
        store.write(perp, minute, day.minusDays(1), listOf(sample(midnight - 30_000, "99")))
        store.write(perp, minute, day, listOf(sample(midnight + 10_000, "101"), sample(midnight + 70_000, "102")))
        store.write(perp, hour, day, listOf(sample(midnight + 50_000, "150")))
        return StoredMarkPrices(store)
    }

    @Test
    fun `a sample is invisible at its own instant and visible just after it`(
        @TempDir root: Path,
    ) {
        val marks = prices(root)

        assertThat(marks.at(perp, minute, midnight + 10_000)!!.mark).isEqualByComparingTo("99")
        assertThat(marks.at(perp, minute, midnight + 10_001)!!.mark).isEqualByComparingTo("101")
    }

    @Test
    fun `at a bar's close the newest sample inside the bar is read`(
        @TempDir root: Path,
    ) {
        val marks = prices(root)

        assertThat(marks.at(perp, minute, midnight + 2 * minute)!!.timeMs).isEqualTo(midnight + 70_000)
        assertThat(marks.at(perp, minute, midnight + minute)!!.mark).isEqualByComparingTo("101")
    }

    @Test
    fun `a day with nothing yet reads the newest sample of the stored days before it`(
        @TempDir root: Path,
    ) {
        val marks = prices(root)

        assertThat(marks.at(perp, minute, midnight + 5_000)!!.mark).isEqualByComparingTo("99")
    }

    @Test
    fun `each window reads its own series, and a symbol never stored reads nothing`(
        @TempDir root: Path,
    ) {
        val marks = prices(root)

        assertThat(marks.at(perp, hour, midnight + hour)!!.mark).isEqualByComparingTo("150")
        assertThat(marks.at("DERIBIT:ETH_USDC_PERPETUAL", minute, midnight + hour)).isNull()
        assertThat(marks.at(perp, minute, midnight - 2 * 86_400_000L)).isNull()
    }
}
