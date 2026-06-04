package com.qkt.cli.daemon

import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradeLogTest {
    @Test
    fun `formats a trade line with side, symbol, qty, price and realized`() {
        val t =
            Trade(
                orderId = "o1",
                symbol = "EXNESS:XAUUSD",
                price = BigDecimal("2351"),
                quantity = BigDecimal("0.2"),
                side = Side.SELL,
                timestamp = 0L,
            )
        val line = TradeLog.line(t, BigDecimal("12.50"))
        assertThat(line).isEqualTo("trade SELL EXNESS:XAUUSD qty=0.2 px=2351 realized=12.50")
    }
}
