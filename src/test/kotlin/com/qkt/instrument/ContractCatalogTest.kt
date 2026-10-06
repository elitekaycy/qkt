package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContractCatalogTest {
    @Test
    fun `contracts are kept in expiry order`() {
        val catalog =
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                listOf(
                    ListedContract("BTCUSDT_241227", 1_735_286_400_000L),
                    ListedContract("BTCUSDT_240927", 1_727_424_000_000L),
                ),
            )
        assertThat(catalog.sorted().contracts.map { it.symbol }).containsExactly("BTCUSDT_240927", "BTCUSDT_241227")
    }

    @Test
    fun `duplicate contract symbols are refused`() {
        val c = ListedContract("BTCUSDT_240927", 1L)
        assertThatThrownBy { ContractCatalog("BINANCE_UM:BTCUSDT", listOf(c, c)) }.hasMessageContaining("duplicate")
    }

    @Test
    fun `a delivery price is parsed exactly`() {
        assertThat(ListedContract("BTCUSDT_240927", 1L, "65528.1").deliveryPriceOrNull())
            .isEqualByComparingTo(BigDecimal("65528.1"))
        assertThat(ListedContract("BTCUSDT_240927", 1L).deliveryPriceOrNull()).isNull()
    }

    @Test
    fun `a non-positive delivery price is refused`() {
        assertThatThrownBy { ListedContract("BTCUSDT_240927", 1L, "0") }.hasMessageContaining("deliveryPrice")
    }
}
