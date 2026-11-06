package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeriesFetcher
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MacroMarketSourceLiveTest {
    @Test
    fun `live ticks refresh the cataloged series from its official source on the first read`(
        @TempDir tmp: Path,
    ) {
        val store = MacroSeriesStore(tmp)
        val missing = tmp.resolve("absent-ocr.xlsx").toString()
        val fetcher = PolicyRateSeriesFetcher(store, rbnzSource = missing)
        val clock = FixedClock(Instant.parse("2024-03-08T00:00:00Z").toEpochMilli())
        val source = MacroMarketSource(store, clock = clock, policyRateFetcher = fetcher)

        val feed = source.liveTicks(listOf("MACRO:RBNZ_OCR"))

        assertThat(feed).isInstanceOf(PolicyRateLiveFeed::class.java)
        assertThatThrownBy { feed.next() }
            .hasMessageContaining("RBNZ policy-rate artifact is not a regular file")
            .hasMessageContaining(missing)
    }

    @Test
    fun `live ticks refuse a symbol outside the MACRO prefix`(
        @TempDir tmp: Path,
    ) {
        assertThatThrownBy { MacroMarketSource(MacroSeriesStore(tmp)).liveTicks(listOf("FX:EURUSD")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("MacroMarketSource cannot serve FX:EURUSD")
    }

    @Test
    fun `live ticks refuse a macro series with no official live source`(
        @TempDir tmp: Path,
    ) {
        assertThatThrownBy { MacroMarketSource(MacroSeriesStore(tmp)).liveTicks(listOf("MACRO:DGS10")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("live macro series MACRO:DGS10 is unsupported")
    }
}
