package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.instrument.TickSteps
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Engine orders mapped onto VGP v1 bodies, on a contract with a 0.5 tick. */
class GatewayOrdersTest {
    private val grid = TickSteps(BigDecimal("0.5"))

    private fun holding(quantity: String?) =
        object : PositionProvider {
            override fun positionFor(symbol: String): Position? =
                quantity?.let { Position(symbol, BigDecimal(it), BigDecimal.ONE) }

            override fun allPositions(): Map<String, Position> = emptyMap()
        }

    private fun stopLimit(
        side: Side,
        id: String = "sl-1",
        tif: TimeInForce = TimeInForce.FOK,
    ) = OrderRequest.StopLimit(
        id = id,
        symbol = "DERIBIT:BTC",
        side = side,
        quantity = BigDecimal("2"),
        stopPrice = BigDecimal("100.2"),
        limitPrice = BigDecimal("100.2"),
        timeInForce = tif,
        timestamp = 1_000L,
    )

    private fun send(
        request: OrderRequest,
        positions: PositionProvider = holding(null),
        accountHeld: String = "0",
    ): WireSubmit {
        val mapped = GatewayOrders.map(request, "BTC-PERP", positions, BigDecimal(accountHeld), grid)
        assertThat(mapped).isInstanceOf(GatewayOrderMapping.Send::class.java)
        return (mapped as GatewayOrderMapping.Send).body
    }

    @Test
    fun `a buy stop limit triggers at or above its stop and buys at or below its limit`() {
        val body = send(stopLimit(Side.BUY))

        assertThat(body.type).isEqualTo("stop_limit")
        assertThat(body.side).isEqualTo("buy")
        assertThat(body.stopPrice).isEqualTo("100.5")
        assertThat(body.limitPrice).isEqualTo("100.0")
        assertThat(body.timeInForce).isEqualTo("fok")
        assertThat(body.symbol).isEqualTo("BTC-PERP")
        assertThat(body.quantity).isEqualTo("2")
    }

    @Test
    fun `a sell stop limit rounds the other way`() {
        val body = send(stopLimit(Side.SELL, tif = TimeInForce.IOC))

        assertThat(body.side).isEqualTo("sell")
        assertThat(body.stopPrice).isEqualTo("100.0")
        assertThat(body.limitPrice).isEqualTo("100.5")
        assertThat(body.timeInForce).isEqualTo("ioc")
    }

    @Test
    fun `a sell that closes part of both the strategy's and the account's long is reduce only`() {
        assertThat(send(stopLimit(Side.SELL), holding("3"), accountHeld = "5").reduceOnly).isTrue()
        assertThat(send(stopLimit(Side.BUY), holding("-3"), accountHeld = "-2").reduceOnly).isTrue()
    }

    @Test
    fun `an order larger than either position, the same side, or with nothing held opens and is not reduce only`() {
        assertThat(send(stopLimit(Side.SELL), holding("1"), accountHeld = "5").reduceOnly).isFalse()
        assertThat(send(stopLimit(Side.SELL), holding("3"), accountHeld = "1").reduceOnly).isFalse()
        assertThat(send(stopLimit(Side.BUY), holding("3"), accountHeld = "3").reduceOnly).isFalse()
        assertThat(send(stopLimit(Side.SELL), holding(null), accountHeld = "3").reduceOnly).isFalse()
        assertThat(send(stopLimit(Side.SELL), holding("0"), accountHeld = "3").reduceOnly).isFalse()
    }

    @Test
    fun `an order id longer than the venue allows is not sent`() {
        // A 36-character engine id plus a submit stamp far past 2059 (13 base-36 digits) is 50 characters.
        val request = stopLimit(Side.BUY, id = "x".repeat(36)).copy(timestamp = Long.MAX_VALUE)

        val mapped = GatewayOrders.map(request, "BTC-PERP", holding(null), BigDecimal.ZERO, grid)

        assertThat(mapped).isInstanceOf(GatewayOrderMapping.Unsupported::class.java)
        assertThat((mapped as GatewayOrderMapping.Unsupported).reason).contains("longer than 45 characters")
    }

    @Test
    fun `an order type VGP v1 has no form for is not sent`() {
        val trailing =
            OrderRequest.TrailingStop(
                "t-1",
                "DERIBIT:BTC",
                Side.SELL,
                BigDecimal.ONE,
                BigDecimal.TEN,
                TrailMode.ABSOLUTE,
                TimeInForce.GTC,
                1_000L,
            )

        assertThat(GatewayOrders.map(trailing, "BTC-PERP", holding(null), BigDecimal.ZERO, grid))
            .isEqualTo(GatewayOrderMapping.Unsupported("TrailingStop has no VGP v1 order type"))
    }

    @Test
    fun `a level with no grid is sent as given`() {
        val limit =
            OrderRequest.Limit(
                "l-1",
                "DERIBIT:BTC",
                Side.BUY,
                BigDecimal.ONE,
                BigDecimal("100.23"),
                TimeInForce.GTC,
                1L,
            )

        val noGrid =
            GatewayOrders.map(limit, "BTC-PERP", holding(null), BigDecimal.ZERO, null) as GatewayOrderMapping.Send

        assertThat(noGrid.body.limitPrice).isEqualTo("100.23")
    }
}
