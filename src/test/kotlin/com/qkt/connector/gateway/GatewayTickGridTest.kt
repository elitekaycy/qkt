package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Levels reach the venue on the contract's tick grid (5 here), never filling or triggering early. */
internal class GatewayTickGridTest : GatewayHarness() {
    private fun sent(request: OrderRequest): WireOrder {
        broker(session(), Strategy(), "a").submit(request)
        await { synchronized(fake) { fake.venue.orders.containsKey(wire(request.id)) } }
        return synchronized(fake) { fake.venue.orders.getValue(wire(request.id)) }
    }

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(id, symbol, side, BigDecimal("0.1"), BigDecimal(price), TimeInForce.GTC, 5L, "a")

    private fun stop(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Stop(id, symbol, side, BigDecimal("0.1"), BigDecimal(price), TimeInForce.GTC, 5L, "a")

    @Test
    fun `a buy limit rounds down and a sell limit up`() {
        assertThat(sent(limit("b", Side.BUY, "652.3")).limitPrice).isEqualTo("650")
        assertThat(sent(limit("s", Side.SELL, "652.3")).limitPrice).isEqualTo("655")
    }

    @Test
    fun `a buy stop rounds up and a sell stop down`() {
        assertThat(sent(stop("b", Side.BUY, "641.2")).stopPrice).isEqualTo("645")
        assertThat(sent(stop("s", Side.SELL, "643.8")).stopPrice).isEqualTo("640")
    }

    @Test
    fun `a level already on the grid is sent unchanged`() {
        assertThat(sent(limit("b", Side.BUY, "655")).limitPrice).isEqualTo("655")
    }
}
