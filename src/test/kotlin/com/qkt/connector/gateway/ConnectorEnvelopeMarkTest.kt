package com.qkt.connector.gateway

import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.observe.insights.InsightsEnvelope
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A VGP account's positions carry no mark, profit or open time on the wire (VGP v1, the paper adapter): its
 * `state.positions` envelope states the session's own mark and qkt's valuation of it, and the open time qkt
 * booked. A symbol the session has no quote for yet stays null, never zero; swap stays the venue's (null).
 */
internal class ConnectorEnvelopeMarkTest : ConnectorEnvelopeHarness() {
    private val prices = MarketPriceTracker()
    private val marks = PnLCalculator(StrategyPositionTracker().account, prices)

    @Test
    fun `a VGP position states the session's mark, its floating profit and qkt's open time`() {
        val broker = vgpBroker()
        prices.update(Tick(symbol, BigDecimal("655.5"), NOW, bid = BigDecimal("655"), ask = BigDecimal("656")))

        val position = positionOf(envelopes(broker, deployed = setOf("a"), marks))

        val entry = position["entryPrice"] as BigDecimal
        assertThat(position["currentPrice"] as BigDecimal).isEqualByComparingTo("655")
        // A long 0.1 closes at the bid: (655 - entry) x 0.1, the same rule qkt's own equity uses.
        val expected = BigDecimal("655").subtract(entry).multiply(BigDecimal("0.1"))
        assertThat(position["profit"] as BigDecimal).isEqualByComparingTo(expected)
        assertThat(position["openedAt"]).isEqualTo(NOW - 2_000)
        assertThat(position["swap"]).isNull()
    }

    @Test
    fun `a VGP position on a symbol with no quote yet has no mark and no profit, never zero`() {
        val position = positionOf(envelopes(vgpBroker(), deployed = setOf("a"), marks))

        assertThat(position["currentPrice"]).isNull()
        assertThat(position["profit"]).isNull()
        assertThat(position["openedAt"]).isEqualTo(NOW - 2_000)
    }

    @Test
    fun `an MT5 position keeps the mark and profit its venue states`() {
        prices.update(Tick("EXNESS:XAUUSD", BigDecimal("1"), NOW, bid = BigDecimal("1"), ask = BigDecimal("1")))

        val position = positionOf(envelopes(mt5Broker(), deployed = setOf("hedge_straddle"), marks))

        assertThat(position["currentPrice"] as BigDecimal).isEqualByComparingTo("2310.2")
        assertThat(position["profit"] as BigDecimal).isEqualByComparingTo("9.7")
    }

    @Suppress("UNCHECKED_CAST")
    private fun positionOf(envelopes: Map<String, InsightsEnvelope>): Map<String, Any?> =
        (envelopes.getValue("state.positions").payload["positions"] as List<Map<String, Any?>>).first()
}
