package com.qkt.connector.gateway

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * qkt gives every DAY order its symbol's session end as an expiry, for venues without a day of their own; a VGP venue
 * ends its own day, so a DAY order with that expiry is sent as `day`, and only an expiry on another kind is refused.
 */
class GatewayDayOrderTest {
    private val positions = StrategyPositionTracker().account

    private fun limit(
        tif: TimeInForce,
        expiresAt: Long?,
    ) = OrderRequest.Limit(
        id = "ORD-1",
        symbol = "ALPACA:AAPL",
        side = Side.BUY,
        quantity = Money.of("1"),
        limitPrice = Money.of("340"),
        timeInForce = tif,
        timestamp = 1_000L,
        strategyId = "s",
        expiresAt = expiresAt,
    )

    @Test
    fun `a DAY order carrying its session-end expiry is sent as day`() {
        val mapped =
            GatewayOrders.map(
                limit(TimeInForce.DAY, 1_793_130_300_000),
                "AAPL",
                positions,
                BigDecimal.ZERO,
                null,
            )
        assertThat(mapped).isInstanceOf(GatewayOrderMapping.Send::class.java)
        assertThat((mapped as GatewayOrderMapping.Send).body.timeInForce).isEqualTo("day")
    }

    @Test
    fun `an expiry on any other order is refused, and GTD has no VGP form`() {
        assertThat(GatewayOrders.map(limit(TimeInForce.GTC, 5_000), "AAPL", positions, BigDecimal.ZERO, null))
            .isEqualTo(GatewayOrderMapping.Unsupported("VGP v1 orders cannot carry an expiry"))
        assertThat(GatewayOrders.map(limit(TimeInForce.GTD, 5_000), "AAPL", positions, BigDecimal.ZERO, null))
            .isEqualTo(GatewayOrderMapping.Unsupported("VGP v1 has no good-till-date orders"))
    }
}
