package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Re-reading a day from what was read before always gives what a full read gives. */
class ChainSnapshotStoreIncrementalTest {
    private val root = "DERIBIT:BTC_USDC"
    private val day = LocalDate.parse("2026-10-01")
    private val midnight = 1_790_812_800_000L

    private fun snapshot(
        minute: Long,
        mark: String = "100",
    ): ChainSnapshot {
        val at = midnight + minute * 60_000
        val quotes =
            listOf("BTC_USDC-9OCT26-80000-P", "BTC_USDC-9OCT26-82000-P").map {
                ChainQuote(at, it, null, null, BigDecimal(mark), null, BigDecimal("83000"), null, 0L, QuoteSource.BOOK)
            }
        return ChainSnapshot(root, at, quotes)
    }

    @Test
    fun `appending, replacing the latest snapshot, or inserting an earlier one all read back as a full read`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        store.write(root, listOf(snapshot(0), snapshot(5)))
        var known = store.readDay(root, day)

        for (change in listOf(
            { store.append(root, snapshot(10)) },
            { store.append(root, snapshot(10, mark = "101")) },
            { store.append(root, snapshot(15)) },
            { store.append(root, snapshot(3)) },
            { store.write(root, listOf(snapshot(20))) },
        )) {
            change()
            val incremental = store.readDayAfter(root, day, known)
            assertThat(incremental).isEqualTo(store.readDay(root, day))
            known = incremental
        }
        assertThat(known.map { it.atMs }).containsExactly(midnight + 20 * 60_000)
    }

    @Test
    fun `nothing known reads the whole day, and a missing day is empty`(
        @TempDir dir: Path,
    ) {
        val store = ChainSnapshotStore(dir, QuoteSource.BOOK)
        assertThat(store.readDayAfter(root, day, listOf(snapshot(0)))).isEmpty()
        store.write(root, listOf(snapshot(0), snapshot(5)))

        assertThat(store.readDayAfter(root, day, emptyList())).isEqualTo(store.readDay(root, day))
    }
}
