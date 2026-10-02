package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollPolicy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LiveSymbolChecksTest {
    @Test
    fun `a continuous futures stream without a declared root and roll history is refused live`() {
        assertThatThrownBy { requireLiveTradable(listOf("BINANCE_UM:BTCUSDT@front"), AccountingEngine()) }
            .hasMessageContaining("BINANCE_UM:BTCUSDT@front")
            .hasMessageContaining("roll history")
    }

    @Test
    fun `a continuous stream the session serves live passes, one it does not is refused by name`() {
        val roll = { iso: String -> Instant.parse(iso).toEpochMilli() }
        val root =
            FuturesRoot(
                "BINANCE_UM:BTCUSDT",
                "USDT",
                BigDecimal.ONE,
                BigDecimal("0.1"),
                BigDecimal("0.001"),
                BigDecimal("0.001"),
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
            )
        val catalog = ContractCatalog(root.root, listOf(ListedContract("BTCUSDT_240927", roll("2024-09-27T08:00:00Z"))))
        val chains = ContinuousChains(ContractCatalogRegistry(listOf(root), mapOf(root.root to catalog)))

        requireLiveTradable(listOf("BINANCE_UM:BTCUSDT@front"), AccountingEngine(), chains)
        assertThatThrownBy { requireLiveTradable(listOf("CME:ES@front"), AccountingEngine(), chains) }
            .hasMessageContaining("CME:ES@front")
    }

    @Test
    fun `cfd symbols keep today's checks`() {
        assertThatCode { requireLiveTradable(listOf("EXNESS:XAUUSD"), AccountingEngine()) }.doesNotThrowAnyException()
        assertThatThrownBy {
            requireLiveTradable(
                listOf("EXNESS:EURGBP"),
                AccountingEngine(),
            )
        }.hasMessageContaining("GBP")
    }

    @Test
    fun `a live chain stream needs its root fed, which records the chain it is computed from`() {
        val iv = "CHAIN:DERIBIT.BTC_USDC.atm_iv.30d"

        assertThatThrownBy { requireLiveTradable(listOf(iv), AccountingEngine()) }
            .hasMessageContaining("OPTIONS:DERIBIT.BTC_USDC")
        assertThatCode {
            requireLiveTradable(listOf("OPTIONS:DERIBIT.BTC_USDC", iv), AccountingEngine())
        }.doesNotThrowAnyException()
    }
}
