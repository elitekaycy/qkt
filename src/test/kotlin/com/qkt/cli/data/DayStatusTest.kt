package com.qkt.cli.data

import com.qkt.marketdata.store.DayQuality
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DayStatusTest {
    private val day = LocalDate.of(2024, 1, 2)
    private val start = 1_704_153_600_000L

    private fun q(
        first: Long,
        last: Long,
    ) = DayQuality(tickCount = 2, maxGapMs = last - first, readable = true, firstMs = first, lastMs = last)

    @Test
    fun `ticks inside their own UTC day are ok`() {
        assertThat(DayStatus.of(q(start, start + 3_600_000), day, start - 1)).isEqualTo("ok")
    }

    @Test
    fun `a tick from the next day in this day's file is misplaced`() {
        assertThat(DayStatus.of(q(start, start + 86_400_000), day, null)).startsWith("MISPLACED")
        assertThat(DayStatus.of(q(start - 1, start + 10), day, null)).startsWith("MISPLACED")
    }

    @Test
    fun `a day starting before the previous day's last tick runs backwards`() {
        assertThat(DayStatus.of(q(start + 10, start + 20), day, start + 15)).startsWith("BACKWARDS")
    }

    @Test
    fun `unreadable and empty files keep their verdicts`() {
        assertThat(DayStatus.of(DayQuality(0, 0, readable = false), day, null)).startsWith("CORRUPT")
        assertThat(DayStatus.of(DayQuality(0, 0, readable = true), day, null)).startsWith("EMPTY")
    }
}
