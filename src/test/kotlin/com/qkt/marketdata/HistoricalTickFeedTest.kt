package com.qkt.marketdata

import com.qkt.common.Money
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HistoricalTickFeedTest {
    @Test
    fun `next returns ticks in order then null`() {
        val ticks =
            listOf(
                Tick("XAUUSD", Money.of("100"), 1L),
                Tick("XAUUSD", Money.of("110"), 2L),
                Tick("XAUUSD", Money.of("105"), 3L),
            )
        val feed = HistoricalTickFeed(ticks)

        assertThat(feed.next()?.timestamp).isEqualTo(1L)
        assertThat(feed.next()?.timestamp).isEqualTo(2L)
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `empty list returns null on first call`() {
        val feed = HistoricalTickFeed(emptyList())
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `repeated next calls past end keep returning null`() {
        val feed = HistoricalTickFeed(listOf(Tick("XAUUSD", Money.of("100"), 1L)))
        feed.next()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `fromCsv eager loads a CSV into a list backed feed`(
        @TempDir dir: Path,
    ) {
        val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
        val csv = "$header\n1000,X,100,1,,,,\n2000,X,101,1,,,,"
        val path = dir.resolve("a.csv")
        Files.writeString(path, csv)
        val feed = HistoricalTickFeed.fromCsv(path)
        assertThat(feed.next()!!.timestamp).isEqualTo(1000L)
        assertThat(feed.next()!!.timestamp).isEqualTo(2000L)
        assertThat(feed.next()).isNull()
    }
}
