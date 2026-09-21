package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The daemon never hands warmup the venue source itself: it hands it a router over a prefix remap
 * over the cached venue source. The first version of the lagging-history fix asked the outermost
 * source whether it could be re-read, got "no" from the router, and never ran in a real daemon.
 */
class RefreshableBarsRoutingTest {
    private class Venue :
        InMemoryMarketSource("venue"),
        RefreshableBars {
        val forgotten = mutableListOf<String>()

        override fun forgetBars(
            symbol: String,
            window: TimeWindow,
        ) {
            forgotten += symbol
        }
    }

    private val venue = Venue()
    private val files = InMemoryMarketSource("files")
    private val daemonSource =
        CompositeMarketSource(
            routes =
                listOf(
                    SymbolPattern.prefix("EXNESS_S1:") to PrefixRemapMarketSource(venue, "EXNESS:", "EXNESS_S1:"),
                    SymbolPattern.prefix("EXNESS:") to venue,
                ),
            fallback = files,
        )

    @Test
    fun `a venue symbol can be re-read through the router and the prefix remap`() {
        assertThat(daemonSource.canRefresh("EXNESS:EURUSD")).isTrue()
        assertThat(daemonSource.canRefresh("EXNESS_S1:EURUSD")).isTrue()

        daemonSource.forgetBars("EXNESS_S1:EURUSD", TimeWindow.ONE_MINUTE)
        daemonSource.forgetBars("EXNESS:XAUUSD", TimeWindow.ONE_MINUTE)

        assertThat(
            venue.forgotten,
        ).`as`("the remap translates back to the venue's own prefix").containsExactly("EXNESS:EURUSD", "EXNESS:XAUUSD")
    }

    @Test
    fun `a symbol served from files cannot, so a backtest never waits`() {
        assertThat(daemonSource.canRefresh("BACKTEST:EURUSD")).isFalse()
    }
}
