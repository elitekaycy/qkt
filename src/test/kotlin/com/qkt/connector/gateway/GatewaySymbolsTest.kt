package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewaySymbolsTest {
    private val symbols = GatewaySymbols("DERIBIT:").apply { update(listOf("BTC_USDC-27DEC26", "BTC_USDC-PERPETUAL")) }

    @Test
    fun `a listed code maps both ways and an unlisted option still finds its code by rule`() {
        assertThat(symbols.code("DERIBIT:BTC_USDC_27DEC26")).isEqualTo("BTC_USDC-27DEC26")
        assertThat(symbols.venue("DERIBIT:BTC_USDC_26SEP26_90000_P")).isNull()
        assertThat(symbols.code("DERIBIT:BTC_USDC_26SEP26_90000_P")).isEqualTo("BTC_USDC-26SEP26-90000-P")
        assertThat(symbols.code("DERIBIT:BTC_USDC_26SEP26_92d5_C")).isEqualTo("BTC_USDC-26SEP26-92d5-C")
    }

    @Test
    fun `an unlisted future, another account's symbol, or a malformed option has no code`() {
        assertThat(symbols.code("DERIBIT:BTC_USDC_26SEP26")).isNull()
        assertThat(symbols.code("OKX:BTC_USDC_26SEP26_90000_P")).isNull()
        assertThat(symbols.owns("OKX:BTC_USDC_26SEP26_90000_P")).isFalse()
        assertThat(symbols.code("DERIBIT:BTC_USDC_26SEP26_90000_X")).isNull()
    }
}
