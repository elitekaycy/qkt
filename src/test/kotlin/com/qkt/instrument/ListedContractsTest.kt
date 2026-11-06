package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ListedContractsTest {
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
            perpetual = "BTCUSDT",
        )
    private val catalog =
        ContractCatalog(
            btc.root,
            listOf(
                ListedContract("BTCUSDT_240927", 1_727_424_000_000L, "65528.1"),
                ListedContract("BTCUSDT_241227", 1_735_286_400_000L),
            ),
        )
    private val registry = ContractCatalogRegistry(listOf(btc), mapOf(btc.root to catalog))

    @Test
    fun `a dated contract resolves its catalog entry and delivery price`() {
        assertThat(registry.listedContract("BINANCE_UM:BTCUSDT_240927")?.expiryMs).isEqualTo(1_727_424_000_000L)
        assertThat(registry.deliveryPrice("BINANCE_UM:BTCUSDT_240927")).isEqualByComparingTo("65528.1")
        assertThat(registry.deliveryPrice("BINANCE_UM:BTCUSDT_241227")).isNull()
    }

    @Test
    fun `continuous views, perpetuals and unknown symbols are not dated contracts`() {
        assertThat(registry.listedContract("BINANCE_UM:BTCUSDT@front")).isNull()
        assertThat(registry.listedContract("BINANCE_UM:BTCUSDT")).isNull()
        assertThat(registry.listedContract("EXNESS:XAUUSD")).isNull()
        assertThat(StandardInstrumentRegistry.deliveryPrice("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `futures symbols are the continuous streams, listed contracts and perpetual of a declared root`() {
        val symbols =
            listOf("BINANCE_UM:BTCUSDT@front", "BINANCE_UM:BTCUSDT_240927", "BINANCE_UM:BTCUSDT", "EXNESS:XAUUSD")

        assertThat(registry.futuresSymbols(symbols))
            .containsExactlyInAnyOrder("BINANCE_UM:BTCUSDT@front", "BINANCE_UM:BTCUSDT_240927", "BINANCE_UM:BTCUSDT")
        assertThat(StandardInstrumentRegistry.futuresSymbols(symbols)).isEmpty()
    }
}
