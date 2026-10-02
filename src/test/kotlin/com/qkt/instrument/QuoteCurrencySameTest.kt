package com.qkt.instrument

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuoteCurrencySameTest {
    @Test
    fun `the dollar family books one to one`() {
        assertThat(QuoteCurrencyGuard.sameCurrency("USD", "usdt")).isTrue()
        assertThat(QuoteCurrencyGuard.sameCurrency("USDC", "USD")).isTrue()
    }

    @Test
    fun `different currencies do not`() {
        assertThat(QuoteCurrencyGuard.sameCurrency("EUR", "USD")).isFalse()
        assertThat(QuoteCurrencyGuard.sameCurrency("eur", "EUR")).isTrue()
    }
}
