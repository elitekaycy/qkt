package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaintenanceMarginGateTest {
    private var below = true
    private val gate = MaintenanceMarginGate { below }
    private val longOne =
        object : PositionProvider {
            override fun positionFor(symbol: String) = Position(symbol, BigDecimal.ONE, BigDecimal("60000"))

            override fun allPositions() = mapOf("X" to Position("X", BigDecimal.ONE, BigDecimal("60000")))
        }

    private fun order(side: Side) =
        OrderRequest.Market("o", "X", side, BigDecimal.ONE, TimeInForce.GTC, 0L, strategyId = "s")

    @Test
    fun `below maintenance an order that adds risk is refused`() {
        val decision = gate.evaluate(order(Side.BUY), longOne)

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("below maintenance margin")
    }

    @Test
    fun `below maintenance an order that reduces a position passes`() {
        assertThat(gate.evaluate(order(Side.SELL), longOne)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `above maintenance every order passes the gate`() {
        below = false

        assertThat(gate.evaluate(order(Side.BUY), longOne)).isEqualTo(Decision.Approve)
    }
}
