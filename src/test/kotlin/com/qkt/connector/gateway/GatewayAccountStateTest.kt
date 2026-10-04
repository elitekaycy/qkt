package com.qkt.connector.gateway

import com.qkt.broker.PositionAccountingMode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewayAccountStateTest {
    private val code = "XAUUSD"

    @Test
    fun `on a hedging account a position event is one ticket, and the code's position is their sum`() {
        val state = GatewayAccountState()
        state.apply(
            GatewaySyncState(
                BigDecimal("1000"),
                PositionAccountingMode.HEDGING,
                mapOf(code to BigDecimal("0.3")),
                mapOf(code to mapOf("t1" to BigDecimal("0.1"), "t2" to BigDecimal("0.2"))),
            ),
        )

        state.position(WirePosition(code, "0.5", "1", ticket = "t2"))
        assertThat(state.quantity(code)).isEqualByComparingTo("0.6")
        state.position(WirePosition(code, "0", "0", ticket = "t1"))
        assertThat(state.quantity(code)).isEqualByComparingTo("0.5")
    }

    @Test
    fun `on a netting account a position event is the code's whole position`() {
        val state = GatewayAccountState()

        state.position(WirePosition(code, "0.4", "1"))
        state.position(WirePosition(code, "-0.1", "1"))

        assertThat(state.quantity(code)).isEqualByComparingTo("-0.1")
    }
}
