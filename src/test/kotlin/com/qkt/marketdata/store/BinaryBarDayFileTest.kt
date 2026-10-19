package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryBarDayFileTest {
    private val tf = TimeWindow.parse("15m")
    private val day = LocalDate.parse("2026-01-05")
    private val start = 1_767_571_200_000L // 2026-01-05T00:00Z

    private fun bar(at: Long) =
        BigDecimal.ONE.setScale(Money.SCALE).let { p -> Candle("XAUUSD", p, p, p, p, p, at, at + tf.durationMs) }

    @Test
    fun `the bar count comes from the header and a header-only day is not stored`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        assertThat(store.barCount("BACKTEST", "XAUUSD", tf, day)).isNull()

        store.writeDay("BACKTEST", "XAUUSD", tf, day, emptyList())
        val file = root.resolve("bars/BACKTEST/XAUUSD/15m/$day.bin")
        assertThat(Files.size(file)).isEqualTo(43L) // the header-only files #1441 found
        assertThat(store.barCount("BACKTEST", "XAUUSD", tf, day)).isZero()
        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isFalse()

        store.writeDay("BACKTEST", "XAUUSD", tf, day, listOf(bar(start), bar(start + tf.durationMs)))
        assertThat(store.barCount("BACKTEST", "XAUUSD", tf, day)).isEqualTo(2)
        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isTrue()
        assertThat(store.startTimes("BACKTEST", "XAUUSD", tf, day)).containsExactly(start, start + tf.durationMs)
    }

    @Test
    fun `a day built with no bars is rebuilt once its ticks hold any, and stays built while they do not`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        val ticks = root.resolve("symbols/XAUUSD/$day.bin")
        Files.createDirectories(ticks.parent)
        BinaryTickWriter().write(ticks, "XAUUSD", emptyList())
        Files.setLastModifiedTime(ticks, FileTime.fromMillis(1_000_000L))
        store.writeDay("BACKTEST", "XAUUSD", tf, day, emptyList())

        // The tick day is empty too: nothing to build, so the empty bars stand.
        assertThat(store.isBuiltFrom("BACKTEST", "XAUUSD", tf, day, ticks)).isTrue()

        // The ticks landed later but the file's time did not move past the bars' (a copied or restored store).
        BinaryTickWriter().write(ticks, "XAUUSD", listOf(Tick("XAUUSD", Money.of("2000"), start)))
        Files.setLastModifiedTime(ticks, FileTime.fromMillis(1_000_000L))
        assertThat(store.isBuiltFrom("BACKTEST", "XAUUSD", tf, day, ticks)).isFalse()

        store.writeDay("BACKTEST", "XAUUSD", tf, day, listOf(bar(start)))
        assertThat(store.isBuiltFrom("BACKTEST", "XAUUSD", tf, day, ticks)).isTrue()
        assertThat(store.isBuiltFrom("BACKTEST", "XAUUSD", tf, day.plusDays(1), ticks)).isFalse()
    }
}
