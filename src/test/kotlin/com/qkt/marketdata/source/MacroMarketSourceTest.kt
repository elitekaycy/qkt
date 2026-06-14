package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MacroMarketSourceTest {
    private fun instant(
        date: LocalDate,
        hour: Int,
    ): Instant = date.atTime(hour, 0).toInstant(ZoneOffset.UTC)

    @Test
    fun `routes the MACRO prefix only`(
        @TempDir tmp: Path,
    ) {
        val source = MacroMarketSource(MacroSeriesStore(tmp))
        assertThat(source.supports("MACRO:DGS10")).isTrue()
        assertThat(source.supports("BACKTEST:XAUUSD")).isFalse()
    }

    @Test
    fun `ticks are release-stamped, range-filtered, and point-in-time`(
        @TempDir tmp: Path,
    ) {
        val store = MacroSeriesStore(tmp)
        store.write(
            "DGS10",
            listOf(
                MacroPoint(LocalDate.of(2024, 3, 1), BigDecimal("4.00")), // Fri -> released Mon 13:00
                MacroPoint(LocalDate.of(2024, 3, 4), BigDecimal("4.10")), // Mon -> released Tue 13:00
            ),
        )
        val source = MacroMarketSource(store)
        // Window Mon 00:00 .. Tue 12:00 — before Monday's value releases (Tue 13:00).
        val ticks =
            source
                .ticks(
                    "MACRO:DGS10",
                    TimeRange(instant(LocalDate.of(2024, 3, 4), 0), instant(LocalDate.of(2024, 3, 5), 12)),
                ).toList()
        assertThat(ticks.map { it.price }).containsExactly(BigDecimal("4.00"))
        assertThat(ticks.single().timestamp).isEqualTo(instant(LocalDate.of(2024, 3, 4), 13).toEpochMilli())
        assertThat(ticks.single().symbol).isEqualTo("MACRO:DGS10")
    }
}
