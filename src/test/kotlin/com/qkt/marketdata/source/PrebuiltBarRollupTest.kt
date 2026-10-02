package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Fetched 1m bars serve a 5m series by rolling up on the UTC grid, not by falling back to ticks. */
class PrebuiltBarRollupTest {
    private val day = LocalDate.parse("2026-09-22")
    private val dayStart = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli()

    private fun minute(
        i: Int,
        o: String,
        h: String,
        l: String,
        c: String,
    ) = Candle(
        "EXNESS:XAUUSD",
        Money.of(o),
        Money.of(h),
        Money.of(l),
        Money.of(c),
        Money.of("${i + 1}"),
        dayStart + i * 60_000L,
        dayStart + (i + 1) * 60_000L,
    )

    @Test
    fun `a 5m series is rebuilt from stored 1m bars when no 5m bars are stored`(
        @TempDir root: Path,
    ) {
        val store = LocalBarStore(root)
        val oneMinute =
            listOf(
                minute(0, "10", "12", "9", "11"),
                minute(1, "11", "15", "10", "14"),
                minute(2, "14", "14", "8", "9"),
                minute(3, "9", "10", "9", "10"),
                minute(4, "10", "11", "10", "11"),
                minute(5, "11", "13", "11", "12"),
            )
        store.writeDay("EXNESS", "XAUUSD", "1m", day, oneMinute)
        val range = TimeRange(Instant.ofEpochMilli(dayStart), Instant.ofEpochMilli(dayStart + 10 * 60_000L))

        val bars = PrebuiltBarReader(store, null).bars("EXNESS:XAUUSD", TimeWindow.parse("5m"), range)!!.toList()

        assertThat(bars).hasSize(2)
        val first = bars.first()
        assertThat(first.startTime).isEqualTo(dayStart)
        assertThat(first.endTime).isEqualTo(dayStart + 5 * 60_000L)
        assertThat(first.open).isEqualByComparingTo("10")
        assertThat(first.high).isEqualByComparingTo("15")
        assertThat(first.low).isEqualByComparingTo("8")
        assertThat(first.close).isEqualByComparingTo("11")
        assertThat(first.volume).isEqualByComparingTo("15")
        assertThat(bars[1].open).isEqualByComparingTo("11")
        assertThat(bars[1].close).isEqualByComparingTo("12")
    }

    @Test
    fun `a timeframe that does not tile a UTC day is not rolled up`(
        @TempDir root: Path,
    ) {
        val store = LocalBarStore(root)
        store.writeDay("EXNESS", "XAUUSD", "1m", day, listOf(minute(0, "10", "12", "9", "11")))

        assertThat(store.finerTimeframe("EXNESS", "XAUUSD", TimeWindow.parse("7m"))).isNull()
        assertThat(store.finerTimeframe("EXNESS", "XAUUSD", TimeWindow.parse("5m"))).isEqualTo(TimeWindow.parse("1m"))
    }
}
