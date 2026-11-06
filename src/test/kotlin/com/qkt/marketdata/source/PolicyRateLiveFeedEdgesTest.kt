package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PolicyRateLiveFeedEdgesTest {
    private fun feed(
        store: MacroSeriesStore,
        refreshes: MutableList<PolicyRateSeries>,
    ) = PolicyRateLiveFeed(
        symbols = mapOf("MACRO:RBNZ_OCR" to PolicyRateSeries.RBNZ_OCR),
        store = store,
        clock = FixedClock(1_709_640_000_000L),
        pollIntervalMs = 1L,
        refresh = { series, _, _ -> refreshes += series },
    )

    @Test
    fun `a closed feed ends without polling`(
        @TempDir tmp: Path,
    ) {
        val refreshes = mutableListOf<PolicyRateSeries>()
        val feed = feed(MacroSeriesStore(tmp), refreshes)

        feed.close()

        assertThat(feed.next()).isNull()
        assertThat(refreshes).isEmpty()
    }

    @Test
    fun `a refresh that leaves no recent value fails naming the symbol`(
        @TempDir tmp: Path,
    ) {
        val refreshes = mutableListOf<PolicyRateSeries>()
        val feed = feed(MacroSeriesStore(tmp), refreshes)

        assertThatThrownBy { feed.next() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("official policy-rate source returned no recent value for MACRO:RBNZ_OCR")
        assertThat(refreshes).containsExactly(PolicyRateSeries.RBNZ_OCR)
    }

    @Test
    fun `closing day slices closes the open feed and a later slice reopens it`() {
        var opens = 0
        var closes = 0
        val day =
            CsvDaySlices {
                opens++
                val ticks = mutableListOf(Tick("X", java.math.BigDecimal.ONE, 5L)).iterator()
                object : TickFeed {
                    override fun next(): Tick? = if (ticks.hasNext()) ticks.next() else null

                    override fun close() {
                        closes++
                    }
                }
            }
        day.slice(0L, 10L).toList()

        day.close()
        val again = day.slice(0L, 10L).map { it.timestamp }.toList()

        assertThat(closes).isEqualTo(1)
        assertThat(opens).isEqualTo(2)
        assertThat(again).containsExactly(5L)
    }
}
