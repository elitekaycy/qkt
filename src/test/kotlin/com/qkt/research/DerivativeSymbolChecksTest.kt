package com.qkt.research

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
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
    fun `dollar-family fees and fee-free foreign roots pass`() {
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
}
