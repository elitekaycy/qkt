package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A session with a moving set still re-reads lagging venue history: the expandable source passes that on. */
class ExpandableLiveSourceRefreshTest {
    private val forgotten = mutableListOf<String>()

    private val venue =
        object : MarketSource by NullMarketSource, RefreshableBars {
            override fun canRefresh(symbol: String) = symbol.startsWith("GW:")

            override fun forgetBars(
                symbol: String,
                window: TimeWindow,
            ) {
                forgotten += symbol
            }
        }

    @Test
    fun `a venue that can re-read its history still can behind the expandable source`() {
        val source = ExpandableLiveSource(venue)
        assertThat(source.canRefresh("GW:A")).isTrue()
        assertThat(source.canRefresh("FILE:A")).isFalse()
        source.forgetBars("GW:A", TimeWindow.ONE_MINUTE)
        assertThat(forgotten).containsExactly("GW:A")
    }

    @Test
    fun `a source that cannot re-read stays that way`() {
        val source = ExpandableLiveSource(NullMarketSource)
        assertThat(source.canRefresh("GW:A")).isFalse()
        source.forgetBars("GW:A", TimeWindow.ONE_MINUTE)
        assertThat(forgotten).isEmpty()
    }
}
