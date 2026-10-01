package com.qkt.research

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DerivativeSymbolChecksTest {
    private fun root(
        currency: String,
        fee: String,
    ) = FuturesRoot(
        "CME:ES",
        currency,
        BigDecimal("50"),
        BigDecimal("0.25"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        null,
        BigDecimal(fee),
        BigDecimal.ZERO,
        null,
    )

    private fun accounting(registry: ContractCatalogRegistry) =
        AccountingEngine(currencyOf = {
            registry.lookup(it)?.currency
        })

    @Test
    fun `a continuous symbol without a declared root fails the run`() {
        val registry = ContractCatalogRegistry(emptyList(), emptyMap())
        assertThatThrownBy {
            requireDerivativeSymbolsResolvable(
                listOf("CME:ES@front"),
                accounting(registry),
                registry,
            )
        }.hasMessageContaining("CME:ES@front")
            .hasMessageContaining("futures:")
    }

    @Test
    fun `a fee in a currency incompatible with the account fails the run`() {
        val registry = ContractCatalogRegistry(listOf(root("EUR", "1.0")), emptyMap())
        assertThatThrownBy {
            requireDerivativeSymbolsResolvable(
                listOf("CME:ES@front"),
                accounting(registry),
                registry,
            )
        }.hasMessageContaining("EUR")
    }

    @Test
    fun `dollar-family fees and fee-free foreign roots pass the fee check`() {
        val usd = ContractCatalogRegistry(listOf(root("USD", "1.29")), emptyMap())
        assertThatCode {
            requireDerivativeSymbolsResolvable(listOf("CME:ES@front"), accounting(usd), usd)
        }.doesNotThrowAnyException()
        val eurNoFee = ContractCatalogRegistry(listOf(root("EUR", "0")), emptyMap())
        assertThatCode { requireDerivativeSymbolsResolvable(listOf("CME:ES@front"), accounting(eurNoFee), eurNoFee) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `cfd symbols are ignored`() {
        val registry = ContractCatalogRegistry(emptyList(), emptyMap())
        assertThatCode { requireDerivativeSymbolsResolvable(listOf("EXNESS:XAUUSD"), accounting(registry), registry) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a dated contract missing from the catalog fails the run through a layered registry`() {
        val registry = ContractCatalogRegistry(listOf(root("USD", "0")), emptyMap())
        val layered = LayeredInstrumentRegistry(listOf(registry, StandardInstrumentRegistry))
        assertThatThrownBy { requireDerivativeSymbolsResolvable(listOf("CME:ESH6"), accounting(registry), layered) }
            .hasMessageContaining("CME:ESH6")
            .hasMessageContaining("--catalog")
    }

    @Test
    fun `a catalogued option is refused until qkt has an option venue`() {
        val root =
            com.qkt.instrument.OptionRoot(
                "DERIBIT:BTC_USDC",
                "USDC",
                BigDecimal.ONE,
                com.qkt.instrument.TickSteps(BigDecimal("5")),
                BigDecimal("0.01"),
                BigDecimal("0.01"),
                "btc_usdc",
            )
        val catalog =
            com.qkt.instrument.OptionCatalog(
                root.root,
                listOf(com.qkt.instrument.OptionListing("BTC_USDC-27DEC24-90000-P", "90000", "put", 1735286400000)),
            )
        val registry = com.qkt.instrument.OptionCatalogRegistry(listOf(root), mapOf(root.root to catalog))

        assertThatThrownBy {
            requireDerivativeSymbolsResolvable(listOf("DERIBIT:BTC_USDC_27DEC24_90000_P"), AccountingEngine(), registry)
        }.hasMessageContaining("DERIBIT:BTC_USDC_27DEC24_90000_P").hasMessageContaining("option")
    }
}
