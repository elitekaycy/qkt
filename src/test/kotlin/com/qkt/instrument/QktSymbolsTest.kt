package com.qkt.instrument

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class QktSymbolsTest {
    @Test
    fun `venue-qualified symbols made of safe characters pass`() {
        listOf("CME:ESZ6", "BINANCE_UM:BTCUSDT_240927", "BINANCE_UM:BTCUSDT@front", "DERIBIT:BTC_USDC_27SEP24_60000_C")
            .forEach(QktSymbols::requireFileSafe)
    }

    @Test
    fun `path separators, spaces and missing venues are refused`() {
        listOf("CME:ES/Z6", "CME:ES Z6", "ESZ6", ":ESZ6", "CME:").forEach { bad ->
            assertThatThrownBy { QktSymbols.requireFileSafe(bad) }.hasMessageContaining(bad)
        }
    }

    @Test
    fun `dot-only names that would escape a directory are refused`() {
        assertThatThrownBy { QktSymbols.requireFileSafe("CME:..") }.hasMessageContaining("CME:..")
        assertThat(runCatching { QktSymbols.requireFileSafe("CME:ES.c.0") }.isSuccess).isTrue()
    }
}
