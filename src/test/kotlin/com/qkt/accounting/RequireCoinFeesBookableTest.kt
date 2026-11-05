package com.qkt.accounting

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequireCoinFeesBookableTest {
    @Test
    fun `a coin pair quoted in the account currency needs no fx config, its own price is the coin's rate`() {
        // #1641: soak_o07_tday traded BYBIT_SPOT:ETHUSDT with no fx_conversion.symbols.ETHUSD.
        assertThatCode {
            AccountingEngine().requireCoinFeesBookable(
                listOf("BYBIT_SPOT:ETHUSDT", "ALPACA:BTCUSD", "DERIBIT:BTC_USDC", "EXNESS:XAUUSD", "EXNESS:EURUSD"),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `a coin with no rate is refused at start with the exact config line`() {
        val engine = AccountingEngine(AccountingConfig(symbols = mapOf("EURUSD" to "OANDA:EURUSD")))

        assertThatThrownBy { engine.requireCoinFeesBookable(listOf("BYBIT_SPOT:ETHEUR")) }
            .hasMessageContaining("BYBIT_SPOT:ETHEUR")
            .hasMessageContaining("fx_conversion.symbols.ETHUSD")
    }

    @Test
    fun `a configured coin pair satisfies the start check`() {
        val config = AccountingConfig(symbols = mapOf("EURUSD" to "OANDA:EURUSD", "ETHUSD" to "BYBIT_SPOT:ETHUSDT"))

        assertThatCode { AccountingEngine(config).requireCoinFeesBookable(listOf("BYBIT_SPOT:ETHEUR")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a coin fee on a separated pair is priced from the fill`() {
        val fee = VenueCost(CostKind.COMMISSION, MoneyAmount(BigDecimal("0.0001"), "BTC"), 4_000L)

        val converted = AccountingEngine().convertCost(fee, "DERIBIT:BTC_USDC", BigDecimal("90000"))

        assertThat(converted.account.amount).isEqualByComparingTo("9")
        assertThat(converted.conversion?.source).isEqualTo("context:DERIBIT:BTC_USDC")
    }
}
