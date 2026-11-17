package com.qkt.marketdata.marks

import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoredMarkPricesLookbackTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val minute = 60_000L
    private val midnight = 1_791_072_000_000L // 2026-10-04T00:00Z

    @Test
    fun `eight stored days that hold nothing before the instant give no mark`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)
        val first = LocalDate.parse("2026-10-03")
        for (back in 0L until 8L) {
            store.write(perp, minute, first.minusDays(back), listOf(MarkSample(midnight + 1, BigDecimal.ONE, null)))
        }

        assertThat(StoredMarkPrices(store).at(perp, minute, midnight)).isNull()
    }
}
