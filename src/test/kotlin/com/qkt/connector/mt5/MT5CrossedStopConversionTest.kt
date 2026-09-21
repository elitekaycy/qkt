package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5CrossedStopConversionTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")

    /** Ask 1.1052 / bid 1.1048: a BUY stop is judged against the ask, a SELL stop against the bid. */
    private val quotes =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal("1.1050")

            override fun executionPrice(
                symbol: String,
                side: Side,
            ): BigDecimal? = if (side == Side.BUY) BigDecimal("1.1052") else BigDecimal("1.1048")
        }

    private fun stop(
        side: Side,
        stopPrice: String,
    ) = OrderRequest.Stop(
        id = "breakout",
        symbol = "EXNESS:EURUSD",
        side = side,
        quantity = BigDecimal("0.1"),
        stopPrice = BigDecimal(stopPrice),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `a buy stop already below the ask goes out as a market order with the same id`() {
        val converted =
            MT5CrossedStopConversion(
                profile,
                quotes,
            ).convertAlreadyCrossedStopAtMarket(stop(Side.BUY, "1.1051"))

        assertThat(converted).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(converted.id).isEqualTo("breakout")
    }

    @Test
    fun `a sell stop is judged against the bid, not the ask`() {
        val request = stop(Side.SELL, "1.1047")

        assertThat(
            MT5CrossedStopConversion(profile, quotes).convertAlreadyCrossedStopAtMarket(request),
        ).isSameAs(request)
    }

    @Test
    fun `without a price source the stop is sent unchanged`() {
        val request = stop(Side.BUY, "1.1051")

        assertThat(MT5CrossedStopConversion(profile, null).convertAlreadyCrossedStopAtMarket(request)).isSameAs(request)
    }
}
