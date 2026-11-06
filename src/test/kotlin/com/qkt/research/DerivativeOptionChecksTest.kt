package com.qkt.research

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DerivativeOptionChecksTest {
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
        OptionCatalog(root.root, listOf(OptionListing("BTC_USDC-27DEC24-90000-P", "90000", "put", 1735286400000)))
    private val symbol = "DERIBIT:BTC_USDC_27DEC24_90000_P"

    @Test
    fun `an option whose chains have no data root is refused`() {
        val noDataRoot = OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog))

        assertThatThrownBy { requireDerivativeSymbolsResolvable(listOf(symbol), AccountingEngine(), noDataRoot) }
            .hasMessage("$symbol is an option but its chains have no data root")
    }

    @Test
    fun `an option from a registry that declares no option roots is refused`() {
        val meta = OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog)).lookup(symbol)
        val metaOnly =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta? = meta
            }

        assertThatThrownBy { requireDerivativeSymbolsResolvable(listOf(symbol), AccountingEngine(), metaOnly) }
            .hasMessage("$symbol is an option but no option roots are declared")
    }
}
