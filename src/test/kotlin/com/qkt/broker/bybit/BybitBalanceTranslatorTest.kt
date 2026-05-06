package com.qkt.broker.bybit

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitBalanceTranslatorTest {
    @Test
    fun `parseWalletBalance extracts coin balances from valid response`() {
        val response =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED",
            "coin":[{"coin":"BTC","walletBalance":"0.5","availableToWithdraw":"0.5"},
            {"coin":"USDT","walletBalance":"30000","availableToWithdraw":"30000"}]}]}}
            """.trimIndent()

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).containsOnlyKeys("BTC", "USDT")
        assertThat(balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(balances["USDT"]).isEqualByComparingTo(BigDecimal("30000"))
    }

    @Test
    fun `parseWalletBalance returns empty map when result list empty`() {
        val response = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).isEmpty()
    }

    @Test
    fun `parseWalletBalance returns empty map when account has no coins`() {
        val response =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[]}]}}"""

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).isEmpty()
    }

    @Test
    fun `parseWalletBalance skips coins with blank walletBalance`() {
        val response =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED",
            "coin":[{"coin":"BTC","walletBalance":""},{"coin":"USDT","walletBalance":"100"}]}]}}
            """.trimIndent()

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).containsOnlyKeys("USDT")
    }
}
