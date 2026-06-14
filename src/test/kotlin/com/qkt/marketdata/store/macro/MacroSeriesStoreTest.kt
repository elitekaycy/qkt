package com.qkt.marketdata.store.macro

import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MacroSeriesStoreTest {
    @Test
    fun `writes and reads points across a year boundary in date order`(
        @TempDir tmp: Path,
    ) {
        val store = MacroSeriesStore(tmp)
        store.write(
            "DGS10",
            listOf(
                MacroPoint(LocalDate.of(2024, 1, 2), BigDecimal("3.95")),
                MacroPoint(LocalDate.of(2023, 12, 29), BigDecimal("3.88")),
            ),
        )
        val read = store.read("DGS10", LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2))
        assertThat(read.map { it.date }).containsExactly(
            LocalDate.of(2023, 12, 29),
            LocalDate.of(2024, 1, 2),
        )
        assertThat(read[0].value).isEqualByComparingTo("3.88")
        assertThat(read[1].value).isEqualByComparingTo("3.95")
    }

    @Test
    fun `a re-write of the same date replaces the value (idempotent merge)`(
        @TempDir tmp: Path,
    ) {
        val store = MacroSeriesStore(tmp)
        val d = LocalDate.of(2024, 3, 1)
        store.write("DFII10", listOf(MacroPoint(d, BigDecimal("1.80"))))
        store.write("DFII10", listOf(MacroPoint(d, BigDecimal("1.85"))))
        val read = store.read("DFII10", d, d)
        assertThat(read).hasSize(1)
        assertThat(read[0].value).isEqualByComparingTo("1.85")
    }

    @Test
    fun `hasRange brackets the window and is false past the last written date`(
        @TempDir tmp: Path,
    ) {
        val store = MacroSeriesStore(tmp)
        store.write(
            "DGS2",
            listOf(
                MacroPoint(LocalDate.of(2023, 12, 29), BigDecimal("4.25")),
                MacroPoint(LocalDate.of(2024, 1, 2), BigDecimal("4.33")),
            ),
        )
        assertThat(store.hasRange("DGS2", LocalDate.of(2023, 12, 29), LocalDate.of(2024, 1, 2))).isTrue()
        assertThat(store.hasRange("DGS2", LocalDate.of(2023, 12, 29), LocalDate.of(2024, 6, 1))).isFalse()
        assertThat(store.hasRange("MISSING", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))).isFalse()
    }
}
