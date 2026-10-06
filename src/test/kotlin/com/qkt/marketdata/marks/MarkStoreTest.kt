package com.qkt.marketdata.marks

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Marks are stored one UTC day per file, per symbol and sampling window, as exact text. */
class MarkStoreTest {
    private val minute = 60_000L
    private val day = LocalDate.parse("2026-10-04")
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"

    @Test
    fun `a day's samples are written under marks, venue, name and window, and read back exactly`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)
        val samples =
            listOf(
                MarkSample(1_791_154_919_979L, BigDecimal("86440.68"), BigDecimal("86415.59")),
                MarkSample(1_791_154_859_833L, BigDecimal("86482.30"), null),
            )

        store.write(perp, minute, day, samples)

        val file = root.resolve("marks/DERIBIT/BTC_USDC_PERPETUAL/1m/2026-10-04.csv")
        assertThat(Files.readAllLines(file))
            .containsExactly("time,mark,index", "1791154859833,86482.30,", "1791154919979,86440.68,86415.59")
        val read = store.read(perp, minute, day)!!
        assertThat(read.map { it.timeMs }).containsExactly(1_791_154_859_833L, 1_791_154_919_979L)
        assertThat(read[0].mark!!.toPlainString()).isEqualTo("86482.30")
        assertThat(read[0].index).isNull()
    }

    @Test
    fun `a fetched day without reports is stored, so it counts as fetched, and a day never fetched reads as null`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)

        store.write(perp, minute, day, emptyList())

        assertThat(store.has(perp, minute, day)).isTrue
        assertThat(store.read(perp, minute, day)).isEmpty()
        assertThat(store.read(perp, minute, day.plusDays(1))).isNull()
        assertThat(store.has(perp, 3_600_000L, day)).isFalse
    }

    @Test
    fun `a malformed line fails naming the file and line`(
        @TempDir root: Path,
    ) {
        val store = MarkStore(root)
        store.write(perp, minute, day, emptyList())
        Files.writeString(store.dir(perp, minute).resolve("$day.csv"), "time,mark,index\n1,2\n")

        assertThatThrownBy { store.read(perp, minute, day) }
            .hasMessageContaining("2026-10-04.csv line 2: expected time,mark,index")
    }
}
