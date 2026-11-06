package com.qkt.accounting

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MoneyAmountTest {
    @Test
    fun `an account currency prints as its trimmed upper-case code`() {
        assertThat(AccountCurrency(" usd ").toString()).isEqualTo("USD")
    }

    @Test
    fun `an account currency that is not a three to five letter code is refused`() {
        assertThatThrownBy { AccountCurrency("us") }
            .hasMessage("account currency must be a 3-5 letter code: us")
        assertThatThrownBy { AccountCurrency("DOLLAR") }
            .hasMessage("account currency must be a 3-5 letter code: DOLLAR")
        assertThatThrownBy { AccountCurrency("US1") }
            .hasMessage("account currency must be a 3-5 letter code: US1")
    }

    @Test
    fun `a money amount needs a currency code and normalizes it`() {
        assertThat(MoneyAmount(BigDecimal.ONE, "usdt").normalizedCurrency).isEqualTo("USDT")
        assertThatThrownBy { MoneyAmount(BigDecimal.ONE, "$") }
            .hasMessage("money currency must be a 3-5 letter code: $")
    }

    @Test
    fun `a venue cost priced with the default context converts like any same-currency amount`() {
        val engine = AccountingEngine()
        val cost = VenueCost(CostKind.values().first(), MoneyAmount(BigDecimal("2.5"), "USD"), timestamp = 7L)

        val converted = engine.convertCost(cost)

        assertThat(converted.account.amount).isEqualByComparingTo("2.5")
        assertThat(converted.conversion?.source).isEqualTo("identity")
    }
}
