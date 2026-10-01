package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewayQuoteTicksTest {
    private val symbol = "DERIBIT:BTC_USDC_25DEC26_92000_C"

    private fun quote(
        bid: String? = null,
        ask: String? = null,
        mark: String? = null,
    ) = WireQuote("BTC_USDC-25DEC26-92000-C", bid, ask, "1.2", "0.8", mark, "52.3", "84437.55", 1_000L)

    @Test
    fun `a quote with a mark is priced at the mark and keeps its book`() {
        val tick = gatewayQuoteTick(symbol, quote("640", "655", "648.5"))!!

        assertThat(tick.symbol).isEqualTo(symbol)
        assertThat(tick.price).isEqualByComparingTo("648.5")
        assertThat(tick.timestamp).isEqualTo(1_000L)
        assertThat(tick.bid).isEqualByComparingTo("640")
        assertThat(tick.ask).isEqualByComparingTo("655")
        assertThat(tick.bidVolume).isEqualByComparingTo("1.2")
        assertThat(tick.askVolume).isEqualByComparingTo("0.8")
    }

    @Test
    fun `a quote without a mark is priced at the mid of its two sides`() {
        assertThat(gatewayQuoteTick(symbol, quote("640", "655"))!!.price).isEqualByComparingTo("647.5")
    }

    @Test
    fun `a one-sided quote without a mark gives no tick, with a mark it trades on its side only`() {
        assertThat(gatewayQuoteTick(symbol, quote(ask = "655"))).isNull()

        val tick = gatewayQuoteTick(symbol, quote(ask = "655", mark = "648.5"))!!
        assertThat(tick.bid).isNull()
        assertThat(tick.bidVolume).isNull()
        assertThat(tick.ask).isEqualByComparingTo("655")
    }

    @Test
    fun `a crossed or zero-priced book trades on neither side`() {
        val crossed = gatewayQuoteTick(symbol, quote("660", "655", "650"))!!
        assertThat(crossed.bid).isNull()
        assertThat(crossed.ask).isNull()
        assertThat(gatewayQuoteTick(symbol, quote("0", "655"))).isNull()
    }

    @Test
    fun `a mark at or below zero is no price at all`() {
        assertThat(gatewayQuoteTick(symbol, quote("640", "655", "0"))).isNull()
    }
}
