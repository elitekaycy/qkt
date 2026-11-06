package com.qkt.connector.mt5

import com.qkt.broker.PositionAccountingMode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every configured account expectation refuses a venue account that differs from it. */
class MT5AccountMismatchTest {
    private val profile = MT5DefaultProfiles.exness
    private val account =
        MT5AccountInfo(
            balance = BigDecimal("10000"),
            equity = BigDecimal("10000"),
            currency = "USD",
            leverage = 100,
            marginMode = MARGIN_MODE_NETTING,
            login = 7L,
            server = "Exness-MT5Trial9",
            tradeMode = MT5TradeMode.DEMO.wireValue,
        )

    @Test
    fun `a different trade server is refused, but case and padding are not differences`() {
        assertThatThrownBy { MT5AccountVerifier.verify(profile.copy(expectedAccountServer = "Exness-Real"), account) }
            .hasMessageContaining("account server mismatch: expected 'Exness-Real', got 'Exness-MT5Trial9'")
        assertThatCode {
            MT5AccountVerifier.verify(profile.copy(expectedAccountServer = " exness-mt5trial9 "), account)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `a different account currency is refused`() {
        assertThatThrownBy { MT5AccountVerifier.verify(profile.copy(expectedAccountCurrency = "EUR"), account) }
            .hasMessageContaining("account currency mismatch: expected EUR, got USD")
    }

    @Test
    fun `a different leverage is refused`() {
        assertThatThrownBy { MT5AccountVerifier.verify(profile.copy(expectedLeverage = 500), account) }
            .hasMessageContaining("leverage mismatch: expected 500, got 100")
    }

    @Test
    fun `a netting account passes a netting expectation and an unknown mode fails it by name`() {
        val netting = profile.copy(expectedMarginMode = PositionAccountingMode.NETTING)

        assertThatCode { MT5AccountVerifier.verify(netting, account) }.doesNotThrowAnyException()
        assertThatThrownBy { MT5AccountVerifier.verify(netting, account.copy(marginMode = 5)) }
            .hasMessageContaining("expected netting, got unknown(5)")
    }

    @Test
    fun `an unknown trade mode is named by its number`() {
        assertThatThrownBy {
            MT5AccountVerifier.verify(profile.copy(expectedTradeMode = MT5TradeMode.REAL), account.copy(tradeMode = 9))
        }.hasMessageContaining("expected real, got unknown(9)")
    }
}
