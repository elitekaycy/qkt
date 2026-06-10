package com.qkt.instrument

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class QuoteCurrencyGuardTest {
    @Test
    fun `dollar-quoted symbols pass`() {
        QuoteCurrencyGuard.assertAccountQuoted(
            listOf("EXNESS:XAUUSD", "EXNESS:EURUSD", "BYBIT_SPOT:BTCUSDT", "BYBIT_SPOT:ETHUSDC"),
        )
    }

    @Test
    fun `symbols with no recognizable currency tail pass`() {
        QuoteCurrencyGuard.assertAccountQuoted(listOf("EXNESS:US30", "ALPACA:AAPL"))
    }

    @Test
    fun `JPY-quoted symbol is rejected with the symbol and quote named`() {
        assertThatThrownBy { QuoteCurrencyGuard.assertAccountQuoted(listOf("EXNESS:USDJPY")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("EXNESS:USDJPY")
            .hasMessageContaining("JPY")
    }

    @Test
    fun `CHF CAD and cross-quoted symbols are rejected`() {
        for (sym in listOf("EXNESS:USDCHF", "EXNESS:USDCAD", "EXNESS:EURGBP", "EXNESS:EURJPY")) {
            assertThatThrownBy { QuoteCurrencyGuard.assertAccountQuoted(listOf(sym)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `contract-size meta is required for FX and metals, not crypto or stocks`() {
        assertThat(QuoteCurrencyGuard.requiresContractSizeMeta("EXNESS:XAUUSD")).isTrue()
        assertThat(QuoteCurrencyGuard.requiresContractSizeMeta("EXNESS:EURUSD")).isTrue()
        // USD-quoted crypto is a unit contract — BTCUSD must not be refused at bind.
        assertThat(QuoteCurrencyGuard.requiresContractSizeMeta("BACKTEST:BTCUSD")).isFalse()
        assertThat(QuoteCurrencyGuard.requiresContractSizeMeta("BYBIT_SPOT:BTCUSDT")).isFalse()
        assertThat(QuoteCurrencyGuard.requiresContractSizeMeta("ALPACA:AAPL")).isFalse()
    }

    @Test
    fun `quote inference reads the trailing currency code`() {
        assertThat(QuoteCurrencyGuard.quoteOf("EXNESS:USDJPY")).isEqualTo("JPY")
        assertThat(QuoteCurrencyGuard.quoteOf("BYBIT_SPOT:BTCUSDT")).isEqualTo("USDT")
        assertThat(QuoteCurrencyGuard.quoteOf("EXNESS:GBPUSD")).isEqualTo("USD")
        assertThat(QuoteCurrencyGuard.quoteOf("EXNESS:US30")).isNull()
    }
}
