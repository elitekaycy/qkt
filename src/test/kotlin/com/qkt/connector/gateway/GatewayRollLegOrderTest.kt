package com.qkt.connector.gateway

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.TickSteps
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A roll's collared leg reaches a VGP venue as what keeps it from resting: a limit, `ioc`, on the contract's grid. */
class GatewayRollLegOrderTest {
    private val positions = StrategyPositionTracker().account

    private fun leg(
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = "roll:1:abc:close",
        symbol = "PAPER:BTC_USDC-3NOV26",
        side = side,
        quantity = Money.of("0.001"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.IOC,
        timestamp = 1_000L,
        strategyId = "s",
    )

    private fun body(
        side: Side,
        price: String,
    ) = (
        GatewayOrders.map(
            leg(side, price),
            "BTC_USDC-3NOV26",
            positions,
            BigDecimal.ZERO,
            TickSteps(BigDecimal("2.5")),
        ) as GatewayOrderMapping.Send
    ).body

    @Test
    fun `it is sent as an immediate-or-cancel limit, snapped never beyond the collar`() {
        val buy = body(Side.BUY, "97501")
        assertThat(buy.type).isEqualTo("limit")
        assertThat(buy.timeInForce).isEqualTo("ioc")
        assertThat(BigDecimal(buy.limitPrice!!)).isEqualByComparingTo("97500")
        assertThat(BigDecimal(body(Side.SELL, "58501").limitPrice!!)).isEqualByComparingTo("58502.5")
    }
}
