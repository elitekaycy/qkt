package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [ExitRetryClamp] keeps a retried exit reduce-only against what the strategy still holds. */
class ExitRetryClampTest {
    private fun holding(qty: String?) =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? =
                qty?.let { Position(symbol, BigDecimal(it), BigDecimal.TEN) }

            override fun allPositions(): Map<String, Position> = emptyMap()
        }

    private fun market(
        side: Side,
        qty: String,
    ) = Signal.Submit(OrderRequest.Market("o1", "S", side, BigDecimal(qty), TimeInForce.GTC, 0L))

    @Test
    fun `a buy retry against a short is cut to the short's size`() {
        val clamped = ExitRetryClamp.clamp(listOf(Signal.Buy("S", BigDecimal("5"))), holding("-2"))

        assertThat(clamped).containsExactly(Signal.Buy("S", BigDecimal("2")))
    }

    @Test
    fun `a retry that would add to the position or open one is dropped`() {
        assertThat(ExitRetryClamp.clamp(listOf(Signal.Buy("S", BigDecimal.ONE)), holding("3"))).isEmpty()
        assertThat(ExitRetryClamp.clamp(listOf(Signal.Sell("S", BigDecimal.ONE)), holding(null))).isEmpty()
    }

    @Test
    fun `a market order retry against the position is cut to the held size`() {
        val clamped = ExitRetryClamp.clamp(listOf(market(Side.SELL, "4")), holding("1.5")).single() as Signal.Submit

        assertThat(clamped.request.quantity).isEqualByComparingTo("1.5")
        assertThat(ExitRetryClamp.clamp(listOf(market(Side.BUY, "4")), holding("1.5"))).isEmpty()
    }
}
