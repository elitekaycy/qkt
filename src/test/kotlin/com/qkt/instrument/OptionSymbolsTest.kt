package com.qkt.instrument

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OptionSymbolsTest {
    @Test
    fun `venue names and qkt codes map both ways exactly, decimal strikes included`() {
        for (name in listOf("BTC_USDC-25DEC26-92000-C", "AVAX_USDC-1OCT26-9d5-P")) {
            val code = OptionSymbols.qktCode(name)
            assertThat(code).doesNotContain("-")
            assertThat(OptionSymbols.venueName(code, name.substringBefore('-'))).isEqualTo(name)
        }
        assertThat(OptionSymbols.qktCode("BTC_USDC-25DEC26-92000-C")).isEqualTo("BTC_USDC_25DEC26_92000_C")
    }

    @Test
    fun `a code that is not an option of the root has no venue name`() {
        assertThat(OptionSymbols.venueName("BTC_USDC_PERPETUAL", "BTC_USDC")).isNull()
        assertThat(OptionSymbols.venueName("ETH_USDC_25DEC26_3000_C", "BTC_USDC")).isNull()
        assertThat(OptionSymbols.venueName("BTC_USDC_25DEC26_92000_X", "BTC_USDC")).isNull()
        assertThatThrownBy {
            OptionSymbols.qktCode(
                "BTC_USDC-25DEC26-92000",
            )
        }.hasMessageContaining("BTC_USDC-25DEC26-92000")
    }

    @Test
    fun `the qkt code is a symbol a strategy can name, the venue name is not`() {
        fun parses(symbol: String) =
            Dsl.parse(
                "STRATEGY o VERSION 1\nSYMBOLS\n    opt = $symbol EVERY 1h\nRULES\n    WHEN opt.close > 0\n    THEN BUY opt SIZING 0.1\n",
            ) is ParseResult.Success

        assertThat(parses("DERIBIT:BTC_USDC_25DEC26_92000_C")).isTrue()
        assertThat(parses("DERIBIT:AVAX_USDC_1OCT26_9d5_P")).isTrue()
        assertThat(parses("DERIBIT:BTC_USDC-25DEC26-92000-C")).isFalse()
    }
}
