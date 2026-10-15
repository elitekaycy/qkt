package com.qkt.broker

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An MT5 venue refuses a limit on the wrong side of the market instead of filling it; the simulator must too. */
class MT5LimitSideRefusalTest {
    private fun limit(
        side: Side,
        price: String,
    ) = OrderRequest.Limit("l", "X", side, Money.of("1"), Money.of(price), TimeInForce.GTC, 0L)

    @Test
    fun `a buy limit at or above the ask is refused, below it rests`() {
        assertThat(mt5LimitSideRefusal(limit(Side.BUY, "101"), Money.of("100"))).startsWith("invalid price: BUY_LIMIT")
        assertThat(mt5LimitSideRefusal(limit(Side.BUY, "100"), Money.of("100"))).isNotNull()
        assertThat(mt5LimitSideRefusal(limit(Side.BUY, "99"), Money.of("100"))).isNull()
    }

    @Test
    fun `a sell limit at or below the bid is refused, above it rests`() {
        assertThat(mt5LimitSideRefusal(limit(Side.SELL, "99"), Money.of("100"))).startsWith("invalid price: SELL_LIMIT")
        assertThat(mt5LimitSideRefusal(limit(Side.SELL, "101"), Money.of("100"))).isNull()
    }

    @Test
    fun `other order types are not judged here`() {
        val market = OrderRequest.Market("m", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 0L)

        assertThat(mt5LimitSideRefusal(market, Money.of("100"))).isNull()
    }
}
