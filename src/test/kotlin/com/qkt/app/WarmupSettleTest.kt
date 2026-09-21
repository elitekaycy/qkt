package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.RefreshableBars
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupSettleTest {
    private val window = TimeWindow.ONE_MINUTE
    private val upper = Instant.parse("2026-09-21T13:07:00Z").toEpochMilli()

    private fun bars(
        fromMinute: Int,
        toMinute: Int,
    ) = (fromMinute..toMinute).map { minute ->
        val start = Instant.parse("2026-09-21T13:00:00Z").toEpochMilli() + minute * 60_000L
        Candle("X", Money.of("1"), Money.of("1"), Money.of("1"), Money.of("1"), Money.of("1"), start, start + 60_000L)
    }

    /** A venue whose history catches up after [staleReads] reads, as a busy MT5 terminal's does. */
    private inner class LaggingVenue(
        private val staleReads: Int,
        private val stale: List<Candle>,
        private val fresh: List<Candle>,
    ) : InMemoryMarketSource("lagging"),
        RefreshableBars {
        var reads = 0
        var forgotten = 0

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> {
            val served = if (reads++ < staleReads) stale else fresh
            return served
                .filter {
                    it.startTime >= range.from.toEpochMilli() && it.startTime < range.to.toEpochMilli()
                }.asSequence()
        }

        override fun forgetBars(
            symbol: String,
            window: TimeWindow,
        ) {
            forgotten++
        }
    }

    private val waits = mutableListOf<Long>()
    private val settle = WarmupSettle(pause = { waits += it })

    @Test
    fun `history that is behind its own clock is read again until it has caught up`() {
        // 13:07:31 on a loaded terminal: bars end at 13:02. Two seconds later 13:03-13:06 are there.
        val venue = LaggingVenue(staleReads = 2, stale = bars(-10, 2), fresh = bars(-10, 6))

        val loaded = WarmupHistoryLoader(venue, settle).load("X", window, count = 5, upperMs = upper)

        assertThat(loaded.map { Instant.ofEpochMilli(it.startTime).toString().substring(11, 16) })
            .containsExactly("13:02", "13:03", "13:04", "13:05", "13:06")
        assertThat(waits).containsExactly(2_000L)
        assertThat(venue.forgotten).`as`("the cached stale read must not be served again").isEqualTo(1)
    }

    @Test
    fun `a gap that never closes is a quiet market and the first answer stands`() {
        val venue = LaggingVenue(staleReads = Int.MAX_VALUE, stale = bars(-10, 2), fresh = emptyList())

        val loaded = WarmupHistoryLoader(venue, settle).load("X", window, count = 5, upperMs = upper)

        assertThat(loaded.last().startTime).isEqualTo(bars(2, 2).single().startTime)
        assertThat(waits).hasSize(5)
    }

    @Test
    fun `a closed market is not waited on`() {
        // The newest bar is from the previous hour's session: that is a weekend or a break, not lag.
        val venue = LaggingVenue(staleReads = Int.MAX_VALUE, stale = bars(-120, -60), fresh = emptyList())

        WarmupHistoryLoader(venue, settle).loadAvailable("X", window, count = 5, upperMs = upper)

        assertThat(waits).isEmpty()
    }

    @Test
    fun `history that reaches the live window is used as it is`() {
        val venue = LaggingVenue(staleReads = 0, stale = emptyList(), fresh = bars(-10, 6))

        WarmupHistoryLoader(venue, settle).load("X", window, count = 5, upperMs = upper)

        assertThat(waits).isEmpty()
        assertThat(venue.forgotten).isZero()
    }

    @Test
    fun `the daemon's router over the venue still gets the re-read`() {
        val venue = LaggingVenue(staleReads = 2, stale = bars(-10, 5), fresh = bars(-10, 6))
        val routed =
            com.qkt.marketdata.source.CompositeMarketSource(
                routes =
                    listOf(
                        com.qkt.marketdata.source.SymbolPattern
                            .prefix("X") to venue,
                    ),
                fallback = InMemoryMarketSource("files"),
            )

        val loaded = WarmupHistoryLoader(routed, settle).load("X", window, count = 5, upperMs = upper)

        // One bar behind, as on 2026-09-21 13:52: 13:46-13:50 was served for a window ending 13:52.
        assertThat(loaded.last().endTime).isEqualTo(upper)
        assertThat(venue.forgotten).isEqualTo(1)
    }

    @Test
    fun `a source that cannot lag is never read twice`() {
        val files = InMemoryMarketSource("files").also { it.seedBars("X", window, bars(-10, 2)) }

        WarmupHistoryLoader(files, settle).load("X", window, count = 5, upperMs = upper)

        assertThat(waits).`as`("backtests read files; they must never wait").isEmpty()
    }

    @Test
    fun `a forming bar waits out the same lag, so its last minutes are not left to a later replay`() {
        // A 1h stream started at 13:07:30: its forming bar needs 13:00..13:06. The venue serves only
        // 13:00..13:04 at first; a replay reading the history later would have all seven minutes.
        val venue = LaggingVenue(staleReads = 1, stale = bars(0, 4), fresh = bars(0, 6))

        val forming = FormingBar.load(venue, "X", TimeWindow.ONE_HOUR, nowMs = upper + 30_000L, settle = settle)

        assertThat(forming!!.minutes).hasSize(7)
        assertThat(venue.forgotten).isEqualTo(1)
    }

    @Test
    fun `a quiet symbol is waited on once per minute, not once per stream`() {
        // Nothing traded after 13:04: every read of this symbol's minutes shows the same gap.
        val venue = LaggingVenue(staleReads = Int.MAX_VALUE, stale = bars(-10, 4), fresh = emptyList())
        val loader = WarmupHistoryLoader(venue, settle)

        loader.load("X", window, count = 5, upperMs = upper)
        loader.forming("X", TimeWindow.ONE_HOUR, upper + 30_000L)
        loader.forming("X", TimeWindow(14_400_000L), upper + 30_000L)

        assertThat(waits).`as`("one wait of five re-reads for the symbol, not three").hasSize(5)
    }
}
