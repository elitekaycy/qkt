package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.Instant
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The real book snapshot of `btc-usdc-book-20261001` (values from its PROVENANCE). */
class ChainAnalyticsMarketSourceTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-book-20261001")).toURI())
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )
    private val catalog =
        Json.decodeFromString(
            OptionCatalog.serializer(),
            dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
        )
    private val source =
        ChainAnalyticsMarketSource(OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog), dir))
    private val day = TimeRange(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"))
    private val at = 1_790_822_716_427L

    @Test
    fun `each stored snapshot is a tick at the metric's value where it is defined`() {
        val ticks = source.ticks("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d", day).toList()

        assertThat(source.supports("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d")).isTrue()
        assertThat(ticks.single().timestamp).isEqualTo(at)
        assertThat(ticks.single().price).isEqualByComparingTo("33.82083984")
        assertThat(source.ticks("CHAIN:DERIBIT.BTC_USDC.atm_iv.90d", day).toList()).isEmpty()
    }

    @Test
    fun `warmup bars are one flat candle per observation`() {
        val bar = source.bars("CHAIN:DERIBIT.BTC_USDC.skew_25d.30d", TimeWindow.ONE_HOUR, day).single()

        assertThat(
            listOf(bar.open, bar.high, bar.low, bar.close).distinct().single(),
        ).isEqualByComparingTo("1.06899787")
        assertThat(bar.startTime).isEqualTo(at)
        assertThat(bar.endTime).isEqualTo(at + 3_600_000)
    }

    @Test
    fun `an undeclared root or a root without a chain series is refused by name`() {
        assertThatThrownBy { source.ticks("CHAIN:DERIBIT.ETH_USDC.atm_iv.30d", day).toList() }
            .hasMessageContaining("DERIBIT:ETH_USDC")
            .hasMessageContaining("options:")
    }
}
