package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Orders whose trigger the market has not reached, or that have no trigger, go out as they are. Ask 1.1052. */
class MT5UncrossedStopTest {
    private val ask = BigDecimal("1.1052")

    @Test
    fun `a buy stop limit above the ask stays a stop limit`() {
        val stopLimit =
            OrderRequest.StopLimit(
                id = "breakout",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1060"),
                limitPrice = BigDecimal("1.1065"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        assertThat(convertAlreadyCrossedStop(stopLimit, ask)).isSameAs(stopLimit)
    }

    @Test
    fun `a buy stop limit at or below the ask goes out as its limit`() {
        val stopLimit =
            OrderRequest.StopLimit(
                id = "breakout",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                limitPrice = BigDecimal("1.1055"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        val converted = convertAlreadyCrossedStop(stopLimit, ask) as OrderRequest.Limit

        assertThat(converted.id).isEqualTo("breakout")
        assertThat(converted.limitPrice).isEqualByComparingTo("1.1055")
    }

    @Test
    fun `an order with no trigger is sent unchanged`() {
        val market =
            OrderRequest.Market("m-1", "EXNESS:EURUSD", Side.BUY, BigDecimal("0.1"), TimeInForce.GTC, 0L)

        assertThat(convertAlreadyCrossedStop(market, ask)).isSameAs(market)
    }
}
