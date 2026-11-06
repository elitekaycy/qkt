package com.qkt.connector.mt5.marketdata

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.connector.mt5.Mt5RoutedGateway
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Bid bars lifted to mid need the symbol's point, read once from the venue. */
class Mt5BarFetcherPointTest {
    private val gateway = Mt5RoutedGateway()
    private val fetcher = Mt5BarFetcher(gateway.url, normalizeBidBarsToMid = true)
    private val range =
        TimeRange(Instant.parse("2026-05-13T08:00:00Z"), Instant.parse("2026-05-13T08:05:00Z"))

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `a symbol the venue reports no point for cannot be normalized to mid`() {
        gateway.on("/symbol_info/XAUUSDm", """{"digits":2}""")

        assertThatThrownBy { fetcher.fetchRange("XAUUSDm", TimeWindow(300_000L), range).toList() }
            .hasMessageContaining("did not provide point metadata for XAUUSDm")
    }

    @Test
    fun `the point is read once and reused for later fetches`() {
        gateway.on("/symbol_info/XAUUSDm", """{"point":0.01}""")
        gateway.on(
            "/fetch_data_range",
            """[{"open":100,"high":100,"low":100,"close":100,"spread":10,"time":"2026-05-13T08:00:00Z"}]""",
        )

        val first = fetcher.fetchRange("XAUUSDm", TimeWindow(300_000L), range).toList()
        fetcher.fetchRange("XAUUSDm", TimeWindow(300_000L), range).toList()

        assertThat(first.single().close).isEqualByComparingTo("100.05")
        assertThat(gateway.calls("/symbol_info/XAUUSDm")).hasSize(1)
    }
}
