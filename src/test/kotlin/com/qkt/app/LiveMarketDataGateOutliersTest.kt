package com.qkt.app

import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Options are not judged for price outliers, listed or not: on the bot2 soak, strikes Deribit listed after the
 * catalog's last refresh were judged as linear prices and re-baselined at ERROR every few seconds (qkt#1550).
 */
class LiveMarketDataGateOutliersTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "DERIBIT:BTC_USDC",
        )
    private val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        listOf(OptionListing("BTC_USDC-27DEC24-90000-P", "90000", "put", 1735286400000)),
                    ),
            ),
            Path.of("data"),
        )

    @Test
    fun `a listed option is not judged`() {
        assertThat(judgesPriceOutliers(registry, "DERIBIT:BTC_USDC_27DEC24_90000_P")).isFalse()
    }

    @Test
    fun `an option the catalog does not list yet is not judged either`() {
        assertThat(judgesPriceOutliers(registry, "DERIBIT:BTC_USDC_2NOV26_90000_C")).isFalse()
    }

    @Test
    fun `anything else is judged, known or not`() {
        assertThat(judgesPriceOutliers(registry, "DERIBIT:BTC_USDC_PERPETUAL")).isTrue()
        assertThat(judgesPriceOutliers(registry, "EXNESS:XAUUSD")).isTrue()
        assertThat(
            judgesPriceOutliers(
                object : InstrumentRegistry {
                    override fun lookup(qktSymbol: String) = null
                },
                "DERIBIT:BTC_USDC_2NOV26_90000_C",
            ),
        ).isTrue()
    }
}
