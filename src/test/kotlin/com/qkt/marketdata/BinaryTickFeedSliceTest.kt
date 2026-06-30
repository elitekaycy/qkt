package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryTickFeedSliceTest {
    private fun write(dir: Path): Path {
        val f = dir.resolve("d.bin")
        // ts 0, 1000, 2000, ... 9000
        val ticks = (0 until 10).map { Tick("X", Money.of((100 + it).toString()), it * 1000L) }
        BinaryTickWriter().write(f, "X", ticks)
        return f
    }

    private fun drain(feed: BinaryTickFeed): List<Long> {
        val out = mutableListOf<Long>()
        while (true) {
            val t = feed.next() ?: break
            out.add(t.timestamp)
        }
        return out
    }

    @Test
    fun `slice yields only the half-open window`(
        @TempDir dir: Path,
    ) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(3000L, 7000L)
        assertThat(drain(feed)).containsExactly(3000L, 4000L, 5000L, 6000L) // 7000 excluded
    }

    @Test
    fun `slice with no ticks in window yields nothing`(
        @TempDir dir: Path,
    ) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(9500L, 9800L)
        assertThat(drain(feed)).isEmpty()
    }

    @Test
    fun `successive forward slices each yield their window`(
        @TempDir dir: Path,
    ) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(0L, 2000L)
        assertThat(drain(feed)).containsExactly(0L, 1000L)
        feed.slice(2000L, 4000L)
        assertThat(drain(feed)).containsExactly(2000L, 3000L)
    }

    @Test
    fun `slice covering all ticks yields everything`(
        @TempDir dir: Path,
    ) {
        val feed = BinaryTickFeed(write(dir))
        feed.slice(Long.MIN_VALUE, Long.MAX_VALUE)
        assertThat(drain(feed)).hasSize(10)
    }
}
