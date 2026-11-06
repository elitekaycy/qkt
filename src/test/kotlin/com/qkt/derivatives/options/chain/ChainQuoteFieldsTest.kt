package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChainQuoteFieldsTest {
    private fun quote(
        contract: String = "BTC_USDC-2OCT26-92000-C",
        underlying: String = "83000",
        markAgeMs: Long = 0L,
        index: String? = null,
    ) = ChainQuote(
        atMs = 1L,
        contract = contract,
        bid = null,
        ask = null,
        mark = BigDecimal("100"),
        markIv = null,
        underlying = BigDecimal(underlying),
        rate = null,
        markAgeMs = markAgeMs,
        source = QuoteSource.BOOK,
        index = index?.let(::BigDecimal),
    )

    @Test
    fun `a quote refuses a blank contract, a negative age and non-positive prices`() {
        assertThatThrownBy { quote(contract = " ") }.hasMessageContaining("contract must be a code without commas")
        assertThatThrownBy { quote(markAgeMs = -1L) }.hasMessage("ChainQuote.markAgeMs must be >= 0: -1")
        assertThatThrownBy { quote(underlying = "0") }.hasMessage("ChainQuote.underlying must be > 0: 0")
        assertThatThrownBy { quote(index = "0") }.hasMessage("ChainQuote.index must be > 0: 0")
        assertThat(quote(index = "83010").index).isEqualByComparingTo("83010")
    }
}
