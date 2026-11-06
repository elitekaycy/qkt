package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A collared market sell on a thin contract that ends without filling, for reasons other than its collar. */
class GatewayMarketCollarEndsTest {
    private val symbol = "D:BTC_USDC_4NOV26"
    private val clock = FixedClock(time = 1_000L)
    private var reference: BigDecimal? = BigDecimal("77400")
    private val events = mutableListOf<BrokerEvent>()
    private val sent = mutableListOf<OrderRequest>()
    private val scheduled = ArrayDeque<() -> Unit>()
    private val collar =
        GatewayMarketCollar(
            BigDecimal("0.25"),
            object : MarketPriceProvider {
                override fun lastPrice(symbol: String): BigDecimal? = reference
            },
            clock,
            collars = { it == symbol },
            exits = { false },
            send = { sent += it },
            later = { _, action -> scheduled += action },
            publish = { events += it },
        )

    private fun startSelling() =
        collar.first(OrderRequest.Market("o", symbol, Side.SELL, BigDecimal("1.0"), TimeInForce.GTC, 900L, "s"))

    @Test
    fun `a rejection that is not about price passes straight to the engine and ends the order`() {
        startSelling()
        val rejected = BrokerEvent.OrderRejected("o", null, "insufficient funds", "s", 1L)

        collar.heard(rejected)

        assertThat(events).containsExactly(rejected)
        assertThat(collar.cancelled("o")).isFalse()
    }

    @Test
    fun `an order event the collar does not handle passes through unchanged`() {
        startSelling()
        val modified = BrokerEvent.OrderModified("o", "v")

        collar.heard(modified)

        assertThat(events).containsExactly(modified)
    }

    @Test
    fun `a retry finding no price any more rejects the order`() {
        startSelling()
        collar.heard(BrokerEvent.OrderCancelled("o", "v", "nothing within the collar", "s", 1L))
        reference = null

        scheduled.removeFirst().invoke()

        val rejected = events.single() as BrokerEvent.OrderRejected
        assertThat(rejected.reason)
            .isEqualTo("market order on $symbol not filled: its price went away between attempts")
        assertThat(sent).isEmpty()
    }
}
