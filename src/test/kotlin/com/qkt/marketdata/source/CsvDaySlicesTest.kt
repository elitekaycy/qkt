package com.qkt.marketdata.source

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bar-by-bar slices of one CSV tick day. A replay asks for them in time order, so they read the day once: reopening
 * and scanning the file for every bar made `--bars` over a 3.5M-trade day run past 300 s (2026-10-20).
 */
class CsvDaySlicesTest {
    private val ticks = (0 until 1000).map { Tick("X", Money.of("1"), it * 60L) }

    private var opens = 0

    private fun slices() =
        CsvDaySlices {
            opens++
            val iter = ticks.iterator()
            object : TickFeed {
                override fun next(): Tick? = if (iter.hasNext()) iter.next() else null
            }
        }

    @Test
    fun `forward slices return each window's ticks and read the day once`() {
        val day = slices()

        val windows = (0 until 100).map { i -> day.slice(i * 600L, (i + 1) * 600L).map { it.timestamp }.toList() }

        assertThat(windows.flatten()).isEqualTo(ticks.map { it.timestamp })
        assertThat(windows[3]).containsExactly(1800L, 1860L, 1920L, 1980L, 2040L, 2100L, 2160L, 2220L, 2280L, 2340L)
        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun `a slice that goes back in time reopens and is still exact`() {
        val day = slices()
        day.slice(30_000L, 30_600L).toList()

        val earlier = day.slice(600L, 780L).map { it.timestamp }.toList()

        assertThat(earlier).containsExactly(600L, 660L, 720L)
        assertThat(opens).isEqualTo(2)
    }

    @Test
    fun `an empty window between ticks yields nothing and keeps the next tick for the next window`() {
        val day = slices()

        assertThat(day.slice(61L, 119L).toList()).isEmpty()
        assertThat(day.slice(119L, 181L).map { it.timestamp }.toList()).containsExactly(120L, 180L)
    }
}
