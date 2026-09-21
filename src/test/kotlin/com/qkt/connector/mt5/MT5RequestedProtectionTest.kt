package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5RequestedProtectionTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")
    private val protection = MT5RequestedProtection(MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy)))

    private val market =
        OrderRequest.Market(
            id = "m-1",
            symbol = "EXNESS:EURUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    @Test
    fun `a bracket remembers the stop and target it sends to the venue`() {
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = market,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.0500")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )

        val requested = protection.protectionFor(bracket)

        assertThat(requested?.stopLoss).isEqualByComparingTo("1.0500")
        assertThat(requested?.takeProfit).isEqualByComparingTo("1.1500")
    }

    @Test
    fun `an order sent without stop or target has no protection to remember`() {
        assertThat(protection.protectionFor(market)).isNull()
    }

    @Test
    fun `a wire order with only a stop keeps the target empty`() {
        val wire =
            MT5OrderRequest(
                symbol = "EURUSDm",
                volume = BigDecimal("0.1"),
                type = "BUY",
                sl = BigDecimal("1.0500"),
                magic = 1,
                comment = "m-1",
            )

        assertThat(protection.protectionOf(wire)).isEqualTo(MT5PositionProtection(BigDecimal("1.0500"), null))
    }
}
