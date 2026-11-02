package com.qkt.app

import com.qkt.connector.mt5.MT5BrokerProfile
import com.qkt.connector.mt5.SymbolPolicy
import com.qkt.connector.mt5.marketdata.Mt5MarketSource
import com.qkt.marketdata.source.CachedHistoricalMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.PrefixRemapMarketSource
import com.qkt.marketdata.source.SharedLiveMarketSource
import com.qkt.marketdata.source.SymbolPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleCloseGraceCheckTest {
    private fun mt5(tickPollIntervalMs: Long) =
        Mt5MarketSource(
            MT5BrokerProfile(
                name = "exness",
                gatewayUrl = "http://localhost:5002",
                symbolPolicy = SymbolPolicy(suffix = "m"),
                magic = 123,
                tickPollIntervalMs = tickPollIntervalMs,
            ),
        )

    /** The daemon's shape: a strategy's own profile prefix remapped onto the shared feed, behind the router. */
    private fun daemonSource(tickPollIntervalMs: Long) =
        CompositeMarketSource(
            routes =
                listOf(
                    SymbolPattern.prefix("EXNESS_O04:") to
                        PrefixRemapMarketSource(
                            CachedHistoricalMarketSource(SharedLiveMarketSource(mt5(tickPollIntervalMs))),
                            delegatePrefix = "EXNESS:",
                            localPrefix = "EXNESS_O04:",
                        ),
                ),
            fallback = NullMarketSource,
        )

    @Test
    fun `a polled feed slower than the grace is named with the grace that fits`() {
        // The bot2 soak: Exness polled every 10 s at the 2 s default dropped the tail of every bar.
        val warnings =
            CandleCloseGraceCheck.warnings(daemonSource(10_000), listOf("EXNESS_O04:XAUUSD", "PAPER:BTC"), 2_000)

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single())
            .contains("runtime.candle_close_grace_ms is 2000 ms")
            .contains("EXNESS_O04:XAUUSD deliver ticks up to 10000 ms")
            .contains("at least 15000 ms")
            .doesNotContain("PAPER:BTC")
    }

    @Test
    fun `a grace longer than the poll is quiet`() {
        assertThat(CandleCloseGraceCheck.warnings(daemonSource(1_000), listOf("EXNESS_O04:XAUUSD"), 2_000)).isEmpty()
        assertThat(CandleCloseGraceCheck.warnings(daemonSource(10_000), listOf("EXNESS_O04:XAUUSD"), 15_000)).isEmpty()
    }

    @Test
    fun `a grace equal to the poll still drops the tail, so it is named`() {
        assertThat(CandleCloseGraceCheck.warnings(daemonSource(2_000), listOf("EXNESS_O04:XAUUSD"), 2_000)).hasSize(1)
    }

    @Test
    fun `a pushed stream has no delivery lag, even with no grace`() {
        assertThat(NullMarketSource.tickDeliveryLagMsFor("PAPER:BTC")).isZero
        assertThat(CandleCloseGraceCheck.warnings(daemonSource(10_000), listOf("PAPER:BTC"), 0)).isEmpty()
    }

    @Test
    fun `the poll interval reaches the router only for the profile's own symbols`() {
        val source = daemonSource(10_000)
        assertThat(source.tickDeliveryLagMsFor("EXNESS_O04:XAUUSD")).isEqualTo(10_000)
        assertThat(source.tickDeliveryLagMsFor("EXNESS_O05:XAUUSD")).isZero
        assertThat(mt5(10_000).tickDeliveryLagMsFor("OANDA:XAUUSD")).isZero
    }

    @Test
    fun `symbols on feeds of different speeds are grouped, slowest first`() {
        val slow = daemonSource(10_000)
        val router =
            CompositeMarketSource(
                routes =
                    listOf(
                        SymbolPattern.prefix("EXNESS_O04:") to slow,
                        SymbolPattern.prefix("EXNESS:") to mt5(3_000),
                    ),
                fallback = NullMarketSource,
            )
        val warnings =
            CandleCloseGraceCheck.warnings(
                router,
                listOf("EXNESS:EURUSD", "EXNESS_O04:XAUUSD", "EXNESS:XAUUSD"),
                2_000,
            )

        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("EXNESS_O04:XAUUSD deliver ticks up to 10000 ms")
        assertThat(warnings[1]).contains("EXNESS:EURUSD, EXNESS:XAUUSD deliver ticks up to 3000 ms").contains("4500")
    }
}
