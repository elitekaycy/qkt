package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.OptionRootSymbol
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
import org.junit.jupiter.api.Test

/**
 * The real trade chains of `btc-usdc-trade-25sep26`. Counts recomputed independently from the raw files:
 * 5713 quote rows with a positive mark before their contract's expiry, 237 contracts, and 53 of those
 * expiring inside 25–26 September (each gets one settlement print).
 */
class OptionRootMarketSourceTest {
    private val dir = Paths.get(requireNotNull(javaClass.getResource("/options/btc-usdc-trade-25sep26")).toURI())
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.TRADE,
            markSpread = BigDecimal("0.05"),
        )
    private val catalog =
        Json.decodeFromString(
            OptionCatalog.serializer(),
            dir.resolve("contracts/DERIBIT/BTC_USDC.options.json").toFile().readText(),
        )
    private val source = OptionRootMarketSource(OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog), dir))
    private val window = TimeRange(Instant.parse("2026-09-25T00:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"))

    @Test
    fun `a fed root emits every quoted contract once per snapshot and one settlement print per expiry seen`() {
        val ticks = source.ticks("OPTIONS:DERIBIT.BTC_USDC", window).toList()

        assertThat(source.supports("OPTIONS:DERIBIT.BTC_USDC")).isTrue()
        assertThat(ticks).hasSize(5713 + 53)
        assertThat(ticks.map { it.timestamp }).isSorted()
        assertThat(ticks.map { it.symbol }.toSet()).hasSize(237).allMatch { it.startsWith("DERIBIT:BTC_USDC_") }
        val print =
            ticks.single {
                it.symbol == "DERIBIT:BTC_USDC_26SEP26_84000_C" &&
                    it.timestamp == Instant.parse("2026-09-26T08:00:00Z").toEpochMilli()
            }
        assertThat(print.price).isEqualByComparingTo("42.83")
        assertThat(listOf(print.bid, print.ask)).containsOnlyNulls()
    }

    @Test
    fun `the root symbol names its root and a malformed one says what is wrong`() {
        assertThat(OptionRootSymbol.parse("OPTIONS:DERIBIT.BTC_USDC").getOrThrow().root).isEqualTo("DERIBIT:BTC_USDC")
        assertThat(
            OptionRootSymbol.parse("OPTIONS:DERIBIT").exceptionOrNull(),
        ).hasMessageContaining("OPTIONS:<VENUE>.<ROOT>")
        assertThat(
            OptionRootSymbol.parse("CHAIN:DERIBIT.BTC_USDC").exceptionOrNull(),
        ).hasMessageContaining("not an option root")
    }
}
