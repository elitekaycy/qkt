package com.qkt.app

import com.qkt.accounting.AccountingEngine
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LiveSymbolChecksTest {
    @Test
    fun `a continuous futures stream is refused live`() {
        assertThatThrownBy { requireLiveTradable(listOf("BINANCE_UM:BTCUSDT@front"), AccountingEngine()) }
            .hasMessageContaining("BINANCE_UM:BTCUSDT@front")
            .hasMessageContaining("backtest")
    }

    @Test
    fun `cfd symbols keep today's checks`() {
        assertThatCode { requireLiveTradable(listOf("EXNESS:XAUUSD"), AccountingEngine()) }.doesNotThrowAnyException()
        assertThatThrownBy {
            requireLiveTradable(
                listOf("EXNESS:EURGBP"),
                AccountingEngine(),
            )
        }.hasMessageContaining("GBP")
    }

    @Test
    fun `a live chain stream needs its root fed, which records the chain it is computed from`() {
        val iv = "CHAIN:DERIBIT.BTC_USDC.atm_iv.30d"

        assertThatThrownBy { requireLiveTradable(listOf(iv), AccountingEngine()) }
            .hasMessageContaining("OPTIONS:DERIBIT.BTC_USDC")
        assertThatCode {
            requireLiveTradable(listOf("OPTIONS:DERIBIT.BTC_USDC", iv), AccountingEngine())
        }.doesNotThrowAnyException()
    }
}
