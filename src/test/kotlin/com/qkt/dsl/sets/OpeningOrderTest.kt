package com.qkt.dsl.sets

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpeningOrderTest {
    @Test
    fun `the order a signal sends is read from buys, sells and typed orders only`() {
        assertThat(Signal.Buy("X:A", BigDecimal.ONE).orderSide()).isEqualTo("X:A" to Side.BUY)
        assertThat(Signal.Sell("X:A", BigDecimal.ONE).orderSide()).isEqualTo("X:A" to Side.SELL)
        val limit =
            OrderRequest.Limit(
                id = "o",
                symbol = "X:B",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal.TEN,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        assertThat(Signal.Submit(limit).orderSide()).isEqualTo("X:B" to Side.SELL)
        assertThat(Signal.CancelPendingForSymbol("X:A").orderSide()).isNull()
    }

    @Test
    fun `an order opens or adds unless it reduces the holding`() {
        assertThat(opensOrAdds(Side.BUY, 0)).isTrue()
        assertThat(opensOrAdds(Side.SELL, 0)).isTrue()
        assertThat(opensOrAdds(Side.BUY, 1)).isTrue()
        assertThat(opensOrAdds(Side.SELL, -1)).isTrue()
        assertThat(opensOrAdds(Side.SELL, 1)).isFalse()
        assertThat(opensOrAdds(Side.BUY, -1)).isFalse()
    }
}
