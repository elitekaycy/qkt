package com.qkt.marketdata

import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [BinaryTickFeed.mustFeedRest] finds a fill bar's must-feed ticks — the ticks after the opening that
 * set a new price/ask/bid extreme, plus the close — by scanning the raw scaled-long columns, decoding
 * only the kept ticks. It must select the byte-identical set a decode-everything scan would, since a
 * scaled-long compare is order-equivalent to the stored BigDecimal compare.
 */
class BinaryTickFeedMustFeedTest {
    @Test
    fun `mustFeedRest returns the rest extremes plus the close, byte-identical to a full decode`(
        @TempDir dir: Path,
    ) {
        // opening, flat (no new extreme), new-high, interior (no new extreme), new-low, close.
        val ticks =
            listOf(
                tick(1000, 100.0, 99.9, 100.1, 1.0),
                tick(1001, 100.0, 99.9, 100.1, 1.0),
                tick(1002, 101.0, 100.9, 101.1, 2.0),
                tick(1003, 100.5, 100.4, 100.6, 1.0),
                tick(1004, 98.0, 97.9, 98.1, 3.0),
                tick(1005, 99.0, 98.9, 99.1, 1.0),
            )
        val bin = dir.resolve("SYM.bin")
        BinaryTickWriter().write(bin, "SYM", ticks)

        val decoded = BinaryTickFeed(bin).use { drain(it) }
        // rest extremes among indices 1..4 (close = 5 added regardless): 2 (new high) and 4 (new low).
        val expected = listOf(decoded[2], decoded[4], decoded[5])

        val got = BinaryTickFeed(bin).use { it.mustFeedRest(1000, 1006) }

        assertThat(got).isEqualTo(expected)
    }

    @Test
    fun `mustFeedRest yields only the close when no rest tick sets a new extreme`(
        @TempDir dir: Path,
    ) {
        val ticks =
            listOf(
                tick(2000, 50.0, 49.9, 50.1, 1.0),
                tick(2001, 50.0, 49.9, 50.1, 1.0),
                tick(2002, 50.0, 49.9, 50.1, 1.0),
            )
        val bin = dir.resolve("FLAT.bin")
        BinaryTickWriter().write(bin, "FLAT", ticks)
        val decoded = BinaryTickFeed(bin).use { drain(it) }

        val got = BinaryTickFeed(bin).use { it.mustFeedRest(2000, 2003) }

        assertThat(got).isEqualTo(listOf(decoded[2]))
    }

    private fun drain(feed: BinaryTickFeed): List<Tick> {
        val out = ArrayList<Tick>()
        while (true) out.add(feed.next() ?: break)
        return out
    }

    private fun tick(
        ts: Long,
        price: Double,
        bid: Double,
        ask: Double,
        volume: Double,
    ): Tick =
        Tick(
            symbol = "SYM",
            price = BigDecimal.valueOf(price),
            timestamp = ts,
            volume = BigDecimal.valueOf(volume),
            bid = BigDecimal.valueOf(bid),
            ask = BigDecimal.valueOf(ask),
        )
}
