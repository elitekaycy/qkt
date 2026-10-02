package com.qkt.accounting

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountingCurrencyOfTest {
    @Test
    fun `explicit currency wins over the symbol suffix guess`() {
        val engine = AccountingEngine(currencyOf = { if (it == "CME:6EZ6") "EUR" else null })
        assertThat(engine.pnlCurrencyFor("CME:6EZ6")).isEqualTo("EUR")
    }

    @Test
    fun `symbols without an explicit currency keep the suffix guess`() {
        val engine = AccountingEngine(currencyOf = { null })
        assertThat(engine.pnlCurrencyFor("EXNESS:EURJPY")).isEqualTo("JPY")
        assertThat(engine.pnlCurrencyFor("CME:ESZ6")).isEqualTo(engine.accountCurrency)
    }

    @Test
    fun `explicit currency is upper-cased`() {
        val engine = AccountingEngine(currencyOf = { "usdt" })
        assertThat(engine.pnlCurrencyFor("BINANCE_UM:BTCUSDT_240927")).isEqualTo("USDT")
    }
}
