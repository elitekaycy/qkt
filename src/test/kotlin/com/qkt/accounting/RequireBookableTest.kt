package com.qkt.accounting

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequireBookableTest {
    @Test
    fun `an explicit foreign currency without a conversion path is refused at start`() {
        val engine = AccountingEngine(currencyOf = { if (it == "EUREX:FDAXZ6") "EUR" else null })
        assertThatThrownBy { engine.requireBookable(listOf("EUREX:FDAXZ6")) }
            .hasMessageContaining("EUREX:FDAXZ6")
            .hasMessageContaining("EUR")
    }

    @Test
    fun `explicit dollar-family and suffix-quoted symbols keep today's outcome`() {
        val engine = AccountingEngine(currencyOf = { if (it == "CME:ESZ6") "USD" else null })
        assertThatCode { engine.requireBookable(listOf("CME:ESZ6", "EXNESS:XAUUSD", "BYBIT_SPOT:BTCUSDT")) }
            .doesNotThrowAnyException()
        assertThatThrownBy { engine.requireBookable(listOf("EXNESS:EURGBP")) }.hasMessageContaining("GBP")
    }

    @Test
    fun `the quote currency is null only when neither explicit nor inferable`() {
        val engine = AccountingEngine(currencyOf = { if (it == "CME:6EZ6") "eur" else null })
        assertThat(engine.quoteCurrencyOf("CME:6EZ6")).isEqualTo("EUR")
        assertThat(engine.quoteCurrencyOf("EXNESS:EURJPY")).isEqualTo("JPY")
        assertThat(engine.quoteCurrencyOf("CME:ESZ6")).isNull()
    }
}
