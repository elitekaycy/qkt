package com.qkt.trade.venue

import com.qkt.connectivity.VenueFacts
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotVenueFactsTest {
    private val facts =
        VenueFacts(
            bid = BigDecimal("2650.0"),
            ask = BigDecimal("2650.5"),
            equity = BigDecimal("1000"),
            balance = BigDecimal("900"),
            accountCurrency = null,
            contractSize = BigDecimal("100"),
            quoteCurrency = null,
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            volumeMax = BigDecimal("50"),
            digits = 2,
            reference = BigDecimal("2650.2"),
        )

    @Test
    fun `missing currencies fall back to the config account and the symbol's quote`() {
        val ctx = facts.toQuoteContext("EXNESS:XAUUSD", "EUR")

        assertThat(ctx.accountCurrency).isEqualTo("EUR")
        assertThat(ctx.quoteCurrency).isEqualTo("USD")
        assertThat(ctx.reference).isEqualByComparingTo("2650.2")
        assertThat(ctx.volumeMax).isEqualByComparingTo("50")
    }

    @Test
    fun `currencies the venue names win over the fallbacks`() {
        val ctx = facts.copy(accountCurrency = "USD", quoteCurrency = "JPY").toQuoteContext("EXNESS:XAUUSD", "EUR")

        assertThat(ctx.accountCurrency).isEqualTo("USD")
        assertThat(ctx.quoteCurrency).isEqualTo("JPY")
    }

    @Test
    fun `a quote that is not positive is refused`() {
        assertThatThrownBy { facts.copy(bid = BigDecimal.ZERO).toQuoteContext("EXNESS:XAUUSD", "USD") }
            .hasMessage("venue quote for EXNESS:XAUUSD is not positive: bid=0 ask=2650.5")
        assertThatThrownBy { facts.copy(ask = BigDecimal("-1")).toQuoteContext("EXNESS:XAUUSD", "USD") }
            .hasMessageContaining("is not positive")
    }

    @Test
    fun `a numeric venue id renders as a number and any other as text`() {
        assertThat(idJson("123")).isEqualTo(123L)
        assertThat(idJson("ord-9")).isEqualTo("ord-9")
    }
}
