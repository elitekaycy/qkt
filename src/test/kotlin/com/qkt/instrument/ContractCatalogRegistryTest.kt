package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContractCatalogRegistryTest {
    private val btc =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            "crypto",
            BigDecimal.ZERO,
            BigDecimal("0.0005"),
            null,
        )
    private val catalog =
        ContractCatalog("BINANCE_UM:BTCUSDT", listOf(ListedContract("BTCUSDT_240927", 1_727_424_000_000L, "65528.1")))
    private val registry = ContractCatalogRegistry(listOf(btc), mapOf(btc.root to catalog))

    @Test
    fun `a listed contract resolves with its expiry`() {
        val meta = registry.lookup("BINANCE_UM:BTCUSDT_240927")
        assertThat((meta?.derivative as FutureTerms).expiryMs).isEqualTo(1_727_424_000_000L)
        assertThat(meta.currency).isEqualTo("USDT")
    }

    @Test
    fun `continuous views resolve without an expiry`() {
        listOf("BINANCE_UM:BTCUSDT@front", "BINANCE_UM:BTCUSDT@next").forEach { symbol ->
            val terms = registry.lookup(symbol)?.derivative as FutureTerms
            assertThat(terms.expiryMs).isNull()
            assertThat(terms.root).isEqualTo("BINANCE_UM:BTCUSDT")
        }
    }

    @Test
    fun `unknown symbols, selectors and roots are not claimed`() {
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT_991231")).isNull()
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT@third")).isNull()
        assertThat(registry.lookup("CME:ES@front")).isNull()
        assertThat(registry.lookup("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `a root without a catalog still resolves its continuous views`() {
        val bare = ContractCatalogRegistry(listOf(btc), emptyMap())
        assertThat(bare.lookup("BINANCE_UM:BTCUSDT@front")).isNotNull
        assertThat(bare.lookup("BINANCE_UM:BTCUSDT_240927")).isNull()
    }

    @Test
    fun `selector tokens parse case-sensitively to known values only`() {
        assertThat(ContinuousSelector.parse("front")).isEqualTo(ContinuousSelector.FRONT)
        assertThat(ContinuousSelector.parse("next")).isEqualTo(ContinuousSelector.NEXT)
        assertThat(ContinuousSelector.parse("FRONT")).isNull()
        assertThat(ContinuousSelector.FRONT.symbolFor("CME:ES")).isEqualTo("CME:ES@front")
    }

    @Test
    fun `a contract filed under the wrong root is refused`() {
        val misfiled = ContractCatalog("BINANCE_UM:BTCUSDT", listOf(ListedContract("ETHUSDT_240927", 1L)))
        assertThatThrownBy { ContractCatalogRegistry(listOf(btc), mapOf(btc.root to misfiled)) }
            .hasMessageContaining("ETHUSDT_240927")
            .hasMessageContaining("BINANCE_UM:BTCUSDT")
    }

    @Test
    fun `two roots claiming one symbol are refused`() {
        val alias = btc.copy(root = "BINANCE_UM:BTC")
        val catalogs =
            mapOf(
                alias.root to ContractCatalog(alias.root, listOf(ListedContract("BTCUSDT_240927", 1L))),
                btc.root to catalog,
            )
        assertThatThrownBy {
            ContractCatalogRegistry(listOf(btc, alias), catalogs)
        }.hasMessageContaining("BINANCE_UM:BTCUSDT_240927")
    }

    @Test
    fun `a contract of a declared root missing from its catalog explains itself`() {
        val reason = registry.missingReason("BINANCE_UM:BTCUSDT_241227")
        assertThat(
            reason,
        ).contains("BINANCE_UM:BTCUSDT").contains("contracts/BINANCE_UM/BTCUSDT.json").contains("--catalog")
    }

    @Test
    fun `an unknown selector of a declared root explains itself`() {
        assertThat(registry.missingReason("BINANCE_UM:BTCUSDT@third")).contains("front").contains("next")
    }

    @Test
    fun `symbols of undeclared roots and resolvable symbols have no reason`() {
        assertThat(registry.missingReason("EXNESS:XAUUSD")).isNull()
        assertThat(registry.missingReason("BINANCE_UM:ETHUSDT_240927")).isNull()
        assertThat(registry.missingReason("BINANCE_UM:BTCUSDT_240927")).isNull()
    }
}
