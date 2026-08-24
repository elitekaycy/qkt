package com.qkt.broker.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5AccountVerifierTest {
    private val account =
        MT5AccountInfo(
            balance = BigDecimal("10000"),
            equity = BigDecimal("10000"),
            currency = "USD",
            leverage = 100,
            marginMode = MARGIN_MODE_HEDGING,
            login = 435898347L,
            server = "Exness-MT5Trial9",
            tradeMode = MT5TradeMode.DEMO.wireValue,
        )

    @Test
    fun `matching account identity passes`() {
        MT5AccountVerifier.verify(profile(), account)
    }

    @Test
    fun `wrong login refuses cutover`() {
        assertThatThrownBy {
            MT5AccountVerifier.verify(profile().copy(expectedAccountLogin = 999L), account)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("account login mismatch")
    }

    @Test
    fun `demo account refuses real cutover`() {
        assertThatThrownBy {
            MT5AccountVerifier.verify(profile().copy(expectedTradeMode = MT5TradeMode.REAL), account)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected real, got demo")
    }

    @Test
    fun `netting expectation refuses a hedging account`() {
        // The engine's position model would silently diverge from the venue's (#1071).
        assertThatThrownBy {
            MT5AccountVerifier.verify(
                profile().copy(expectedMarginMode = com.qkt.broker.PositionAccountingMode.NETTING),
                account,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("margin mode mismatch")
            .hasMessageContaining("expected netting, got hedging")
    }

    private fun profile(): MT5BrokerProfile =
        MT5DefaultProfiles.exness.copy(
            expectedAccountLogin = account.login,
            expectedAccountServer = account.server,
            expectedTradeMode = MT5TradeMode.DEMO,
            expectedAccountCurrency = account.currency,
            expectedLeverage = account.leverage,
            expectedMarginMode = com.qkt.broker.PositionAccountingMode.HEDGING,
        )
}
